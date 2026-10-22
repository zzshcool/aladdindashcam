package com.aladdin.dashcam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiNetwork = MutableStateFlow<Network?>(null)
    val wifiNetwork: StateFlow<Network?> = _wifiNetwork.asStateFlow()
    private val _wifiLinkRevision = MutableStateFlow(0L)
    val wifiLinkRevision: StateFlow<Long> = _wifiLinkRevision.asStateFlow()
    private var lastGatewayIp: String? = null

    private val _cellularAvailable = MutableStateFlow(false)
    val cellularAvailable: StateFlow<Boolean> = _cellularAvailable.asStateFlow()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                Log.i(TAG, "Wi-Fi network available: $network")
                _wifiNetwork.value = network
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                Log.i(TAG, "Wi-Fi link properties updated for network: $network")
                _wifiNetwork.value = network
                val gateway = ipv4Gateway(linkProperties)
                if (gateway != lastGatewayIp) {
                    lastGatewayIp = gateway
                    _wifiLinkRevision.value += 1
                }
            }
        }

        override fun onLost(network: Network) {
            if (_wifiNetwork.value == network) {
                Log.w(TAG, "Wi-Fi network lost: $network")
                _wifiNetwork.value = null
                lastGatewayIp = null
            }
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Cellular network available: $network")
            _cellularAvailable.value = true
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Cellular network lost")
            _cellularAvailable.value = false
        }
    }

    fun startMonitoring() {
        try {
            // Monitor Wi-Fi
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)

            // Monitor Cellular
            val cellularRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityManager.registerNetworkCallback(cellularRequest, cellularCallback)

            // Initial probe
            connectivityManager.activeNetwork?.let { active ->
                val caps = connectivityManager.getNetworkCapabilities(active)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    _wifiNetwork.value = active
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callbacks", e)
        }
    }

    fun getWifiGatewayIp(): String? {
        val net = _wifiNetwork.value ?: return null
        val linkProps = connectivityManager.getLinkProperties(net) ?: return null
        return ipv4Gateway(linkProps)
    }

    private fun ipv4Gateway(linkProps: android.net.LinkProperties): String? =
        linkProps.routes.firstOrNull {
            it.isDefaultRoute && it.gateway is java.net.Inet4Address
        }?.gateway?.hostAddress

    fun getWifiSsid(): String? {
        val net = _wifiNetwork.value ?: return null
        val caps = connectivityManager.getNetworkCapabilities(net)
        val wifiInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            caps?.transportInfo as? android.net.wifi.WifiInfo
        } else null
        val ssid = wifiInfo?.ssid?.trim('"')
        if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
            return ssid
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val infoSsid = wifiManager?.connectionInfo?.ssid?.trim('"')
        return if (!infoSsid.isNullOrEmpty() && infoSsid != "<unknown ssid>") infoSsid else null
    }

    fun getWifiBssid(): String? {
        val net = _wifiNetwork.value ?: return null
        val caps = connectivityManager.getNetworkCapabilities(net)
        val wifiInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            caps?.transportInfo as? android.net.wifi.WifiInfo
        } else null
        val bssid = wifiInfo?.bssid
        if (!bssid.isNullOrEmpty() && bssid != "02:00:00:00:00:00") {
            return bssid
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        return wifiManager?.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(wifiCallback)
            connectivityManager.unregisterNetworkCallback(cellularCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callbacks", e)
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
