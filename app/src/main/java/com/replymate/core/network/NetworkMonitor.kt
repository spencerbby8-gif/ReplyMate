package com.replymate.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface NetworkStatus { data object Available : NetworkStatus; data object Unavailable : NetworkStatus }

/** Process-scoped connectivity signal. It does not perform a network probe. */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<NetworkStatus> = _status
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _status.value = currentStatus() }
        override fun onLost(network: Network) { _status.value = currentStatus() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { _status.value = currentStatus() }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
    private fun currentStatus(): NetworkStatus {
        val network = manager.activeNetwork ?: return NetworkStatus.Unavailable
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkStatus.Unavailable
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) NetworkStatus.Available else NetworkStatus.Unavailable
    }
}
