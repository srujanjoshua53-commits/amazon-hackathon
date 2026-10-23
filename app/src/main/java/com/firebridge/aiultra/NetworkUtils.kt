package com.firebridge.aiultra

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Retrieves the primary IPv4 address of this Fire OS / Android device.
     * Prioritizes active Wi-Fi and Ethernet interfaces, filtering out loopback addresses.
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            if (connectivityManager != null) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val linkProps: LinkProperties? = connectivityManager.getLinkProperties(activeNetwork)
                    linkProps?.linkAddresses?.forEach { linkAddress ->
                        val address = linkAddress.address
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: ""
                        }
                    }
                }
            }

            // Fallback: Iterate over all network interfaces
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Focus on wlan0, eth0, or standard network interfaces
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }

            // Fallback for older Wi-Fi manager retrieval
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "127.0.0.1"
    }

    /**
     * Builds the pairing URL for the mobile web controller.
     */
    fun getMobileRemoteUrl(ip: String, port: Int = 8080): String {
        return "http://$ip:$port/remote.html"
    }
}
