package com.echosphere.app.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the local IPv4 address of the active Wi-Fi or Hotspot interface (e.g. wlan0, ap0, etc.)
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priority order for interfaces
            val prioritizedInterfaces = interfaces.sortedByDescending { iface ->
                when {
                    iface.name.contains("wlan") -> 3
                    iface.name.contains("ap") -> 2
                    iface.name.contains("eth") -> 1
                    else -> 0
                }
            }

            for (iface in prioritizedInterfaces) {
                if (!iface.isUp || iface.isLoopback) continue

                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        // Ensure it's a standard private LAN IP
                        if (hostAddress != null && (
                                hostAddress.startsWith("192.168.") ||
                                hostAddress.startsWith("10.") ||
                                hostAddress.startsWith("172.")
                            )) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "192.168.43.1" // Fallback to standard Android Hotspot gateway
    }
}
