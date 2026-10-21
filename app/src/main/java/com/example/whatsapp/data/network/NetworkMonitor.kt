package com.example.whatsapp.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

data class NetworkStatus(val isOnline: Boolean, val isUnmetered: Boolean)

/**
 * Observes real connectivity, combined with a debug switch that lets you simulate going offline
 * (airplane mode without leaving the app), which is the easiest way to demo the outbox and resync.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _simulateOffline = MutableStateFlow(false)
    val simulateOffline: StateFlow<Boolean> = _simulateOffline.asStateFlow()

    /**
     * Tracks the *default* network only. A plain registerNetworkCallback reports every network,
     * so e.g. losing Wi-Fi while validated cellular takes over can leave the app wrongly "offline",
     * and a background network's capability changes can overwrite the real status.
     */
    private val system = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.toStatus())
            }

            override fun onLost(network: Network) {
                // The new default network (if any) is reported via onCapabilitiesChanged next.
                trySend(NetworkStatus(isOnline = false, isUnmetered = false))
            }
        }
        trySend(currentStatus())
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    val status: StateFlow<NetworkStatus> =
        combine(system, _simulateOffline) { real, forcedOffline ->
            if (forcedOffline) NetworkStatus(isOnline = false, isUnmetered = false) else real
        }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, currentStatus())

    fun setSimulateOffline(offline: Boolean) {
        _simulateOffline.value = offline
    }

    private fun currentStatus(): NetworkStatus {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return caps?.toStatus() ?: NetworkStatus(isOnline = false, isUnmetered = false)
    }

    private fun NetworkCapabilities.toStatus() = NetworkStatus(
        isOnline = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        isUnmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
