package com.toclient.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class PhysicalNetworkMonitor(
    context: Context,
    private val onChanged: (String) -> Unit,
) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private var validatedNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            synchronized(this@PhysicalNetworkMonitor) {
                if (validatedNetwork == network) {
                    validatedNetwork = null
                    onChanged("validated network lost: $network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                synchronized(this@PhysicalNetworkMonitor) {
                    val previous = validatedNetwork
                    validatedNetwork = network
                    if (previous != null && previous != network) {
                        onChanged("validated network changed: $previous -> $network")
                    }
                }
            }
        }
    }

    fun start() {
        if (started) return
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback,
        )
        started = true
    }

    override fun close() {
        if (!started) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        started = false
        validatedNetwork = null
    }
}