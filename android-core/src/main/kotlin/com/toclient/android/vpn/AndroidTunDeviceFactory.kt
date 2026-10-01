package com.toclient.android.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.toclient.core.connection.ConnectionCoordinator
import com.toclient.core.contract.TunDevice
import com.toclient.core.contract.TunDeviceFactory
import com.toclient.core.model.PerAppMode
import com.toclient.core.model.VpnProfile
import com.toclient.core.routing.Ipv4RoutePlanner
import com.toclient.core.routing.PolicyValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidTunDeviceFactory(
    private val service: VpnService,
) : TunDeviceFactory {
    override suspend fun establish(profile: VpnProfile): TunDevice = withContext(Dispatchers.IO) {
        check(VpnService.prepare(service) == null) {
            "Android VPN user consent is required before connecting"
        }
        PolicyValidator.validate(profile)
        val builder = service.Builder()
            .setSession("TO Client")
            .setMtu(profile.mtu)
            .addAddress(ConnectionCoordinator.DEFAULT_TUN_IPV4, 30)
            .setBlocking(true)

        Ipv4RoutePlanner.includedRoutes(profile.routing).forEach {
            builder.addRoute(it.address, it.prefixLength)
        }
        profile.dns.servers.forEach(builder::addDnsServer)
        if (profile.ipv6Enabled) {
            builder.addAddress(ConnectionCoordinator.DEFAULT_TUN_IPV6, 126)
            builder.addRoute("::", 0)
        }

        applyPerAppPolicy(builder, profile)
        val descriptor = builder.establish() ?: error("Android refused to establish the VPN TUN")
        ParcelTunDevice(descriptor)
    }

    private fun applyPerAppPolicy(builder: VpnService.Builder, profile: VpnProfile) {
        when (profile.perApp.mode) {
            PerAppMode.ONLY_SELECTED -> profile.perApp.packages.forEach { packageName ->
                addKnownPackage(packageName) { builder.addAllowedApplication(it) }
            }
            PerAppMode.ALL_APPS -> {
                // Excluding our own UID prevents Xray's upstream sockets from looping into the TUN.
                builder.addDisallowedApplication(service.packageName)
            }
            PerAppMode.BYPASS_SELECTED -> {
                (profile.perApp.packages + service.packageName).forEach { packageName ->
                    addKnownPackage(packageName) { builder.addDisallowedApplication(it) }
                }
            }
        }
    }

    private inline fun addKnownPackage(packageName: String, operation: (String) -> Unit) {
        try {
            operation(packageName)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Application is not installed: $packageName", error)
        }
    }
}

private class ParcelTunDevice(
    private val descriptor: ParcelFileDescriptor,
) : TunDevice {
    override val fileDescriptor: Int
        get() = descriptor.fd

    override fun close() {
        descriptor.close()
    }
}