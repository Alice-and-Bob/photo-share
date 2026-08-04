package com.example.sony_ftp.http

import com.example.sony_ftp.database.PhotoEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST API JSON 构造（可 JVM 单元测试，测试时依赖 org.json:json）。
 */
object PhotoJson {

    fun photoToJson(p: PhotoEntity): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.fileName)
        put("thumbnail", "/thumb/${encode(p.fileName)}")
        put("original", "/download/${encode(p.fileName)}")
        put("width", p.width)
        put("height", p.height)
        put("size", p.fileSize)
        put("createTime", p.createTime)
        put("exif", if (p.exifJson.isNullOrBlank()) JSONObject() else JSONObject(p.exifJson))
    }

    fun photoListJson(photos: List<PhotoEntity>): String {
        val arr = JSONArray()
        photos.forEach { arr.put(photoToJson(it)) }
        return arr.toString()
    }

    fun statusJson(count: Int, latestId: Long, ftpPort: Int, httpPort: Int, ip: String?): String =
        JSONObject().apply {
            put("photos", count)
            put("latestId", latestId)
            put("ftpPort", ftpPort)
            put("httpPort", httpPort)
            put("ip", ip ?: JSONObject.NULL)
        }.toString()

    /** 简单 URL path 编码（空格等） */
    fun encode(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** 防路径穿越：文件名不允许包含路径分隔符或 .. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() && !name.contains("..") && !name.contains('/') && !name.contains('\\')
}
