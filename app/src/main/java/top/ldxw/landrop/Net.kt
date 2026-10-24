package top.ldxw.landrop

import java.net.Inet4Address
import java.net.NetworkInterface

/** 取本机在局域网里的 IPv4 地址，优先 wlan / eth / ap 网卡。 */
object Net {
    fun localIpv4(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val name = ni.name.lowercase()
                val score = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.contains("softap") -> 2
                    name.startsWith("en") -> 3
                    else -> 9
                }
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("169.254.")) continue
                        candidates.add(score to ip)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return candidates.sortedBy { it.first }.firstOrNull()?.second
    }
}
