package com.toclient.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.toclient.android.compat.AndroidCompatibility
import com.toclient.android.compat.CompatibilityIssue
import com.toclient.android.vpn.VpnRuntime
import com.toclient.core.connection.ConnectionState
import com.toclient.core.model.ProfileId

/**
 * A thin system-UI controller over the existing VPN service.
 * It never owns a second connection engine or invents connection state.
 */
class ToVpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile(VpnRuntime.state.value)
    }

    override fun onClick() {
        super.onClick()
        when (VpnRuntime.state.value) {
            ConnectionState.Idle, is ConnectionState.Failed -> connectSelectedOrOpenApp()
            else -> AndroidCompatibility.stopVpn(this)
        }
        updateTile(VpnRuntime.state.value)
    }

    private fun connectSelectedOrOpenApp() {
        val rawId = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SELECTED_PROFILE, null)
        val profileId = rawId?.let { runCatching { ProfileId(it) }.getOrNull() }
        val report = AndroidCompatibility.inspect(this)
        if (profileId == null ||
            CompatibilityIssue.VPN_CONSENT_REQUIRED in report.issues ||
            CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED in report.issues ||
            CompatibilityIssue.NATIVE_ABI_UNSUPPORTED in report.issues
        ) {
            openApp()
            return
        }
        runCatching { AndroidCompatibility.startVpn(this, profileId) }
            .onFailure { openApp() }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    42,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: ConnectionState) {
        qsTile?.apply {
            val persian = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_PERSIAN, false)
            when (state) {
                ConnectionState.Idle, is ConnectionState.Failed -> {
                    this.state = Tile.STATE_INACTIVE
                    label = if (persian) "قطع" else "Disconnected"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = if (persian) "TO Client" else "TO Client"
                }
                is ConnectionState.Connected -> {
                    this.state = Tile.STATE_ACTIVE
                    label = if (persian) "متصل" else "Connected"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = "TO Client"
                }
                else -> {
                    // Keep the tile actionable so a second tap can cancel a pending connection.
                    this.state = Tile.STATE_ACTIVE
                    label = if (persian) "در حال اتصال…" else "Connecting…"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = "TO Client"
                }
            }
            updateTile()
        }
    }

    companion object {
        private const val PREFS = "to_client_ui"
        private const val KEY_SELECTED_PROFILE = "selected_profile"
        private const val KEY_PERSIAN = "persian"
    }
}