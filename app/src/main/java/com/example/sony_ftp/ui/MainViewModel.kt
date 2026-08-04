package com.example.sony_ftp.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sony_ftp.PhotoServerApp
import com.example.sony_ftp.service.PhotoServerService
import com.example.sony_ftp.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * MVVM：主界面 ViewModel。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<PhotoServerApp>()

    val serverRunning: StateFlow<Boolean> = PhotoServerService.running

    /** 照片计数器（独立持久化，支持「重置/清空时保留」语义） */
    val photoCount: StateFlow<Int> = app.repository.counter

    private val _ip = MutableStateFlow(NetworkUtils.getBestIp())
    val ip: StateFlow<String?> = _ip.asStateFlow()

    data class ConfigState(
        val ftpUser: String,
        val ftpPass: String,
        val ftpPort: Int,
        val httpPort: Int
    )

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    /** 实际成功绑定的端口（端口自动回退后可能与设置不同，UI 据此展示真实访问地址） */
    val httpPort: StateFlow<Int?> = PhotoServerService.httpPort
    val ftpPort: StateFlow<Int?> = PhotoServerService.ftpPort
    val hostName: String = PhotoServerService.HOST_NAME
    val httpDefaultPort: Int = PhotoServerService.HTTP_DEFAULT_PORT
    val ftpDefaultPort: Int = PhotoServerService.FTP_DEFAULT_PORT

    private fun loadConfig() = ConfigState(
        ftpUser = app.serverConfig.ftpUsername,
        ftpPass = app.serverConfig.ftpPassword,
        ftpPort = app.serverConfig.ftpPort,
        httpPort = app.serverConfig.httpPort
    )

    val photoDirPath: String get() = app.photoDir.absolutePath

    fun refreshIp() {
        viewModelScope.launch { _ip.value = NetworkUtils.getBestIp() }
    }

    fun startServer() {
        refreshIp()
        PhotoServerService.start(app)
    }

    fun stopServer() {
        PhotoServerService.stop(app)
    }

    /** 「重置计数器」：重新按磁盘实际文件数校正计数 */
    fun resyncCounter() {
        viewModelScope.launch { app.repository.resyncCounter() }
    }

    /**
     * 「清空所有照片」：删除存储目录下全部照片与缩略图、清空索引库。
     * @param clearCounter true=同时把计数器归零；false=保留计数器数值
     */
    fun clearPhotos(clearCounter: Boolean) {
        viewModelScope.launch { app.repository.clearAllPhotos(clearCounter) }
    }

    /**
     * 保存服务器配置。返回 null 表示成功；否则返回错误提示（供 UI 弹 Toast）。
     *
     * 端口范围 [1,65535]；<1024 的特权端口（80/21）不再拒绝——
     * 普通（非 root）设备无法绑定时会由服务自动回退到 8080/2121，无需用户干预。
     * FTP 与 HTTP 端口不可相同，否则后者绑定会冲突。
     */
    fun updateConfig(user: String, pass: String, ftpPort: Int, httpPort: Int): String? {
        val ftp = ftpPort.coerceIn(1, 65535)
        val http = httpPort.coerceIn(1, 65535)
        if (ftp == http) {
            Log.w("MainViewModel", "updateConfig rejected (equal): ftp=$ftp http=$http")
            return "FTP 与 HTTP 端口不能相同"
        }
        app.serverConfig.ftpUsername = user.ifBlank { "1111" }
        app.serverConfig.ftpPassword = pass.ifBlank { "1111" }
        app.serverConfig.ftpPort = ftp
        app.serverConfig.httpPort = http
        _config.value = loadConfig()
        return null
    }
}
