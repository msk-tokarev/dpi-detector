package com.dpidetector.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetState(
    val online: Boolean = false,
    val transport: String = "Нет сети",
    val vpnActive: Boolean = false,
)

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(readNow())
    val state: StateFlow<NetState> = _state.asStateFlow()

    init {
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = refresh()
            })
        } catch (_: Throwable) {
            // Fallback to manual refresh()
        }
    }

    fun refresh() {
        _state.value = readNow()
    }

    private fun readNow(): NetState {
        return try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            @Suppress("DEPRECATION")
            val allNetworks = cm.allNetworks
            val hasVpn = allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } || (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)

            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                NetState(online = false, transport = "Нет сети", vpnActive = hasVpn)
            } else {
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "Сеть"
                }
                NetState(online = true, transport = transport, vpnActive = hasVpn)
            }
        } catch (_: Throwable) {
            NetState(online = true, transport = "Неизвестно", vpnActive = false)
        }
    }
}
