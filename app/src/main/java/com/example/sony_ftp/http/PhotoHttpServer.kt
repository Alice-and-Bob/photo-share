package com.example.sony_ftp.http

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.sony_ftp.database.PhotoEntity
import com.example.sony_ftp.repository.PhotoRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * 基于 NanoHTTPD 的图库 HTTP 服务。
 *
 * 路由：
 *   GET /                   照片墙网页（assets/web）
 *   GET /api/photos         照片列表 JSON（分页 ?page=&size=）
 *   GET /api/status         服务状态（照片总数、latestId，用于网页增量刷新）
 *   GET /thumb/{filename}   缩略图（应用私有缓存）
 *   GET /download/{filename} 原图下载（读取 MediaStore contentUri），支持 HTTP Range 断点续传
 */
class PhotoHttpServer(
    port: Int,
    private val repository: PhotoRepository,
    private val assets: AssetManager,
    private val ftpPort: Int,
    private val ipProvider: () -> String?,
    private val context: Context
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "PhotoHttpServer"
        private const val MIME_JSON = "application/json"

        private val STATIC_MIME = mapOf(
            "html" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "png" to "image/png"
        )

        fun mimeForImage(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed")
        }
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html")
                uri == "/style.css" -> serveAsset("web/style.css")
                uri == "/app.js" -> serveAsset("web/app.js")
                uri == "/api/photos" -> servePhotoList(session)
                uri == "/api/status" -> serveStatus()
                uri.startsWith("/thumb/") -> serveThumb(decodeName(uri.removePrefix("/thumb/")))
                uri.startsWith("/download/") -> serveDownload(session, decodeName(uri.removePrefix("/download/")))
                else -> notFound()
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error: $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
        }
    }

    private fun decodeName(raw: String): String =
        URLDecoder.decode(raw.substringBefore('?'), "UTF-8")

    // ---------------- API ----------------

    private fun servePhotoList(session: IHTTPSession): Response {
        val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 0
        val size = session.parameters["size"]?.firstOrNull()?.toIntOrNull() ?: 100
        val photos = runBlocking { repository.getPage(page, size) }
        return jsonResponse(PhotoJson.photoListJson(photos))
    }

    private fun serveStatus(): Response {
        val (count, latest) = runBlocking { repository.count() to repository.latestId() }
        return jsonResponse(
            PhotoJson.statusJson(count, latest, ftpPort, listeningPort, ipProvider())
        )
    }

    // ---------------- 缩略图 ----------------

    private fun serveThumb(name: String): Response {
        if (!PhotoJson.isSafeFileName(name)) return notFound()
        val photo = findPhoto(name) ?: return notFound()
        val thumbPath = photo.thumbnailPath
        if (photo.thumbnailStatus == PhotoEntity.THUMB_READY && thumbPath != null) {
            val f = File(thumbPath)
            if (f.exists()) {
                return fileResponse(f, "image/jpeg").apply {
                    addHeader("Cache-Control", "public, max-age=86400")
                }
            }
        }
        // 缩略图未就绪时降级：返回 404 由前端显示占位图（禁止回退到原图，防止流量爆炸）
        return notFound()
    }

    // ---------------- 原图下载（读取 MediaStore，支持 Range 断点） ----------------

    private fun serveDownload(session: IHTTPSession, name: String): Response {
        if (!PhotoJson.isSafeFileName(name)) return notFound()
        val photo = findPhoto(name) ?: return notFound()
        val uri = Uri.parse(photo.contentUri)
        val fileLen = mediaSize(uri) ?: return notFound()
        if (fileLen <= 0) return notFound()

        val mime = mimeForImage(photo.fileName)
        val input = context.contentResolver.openInputStream(uri) ?: return notFound()
        val rangeHeader = session.headers["range"]

        // 显式请求了 Range 但范围非法 -> 416
        if (rangeHeader != null) {
            val range = RangeParser.parse(rangeHeader, fileLen)
            if (range == null) {
                if (rangeHeader.startsWith("bytes=")) {
                    val resp = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, ""
                    )
                    resp.addHeader("Content-Range", "bytes */$fileLen")
                    return resp
                }
            } else {
                input.skipFully(range.start)
                val resp = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime,
                    LimitedInputStream(input, range.length), range.length
                )
                resp.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$fileLen")
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Content-Disposition", "inline; filename=\"${photo.fileName}\"")
                return resp
            }
        }

        return newFixedLengthResponse(Response.Status.OK, mime, input, fileLen).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Disposition", "inline; filename=\"${photo.fileName}\"")
        }
    }

    /** 通过 MediaStore 查询图片字节数 */
    private fun mediaSize(uri: Uri): Long? {
        val c = context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null
        )
        c?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun findPhoto(name: String): PhotoEntity? = runBlocking {
        name.toLongOrNull()?.let { repository.findById(it) } ?: repository.findByName(name)
    }

    // ---------------- 工具 ----------------

    private fun fileResponse(file: File, mime: String): Response {
        val resp = newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(file), file.length()
        )
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun jsonResponse(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun serveAsset(path: String): Response {
        return try {
            val bytes = assets.open(path).use(InputStream::readBytes)
            val ext = path.substringAfterLast('.', "")
            newFixedLengthResponse(
                Response.Status.OK,
                STATIC_MIME[ext] ?: "application/octet-stream",
                bytes.inputStream(),
                bytes.size.toLong()
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    /** 限长输入流：用于 Range 响应只输出片段 */
    private class LimitedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(delegate.available().toLong(), remaining).toInt()

        override fun close() = delegate.close()
    }
}
