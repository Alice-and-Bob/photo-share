package com.example.sony_ftp.ftp

import android.util.Log
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import java.io.File

/**
 * 基于 Apache FtpServer（成熟实现，未自行实现 FTP 协议）。
 * 支持：用户认证、指定上传目录、PASV 被动模式、多文件连续上传、大文件上传。
 */
class FtpServerManager(
    private val uploadDir: File,
    private val config: Config,
    private val onUploadStart: (File) -> Unit = {},
    private val onUploadEnd: (File) -> Unit
) {
    data class Config(
        val port: Int = 2121,
        val username: String = "camera",
        val password: String = "camera",
        val passivePorts: String = "50000-50100",
        /** 热点 IP，作为 PASV 应答地址；null 则自动 */
        val passiveExternalAddress: String? = null
    )

    companion object {
        private const val TAG = "FtpServerManager"
    }

    private var server: FtpServer? = null

    val isRunning: Boolean get() = server?.let { !it.isStopped } == true

    @Synchronized
    fun start() {
        if (isRunning) return
        uploadDir.mkdirs()

        val serverFactory = FtpServerFactory()

        // 连接配置：允许多客户端并发（相机 + 备用连接）
        // 启用匿名登录：相机可无需用户名/密码直接上传（仍保留配置的用户名/密码作为备选）。
        serverFactory.connectionConfig = ConnectionConfigFactory().apply {
            maxLogins = 10
            isAnonymousLoginEnabled = true
            maxLoginFailures = 5
            loginFailureDelay = 1000
        }.createConnectionConfig()

        // 监听器：控制端口 + PASV 被动模式端口段
        val listenerFactory = ListenerFactory().apply {
            port = config.port
            idleTimeout = 300
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passivePorts = config.passivePorts
                config.passiveExternalAddress?.let { passiveExternalAddress = it }
                isActiveEnabled = true
                idleTime = 300
            }.createDataConnectionConfiguration()
        }
        serverFactory.addListener("default", listenerFactory.createListener())

        // 用户认证，主目录 = 上传目录；allowAnonymous 允许相机无凭据上传
        serverFactory.userManager = InMemoryUserManager(
            username = config.username,
            password = config.password,
            homeDir = uploadDir,
            allowAnonymous = true
        )

        // 上传生命周期回调
        serverFactory.ftplets = mapOf(
            "uploadFtplet" to UploadFtplet(uploadDir, onUploadStart, onUploadEnd)
        )

        server = serverFactory.createServer().also { it.start() }
        Log.i(TAG, "FTP server started on port ${config.port}, dir=$uploadDir")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
            .onFailure { Log.w(TAG, "stop ftp failed", it) }
        server = null
        Log.i(TAG, "FTP server stopped")
    }
}
