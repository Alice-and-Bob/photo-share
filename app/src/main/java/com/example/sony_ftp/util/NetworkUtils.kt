package com.example.sony_ftp.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络发现：自动获取手机热点/局域网 IPv4 地址。
 * 热点接口通常为 ap0 / wlan1 / swlan0，IP 常见为 192.168.43.x 或 192.168.x.x。
 */
object NetworkUtils {

    private val HOTSPOT_INTERFACE_HINTS = listOf("ap", "swlan", "wlan", "eth")

    data class AddressInfo(val interfaceName: String, val ip: String)

    fun getAllIpv4(): List<AddressInfo> {
        val result = mutableListOf<AddressInfo>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(AddressInfo(nif.name, addr.hostAddress ?: return@forEach))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * 优先返回热点地址：
     * 1. 接口名以 ap/swlan 开头（热点专用接口）
     * 2. 192.168.43.x（Android 传统热点网段）
     * 3. 任意 wlan 接口地址
     * 4. 其他任意私有地址
     */
    fun getBestIp(): String? {
        val all = getAllIpv4()
        if (all.isEmpty()) return null

        all.firstOrNull { it.interfaceName.startsWith("ap") || it.interfaceName.startsWith("swlan") }
            ?.let { return it.ip }
        all.firstOrNull { it.ip.startsWith("192.168.43.") }?.let { return it.ip }
        for (hint in HOTSPOT_INTERFACE_HINTS) {
            all.firstOrNull { it.interfaceName.startsWith(hint) }?.let { return it.ip }
        }
        return all.first().ip
    }
}
