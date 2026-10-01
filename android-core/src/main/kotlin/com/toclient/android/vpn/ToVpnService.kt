package com.toclient.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.toclient.android.engine.XrayEngineAdapter
import com.toclient.android.persistence.AndroidProfileRepository
import com.toclient.android.tun.HevTunBridge
import com.toclient.core.connection.ConnectionCoordinator
import com.toclient.core.connection.ConnectionState
import com.toclient.core.connection.XrayConfigPlanner
import com.toclient.core.contract.Sleeper
import com.toclient.core.model.ProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

class ToVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var coordinator: ConnectionCoordinator
    private lateinit var networkMonitor: PhysicalNetworkMonitor
    private var activeOperation: Job? = null
    private var trafficOperation: Job? = null
    private val sessionTraffic = SessionTrafficAccumulator()
    private var latestTraffic: com.toclient.core.model.TrafficSnapshot? = null
    private var previousTrafficAt = 0L
    private var previousUpload = 0L
    private var previousDownload = 0L
    private var uploadRate = 0L
    private var downloadRate = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        coordinator = ConnectionCoordinator(
            profiles = AndroidProfileRepository(this),
            engine = XrayEngineAdapter(this),
            tunFactory = AndroidTunDeviceFactory(this),
            bridge = HevTunBridge(this),
            configPlanner = XrayConfigPlanner(),
            sleeper = Sleeper { delay(it) },
        )
        VpnRuntime.attach(coordinator)
        networkMonitor = PhysicalNetworkMonitor(this) { reason ->
            scope.launch {
                if (coordinator.state.value is ConnectionState.Connected) {
                    runCatching { coordinator.handleNetworkChanged(reason) }
                }
            }
        }
        scope.launch {
            coordinator.state.collectLatest { state ->
                VpnRuntime.publishState(state)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(state, latestTraffic))
                requestTileRefresh()
                trafficOperation?.cancel()
                trafficOperation = null
                if (state is ConnectionState.Connected) {
                    trafficOperation = scope.launch {
                        while (isActive) {
                            runCatching { coordinator.traffic() }
                                .onSuccess { raw ->
                                    val cumulative = sessionTraffic.accumulate(raw)
                                    updateTrafficRates(cumulative)
                                    latestTraffic = cumulative
                                    VpnRuntime.publishTraffic(cumulative)
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, notification(state, cumulative))
                                }
                            delay(1_000)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(coordinator.state.value, latestTraffic))
        when (intent?.action) {
            ACTION_CONNECT -> {
                val rawProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                    ?: return START_NOT_STICKY
                getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ACTIVE_PROFILE, rawProfileId)
                    .apply()
                resetSessionTraffic()
                activeOperation?.cancel()
                activeOperation = scope.launch {
                    runCatching { coordinator.connect(ProfileId(rawProfileId)) }
                        .onSuccess { networkMonitor.start() }
                }
            }
            ACTION_DISCONNECT -> {
                getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ACTIVE_PROFILE)
                    .apply()
                activeOperation?.cancel()
                activeOperation = scope.launch {
                    networkMonitor.close()
                    coordinator.disconnect()
                    resetSessionTraffic()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            null -> {
                val rawProfileId = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                    .getString(KEY_ACTIVE_PROFILE, null)
                if (rawProfileId == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeOperation?.cancel()
                activeOperation = scope.launch {
                    runCatching { coordinator.connect(ProfileId(rawProfileId)) }
                        .onSuccess { networkMonitor.start() }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        scope.launch { coordinator.disconnect() }
        super.onRevoke()
    }

    override fun onDestroy() {
        networkMonitor.close()
        runBlocking(Dispatchers.IO) {
            runCatching { coordinator.disconnect() }
        }
        scope.cancel()
        VpnRuntime.detach(coordinator)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun notification(
        state: ConnectionState,
        traffic: com.toclient.core.model.TrafficSnapshot?,
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                2,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ToVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val persian = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_PERSIAN, false)
        val showTraffic = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_SHOW_NOTIFICATION_TRAFFIC, false)
        val stateText = state.label(persian)
        val trafficText = if (showTraffic && traffic != null) {
            val current = localizedString(
                com.toclient.android.core.R.string.notification_current_traffic,
                persian,
                formatBytes(downloadRate),
                formatBytes(uploadRate),
            )
            val overall = localizedString(
                com.toclient.android.core.R.string.notification_overall_traffic,
                persian,
                formatBytes(traffic.engineTotal.downloadedBytes),
                formatBytes(traffic.engineTotal.uploadedBytes),
            )
            "$stateText\n$current\n$overall"
        } else {
            stateText
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.toclient.android.core.R.drawable.ic_to_notification)
            .setContentTitle("TO Client")
            .setContentText(stateText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(trafficText))
            .setContentIntent(contentIntent)
            .setOngoing(state !is ConnectionState.Idle && state !is ConnectionState.Failed)
            .setOnlyAlertOnce(true)
            .apply {
                if (state !is ConnectionState.Idle && state !is ConnectionState.Failed) {
                    addAction(0, if (persian) "قطع اتصال" else "Disconnect", disconnectIntent)
                }
            }
            .build()
    }

    private fun updateTrafficRates(snapshot: com.toclient.core.model.TrafficSnapshot) {
        if (previousTrafficAt > 0) {
            val elapsed = (snapshot.capturedAtMillis - previousTrafficAt).coerceAtLeast(1)
            uploadRate = ((snapshot.engineTotal.uploadedBytes - previousUpload)
                .coerceAtLeast(0) * 1_000L) / elapsed
            downloadRate = ((snapshot.engineTotal.downloadedBytes - previousDownload)
                .coerceAtLeast(0) * 1_000L) / elapsed
        }
        previousTrafficAt = snapshot.capturedAtMillis
        previousUpload = snapshot.engineTotal.uploadedBytes
        previousDownload = snapshot.engineTotal.downloadedBytes
    }

    private fun resetSessionTraffic() {
        sessionTraffic.reset()
        latestTraffic = null
        previousTrafficAt = 0
        previousUpload = 0
        previousDownload = 0
        uploadRate = 0
        downloadRate = 0
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
        value >= 1_024L -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
        else -> "$value B"
    }

    private fun localizedString(id: Int, persian: Boolean, vararg args: Any): String {
        val configuration = android.content.res.Configuration(resources.configuration).apply {
            setLocale(if (persian) Locale("fa") else Locale.ENGLISH)
        }
        return createConfigurationContext(configuration).resources.getString(id, *args)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VPN connection",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun requestTileRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                this,
                ComponentName(packageName, "$packageName.ToVpnTileService"),
            )
        }
    }

    private fun ConnectionState.label(persian: Boolean): String = when (this) {
        ConnectionState.Idle -> if (persian) "قطع" else "Disconnected"
        is ConnectionState.Preparing -> if (persian) "در حال آماده‌سازی" else "Preparing"
        is ConnectionState.StartingEngine -> if (persian) "در حال راه‌اندازی موتور" else "Starting secure engine"
        is ConnectionState.EstablishingTun -> if (persian) "در حال برقراری VPN" else "Establishing VPN"
        is ConnectionState.StartingBridge -> if (persian) "در حال باز کردن مسیر بسته" else "Opening packet path"
        is ConnectionState.Connected -> if (persian) "متصل" else "Connected"
        is ConnectionState.Reconnecting -> if (persian) "در حال اتصال مجدد" else "Reconnecting (attempt $attempt)"
        is ConnectionState.Stopping -> if (persian) "در حال قطع" else "Disconnecting"
        is ConnectionState.Failed -> if (persian) "اتصال ناموفق: $message" else "Connection failed: $message"
    }

    companion object {
        private const val CHANNEL_ID = "to_client_vpn"
        private const val NOTIFICATION_ID = 10_001
        private const val SERVICE_PREFS = "to_client_vpn_service"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val UI_PREFS = "to_client_ui"
        private const val KEY_PERSIAN = "persian"
        private const val KEY_SHOW_NOTIFICATION_TRAFFIC = "show_notification_traffic_totals"
        private const val EXTRA_PROFILE_ID = "com.toclient.extra.PROFILE_ID"
        const val ACTION_CONNECT = "com.toclient.action.CONNECT"
        const val ACTION_DISCONNECT = "com.toclient.action.DISCONNECT"

        fun connectIntent(context: Context, profileId: ProfileId): Intent =
            Intent(context, ToVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE_ID, profileId.value)
    }
}