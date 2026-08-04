package com.example.sony_ftp.http

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sony_ftp.database.PhotoDatabase
import com.example.sony_ftp.repository.PhotoRepository
import com.example.sony_ftp.thumbnail.ThumbnailGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 集成测试：文件落盘 -> 索引 -> 网页访问 / API / 缩略图 / Range 下载 全链路。
 */
@RunWith(AndroidJUnit4::class)
class HttpServerIntegrationTest {

    private lateinit var ctx: Context
    private lateinit var db: PhotoDatabase
    private lateinit var repository: PhotoRepository
    private lateinit var server: PhotoHttpServer
    private lateinit var photoDir: File
    private var port = 0

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        photoDir = File(ctx.cacheDir, "http_test_photos").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, PhotoDatabase::class.java).build()
        repository = PhotoRepository(
            photoDir, db.photoDao(),
            ThumbnailGenerator(File(ctx.cacheDir, "http_test_thumbs").apply { deleteRecursively() })
        )
        repository.start()

        server = PhotoHttpServer(0, repository, ctx.assets, 2121) { "127.0.0.1" }
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        port = server.listeningPort
    }

    @After
    fun teardown() {
        server.stop()
        repository.stop()
        db.close()
    }

    private fun uploadTestPhoto(name: String): File {
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.CYAN)
        val f = File(photoDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        // 模拟 FTP 上传完成回调
        repository.notifyUploadFinished(f)
        return f
    }

    private fun awaitIndexed(expected: Int, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = runBlocking { repository.count() }
            if (n >= expected) return
            Thread.sleep(200)
        }
        throw AssertionError("timeout waiting for $expected photos to be indexed")
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connect()
        return conn
    }

    @Test
    fun indexPageIsServed() {
        val conn = get("/")
        assertEquals(200, conn.responseCode)
        val html = conn.inputStream.bufferedReader().readText()
        assertTrue(html.contains("Photo Server"))
    }

    @Test
    fun apiPhotosReturnsUploadedPhoto() {
        uploadTestPhoto("IMG100.JPG")
        awaitIndexed(1)

        val conn = get("/api/photos")
        assertEquals(200, conn.responseCode)
        val arr = JSONArray(conn.inputStream.bufferedReader().readText())
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("IMG100.JPG", obj.getString("name"))
        assertEquals("/thumb/IMG100.JPG", obj.getString("thumbnail"))
        assertEquals("/download/IMG100.JPG", obj.getString("original"))
        assertTrue(obj.has("exif"))
    }

    @Test
    fun thumbnailIsServed() {
        uploadTestPhoto("IMG101.JPG")
        awaitIndexed(1)
        // 等待缩略图生成完成
        Thread.sleep(1500)

        val conn = get("/thumb/IMG101.JPG")
        assertEquals(200, conn.responseCode)
        assertEquals("image/jpeg", conn.contentType)
        assertTrue(conn.inputStream.readBytes().isNotEmpty())
    }

    @Test
    fun downloadFullFile() {
        val f = uploadTestPhoto("IMG102.JPG")
        awaitIndexed(1)

        val conn = get("/download/IMG102.JPG")
        assertEquals(200, conn.responseCode)
        assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
        assertEquals(f.length(), conn.inputStream.readBytes().size.toLong())
    }

    @Test
    fun downloadWithRangeReturnsPartialContent() {
        val f = uploadTestPhoto("IMG103.JPG")
        awaitIndexed(1)

        val conn = get("/download/IMG103.JPG", mapOf("Range" to "bytes=0-99"))
        assertEquals(206, conn.responseCode)
        assertEquals("bytes 0-99/${f.length()}", conn.getHeaderField("Content-Range"))
        assertEquals(100, conn.inputStream.readBytes().size)

        // 断点续传：从第 100 字节继续
        val conn2 = get("/download/IMG103.JPG", mapOf("Range" to "bytes=100-"))
        assertEquals(206, conn2.responseCode)
        assertEquals(f.length() - 100, conn2.inputStream.readBytes().size.toLong())
    }

    @Test
    fun invalidRangeReturns416() {
        uploadTestPhoto("IMG104.JPG")
        awaitIndexed(1)
        val conn = get("/download/IMG104.JPG", mapOf("Range" to "bytes=99999999-"))
        assertEquals(416, conn.responseCode)
    }

    @Test
    fun pathTraversalIsBlocked() {
        val conn = get("/download/..%2F..%2Fsecret.txt")
        assertEquals(404, conn.responseCode)
    }

    @Test
    fun unknownPhotoReturns404() {
        assertEquals(404, get("/thumb/NOPE.JPG").responseCode)
        assertEquals(404, get("/download/NOPE.JPG").responseCode)
    }
}
