package com.example.sony_ftp.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 服务器配置（SharedPreferences 持久化）。
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("server_config", Context.MODE_PRIVATE)

    var ftpUsername: String
        get() = prefs.getString("ftp_user", "1111") ?: "1111"
        set(v) = prefs.edit().putString("ftp_user", v).apply()

    var ftpPassword: String
        get() = prefs.getString("ftp_pass", "1111") ?: "1111"
        set(v) = prefs.edit().putString("ftp_pass", v).apply()

    /**
     * 持久化的照片计数器（独立于数据库，便于「清空照片但保留计数」）。
     * -1 表示尚未初始化，由仓库在启动时按磁盘实际文件数校正。
     */
    var photoCounter: Int
        get() = prefs.getInt("photo_counter", -1)
        set(v) = prefs.edit().putInt("photo_counter", if (v < 0) 0 else v).apply()

    var ftpPort: Int
        get() = prefs.getInt("ftp_port", 2121)
        set(v) = prefs.edit().putInt("ftp_port", v).apply()

    var httpPort: Int
        get() = prefs.getInt("http_port", 8080)
        set(v) = prefs.edit().putInt("http_port", v).apply()

    val passivePorts: String
        get() = prefs.getString("pasv_ports", "50000-50100") ?: "50000-50100"
}
