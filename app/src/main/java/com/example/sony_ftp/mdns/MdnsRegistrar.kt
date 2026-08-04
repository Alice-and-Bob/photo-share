package com.example.sony_ftp.mdns

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

/**
 * 基于 JmDNS 的局域网服务发现（Bonjour / mDNS）。
 *
 * 广播后，同局域网内浏览器/客户端可直接用 http://photoshare.local 访问，
 * 无需配置 IP、端口、DNS、云服务器或互联网。
 *
 * - 注册主机名 photoshare（底层 A 记录发布为 photoshare.local，指向本机热点 IP）
 * - 仅注册 _http._tcp 服务（浏览器零配置访问）。
 *   相机 FTP 上传不再经由 mDNS 域名发现，仅通过界面给出的直接 IP 地址上传。
 * - 若客户端不支持 mDNS，仍可退回 http://<热点IP> 访问（由调用方在 UI 中给出）
 * - 热点 IP 变化后调用 [reregisterIfIpChanged] 自动重新广播
 */
class MdnsRegistrar(context: Context) {

    private val appContext = context.applicationContext
    private val TAG = "MdnsRegistrar"

    @Volatile private var jmdns: JmDNS? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var registeredIp: String? = null

    @SuppressLint("WifiManagerPotentialLeak")
    @Synchronized
    fun register(ip: String, httpPort: Int, ftpPort: Int) {
        if (ip.isBlank()) {
            Log.w(TAG, "register skipped: empty ip")
            return
        }
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("PhotoServerMdns")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "acquire multicast lock failed (non-fatal)", e)
        }

        try {
            unregisterInternal()
            val addr = InetAddress.getByName(ip)
            // 主机名设为 photoshare.local -> 发布 photoshare.local 的 A 记录，
            // 服务注册会自动携带该主机名，浏览器做 A 查询即可解析到本机 IP
            jmdns = JmDNS.create(addr, "photoshare.local")

            val httpInfo = ServiceInfo.create(
                "_http._tcp.local.", "PhotoShare", httpPort, "Photo Share"
            )
            jmdns?.registerService(httpInfo)

            // 仅广播 HTTP 服务；不再广播 _ftp._tcp，取消相机通过 mDNS 域名（photoshare.local）发现并上传 FTP 的能力。
            registeredIp = ip
            Log.i(TAG, "mDNS registered photoshare.local -> $ip (http:$httpPort, ftp disabled)")
        } catch (e: Exception) {
            Log.e(TAG, "mDNS register failed", e)
        }
    }

    /** IP 变化时才重新广播，避免无谓的抖动 */
    @Synchronized
    fun reregisterIfIpChanged(ip: String, httpPort: Int, ftpPort: Int) {
        if (ip != registeredIp) {
            Log.i(TAG, "IP changed $registeredIp -> $ip, re-registering mDNS")
            register(ip, httpPort, ftpPort)
        }
    }

    @Synchronized
    fun unregister() {
        unregisterInternal()
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        Log.i(TAG, "mDNS unregistered")
    }

    private fun unregisterInternal() {
        val j = jmdns
        jmdns = null
        if (j == null) return
        // JmDNS.close() 可能阻塞数秒（发送 goodbye 包 / 退出组播组），
        // 放到独立线程并加超时，避免拖慢停止流程、冻结 UI。
        val closeThread = Thread({
            try { j.unregisterAllServices() } catch (_: Exception) { }
            try { j.close() } catch (_: Exception) { }
        }, "mdns-close")
        closeThread.isDaemon = true
        closeThread.start()
        try { closeThread.join(1500) } catch (_: InterruptedException) { }
    }
}
