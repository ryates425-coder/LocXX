package com.locxx.langaming

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LanGamingLanIp {
    /** Non-loopback IPv4 addresses for typical Wi‑Fi / LAN interfaces. */
    fun listLanIpv4(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val h = addr.hostAddress
                        if (h != null) out.add(h)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    fun primaryLanHint(): String? = listLanIpv4().firstOrNull()
}
