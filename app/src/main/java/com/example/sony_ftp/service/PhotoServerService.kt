package com.example.sony_ftp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sony_ftp.MainActivity
import com.example.sony_ftp.PhotoServerApp
import com.example.sony_ftp.R
import com.example.sony_ftp.ftp.FtpServerManager
import com.example.sony_ftp.http.PhotoHttpServer
import com.example.sony_ftp.mdns.MdnsRegistrar
import com.example.sony_ftp.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：保证 FTP + HTTP 服务长时间后台运行。
 * 通知栏显示 "Photo Server Running / FTP: ON HTTP: ON Photos: xxxx"。
 */
@OptIn(FlowPreview::class)
class PhotoServerService : Service() {

    companion object {
        private const val TAG = "PhotoServerService"
        private const val CHANNEL_ID = "photo_server"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.sony_ftp.action.START"
        const val ACTION_STOP = "com.example.sony_ftp.action.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _serverIp = MutableStateFlow<String?>(null)
        val serverIp: StateFlow<String?> = _serverIp

        // 实际成功绑定的端口（端口自动回退后可能与用户设置不同，UI 用此展示真实地址）
        private val _httpPort = MutableStateFlow<Int?>(null)
        val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

        private val _ftpPort = MutableStateFlow<Int?>(null)
        val ftpPort: StateFlow<Int?> = _ftpPort.asStateFlow()

        /** 零配置访问主机名，浏览器直接访问 http://photoshare.local（mDNS 友好 + 显示清晰） */
        const val HOST_NAME = "photoshare.local"
        const val HTTP_DEFAULT_PORT = 80
        const val FTP_DEFAULT_PORT = 21

        /** 端口自动回退候选：优先特权端口，失败依次尝试 */
        private val HTTP_CANDIDATES = listOf(80, 8080, 18080)
        private val FTP_CANDIDATES = listOf(21, 2121)

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PhotoServerService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PhotoServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ftpManager: FtpServerManager? = null
    private var httpServer: PhotoHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var photoCount = 0

    private val app get() = application as PhotoServerApp

    /** mDNS / Bonjour 广播（懒加载，确保 applicationContext 已就绪） */
    private val mdns by lazy { MdnsRegistrar(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 仅当确实在运行时才处理；避免重复停止
                if (_running.value || ftpManager != null || httpServer != null) {
                    // 立即更新状态 + 移除通知，让用户瞬间感知“已停止”，
                    // 真正的服务器/ mDNS 拆除放到后台线程，避免阻塞主线程导致卡顿。
                    _running.value = false
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    scope.launch(Dispatchers.Default) {
                        try {
                            stopServers()
                        } finally {
                            stopSelf()
                        }
                    }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 已在运行（如僵尸/重复启动）时先彻底停止，避免端口被占用导致启动失败
                if (_running.value) stopServers()
                startServers()
            }
            else -> {
                // 系统 STICKY 重启（intent 为 null）：不自动拉起，等待用户显式启动
                Log.i(TAG, "onStartCommand: null/unknown action, no-op (running=${_running.value})")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 启停 ----------------

    private fun startServers() {
        if (_running.value) return

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()

        val ip = NetworkUtils.getBestIp()
        _serverIp.value = ip
        val config = app.serverConfig

        try {
            // 1. 照片索引仓库（MediaStore 启动对账）
            app.repository.start()

            // 2. FTP：优先 21，失败依次尝试 2121；上传先落盘到应用私有临时目录，
            //    再由仓库写入系统相册（MediaStore -> DCIM/PhotoShare），无需任何存储权限。
            val (ftpManager, actualFtp) = startWithFallback(
                candidates = ftpCandidates(config.ftpPort),
                label = "FTP"
            ) { port ->
                FtpServerManager(
                    uploadDir = app.uploadTempDir,
                    config = FtpServerManager.Config(
                        port = port,
                        username = config.ftpUsername,
                        password = config.ftpPassword,
                        passivePorts = config.passivePorts,
                        passiveExternalAddress = ip
                    ),
                    onUploadEnd = { file -> app.repository.addUploadedFile(file, file.name) }
                ).also { it.start() }
            }

            // 3. HTTP：优先 80，失败依次尝试 8080、18080
            val (httpServer, actualHttp) = startWithFallback(
                candidates = httpCandidates(config.httpPort),
                label = "HTTP"
            ) { port ->
                PhotoHttpServer(
                    port = port,
                    repository = app.repository,
                    assets = assets,
                    ftpPort = actualFtp ?: FTP_DEFAULT_PORT,
                    ipProvider = { NetworkUtils.getBestIp() },
                    context = applicationContext
                ).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            }

            if (ftpManager == null && httpServer == null) {
                throw IllegalStateException("FTP 与 HTTP 均无法绑定端口")
            }

            this.ftpManager = ftpManager
            this.httpServer = httpServer
            _ftpPort.value = actualFtp
            _httpPort.value = actualHttp

            // 持久化实际端口：下次启动直接复用，避免每次都尝试特权端口
            actualFtp?.let { app.serverConfig.ftpPort = it }
            actualHttp?.let { app.serverConfig.httpPort = it }

            _running.value = true
            Log.i(TAG, "servers started, ip=$ip http=$actualHttp ftp=$actualFtp")

            // 4. mDNS / Bonjour：广播 photo.share.local（客户端不支持时退化为 IP）
            if (ip != null) {
                val h = actualHttp ?: HTTP_DEFAULT_PORT
                val f = actualFtp ?: FTP_DEFAULT_PORT
                scope.launch(Dispatchers.IO) { mdns.register(ip, h, f) }
            }

            // 5. 热点 IP 变化时自动重新广播
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000)
                    if (!_running.value) break
                    val newIp = NetworkUtils.getBestIp() ?: continue
                    val h = _httpPort.value
                    val f = _ftpPort.value
                    if (h != null && f != null) mdns.reregisterIfIpChanged(newIp, h, f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start servers failed (ftp=${config.ftpPort}, http=${config.httpPort})", e)
            stopServers()
            stopSelf()
            return
        }

        // 照片数变化时更新通知（事件驱动 + 防抖）
        scope.launch {
            app.repository.counter.debounce(1000).collect { count ->
                photoCount = count
                if (_running.value) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    /**
     * 依次尝试候选端口，返回首个成功启动的实例与实际端口。
     * 任一候选绑定失败（如非 root 无法绑定特权端口）会被捕获并继续尝试下一个。
     */
    private inline fun <T> startWithFallback(
        candidates: List<Int>,
        label: String,
        crossinline factory: (Int) -> T
    ): Pair<T?, Int?> {
        for (port in candidates) {
            try {
                Log.i(TAG, "$label 尝试端口 $port")
                val instance = factory(port)
                Log.i(TAG, "$label 已在端口 $port 启动")
                @Suppress("UNCHECKED_CAST")
                return (instance to port) as Pair<T?, Int?>
            } catch (e: Exception) {
                Log.w(TAG, "$label 端口 $port 绑定失败：${e.message}")
            }
        }
        Log.e(TAG, "$label 所有候选端口均失败")
        return null to null
    }

    private fun httpCandidates(preferred: Int): List<Int> {
        val defaults = HTTP_CANDIDATES
        return if (defaults.contains(preferred)) defaults else listOf(preferred) + defaults
    }

    private fun ftpCandidates(preferred: Int): List<Int> {
        val defaults = FTP_CANDIDATES
        return if (defaults.contains(preferred)) defaults else listOf(preferred) + defaults
    }

    private fun stopServers() {
        if (!_running.value && ftpManager == null && httpServer == null) return
        _running.value = false
        runCatching { ftpManager?.stop() }
        runCatching { httpServer?.stop() }
        runCatching { app.repository.stop() }
        ftpManager = null
        httpServer = null
        _ftpPort.value = null
        _httpPort.value = null
        mdns.unregister()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "servers stopped")
    }

    // ---------------- 电源/网络锁 ----------------

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoServer:wake").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhotoServer:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Photo Share", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "照片服务器运行状态" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhotoServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = _serverIp.value ?: "?"
        val httpP = _httpPort.value ?: app.serverConfig.httpPort
        val ftpP = _ftpPort.value ?: app.serverConfig.ftpPort
        val httpSuffix = if (httpP == HTTP_DEFAULT_PORT) "" else ":$httpP"
        val ftpSuffix = if (ftpP == FTP_DEFAULT_PORT) "" else ":$ftpP"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle("Photo Share Running")
            .setContentText("FTP: ON  HTTP: ON  Photos: $photoCount")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "访问: http://${HOST_NAME}$httpSuffix\n" +
                        "FTP: ftp://${HOST_NAME}$ftpSuffix\n" +
                        "或 IP: http://$ip$httpSuffix\n" +
                        "Photos: $photoCount"
                )
            )
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
