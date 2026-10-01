package com.toclient.android.compat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.toclient.android.vpn.ToVpnService
import com.toclient.core.model.ProfileId

data class CompatibilityReport(
    val sdk: Int,
    val supportedAbis: List<String>,
    val notificationPermissionGranted: Boolean,
    val vpnConsentGranted: Boolean,
    val issues: List<CompatibilityIssue>,
)

enum class CompatibilityIssue {
    ANDROID_VERSION_UNSUPPORTED,
    NATIVE_ABI_UNSUPPORTED,
    NOTIFICATION_PERMISSION_REQUIRED,
    VPN_CONSENT_REQUIRED,
}

object AndroidCompatibility {
    private val packagedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun inspect(context: Context): CompatibilityReport {
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val vpnGranted = VpnService.prepare(context) == null
        val abiSupported = Build.SUPPORTED_ABIS.any(packagedAbis::contains)
        val issues = buildList {
            if (Build.VERSION.SDK_INT < 24) add(CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED)
            if (!abiSupported) add(CompatibilityIssue.NATIVE_ABI_UNSUPPORTED)
            if (!notificationGranted) add(CompatibilityIssue.NOTIFICATION_PERMISSION_REQUIRED)
            if (!vpnGranted) add(CompatibilityIssue.VPN_CONSENT_REQUIRED)
        }
        return CompatibilityReport(
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            notificationPermissionGranted = notificationGranted,
            vpnConsentGranted = vpnGranted,
            issues = issues,
        )
    }

    fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn(context: Context, profileId: ProfileId) {
        val report = inspect(context)
        require(CompatibilityIssue.ANDROID_VERSION_UNSUPPORTED !in report.issues)
        require(CompatibilityIssue.NATIVE_ABI_UNSUPPORTED !in report.issues)
        require(CompatibilityIssue.VPN_CONSENT_REQUIRED !in report.issues) {
            "VPN user consent is required"
        }
        ContextCompat.startForegroundService(
            context,
            ToVpnService.connectIntent(context, profileId),
        )
    }

    fun stopVpn(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ToVpnService::class.java).setAction(ToVpnService.ACTION_DISCONNECT),
        )
    }
}