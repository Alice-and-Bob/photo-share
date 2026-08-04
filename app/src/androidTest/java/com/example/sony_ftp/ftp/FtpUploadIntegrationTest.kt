package com.example.sony_ftp.ftp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 集成测试：用原始 Socket 模拟相机的 FTP 客户端，
 * 验证认证、PASV 被动模式、STOR 上传与上传完成回调。
 */
@RunWith(AndroidJUnit4::class)
class FtpUploadIntegrationTest {

    private lateinit var uploadDir: File
    private lateinit var manager: FtpServerManager
    private var port = 0
    private val uploadedLatch = CountDownLatch(1)
    private var uploadedFile: File? = null

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        uploadDir = File(ctx.cacheDir, "ftp_test_upload").apply { deleteRecursively(); mkdirs() }
        port = ServerSocket(0).use { it.localPort } // 找一个空闲端口

        manager = FtpServerManager(
            uploadDir = uploadDir,
            config = FtpServerManager.Config(
                port = port,
                username = "camera",
                password = "secret",
                passivePorts = "51000-51050"
            ),
            onUploadEnd = { f ->
                uploadedFile = f
                uploadedLatch.countDown()
            }
        )
        manager.start()
    }

    @After
    fun teardown() {
        manager.stop()
    }

    private class FtpClient(host: String, port: Int) : AutoCloseable {
        private val socket = Socket(host, port)
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val writer = PrintWriter(socket.getOutputStream(), true)

        init { readReply() }

        fun cmd(command: String): String {
            writer.print(command + "\r\n")
            writer.flush()
            return readReply()
        }

        fun readReply(): String {
            val first = reader.readLine() ?: return ""
            if (first.length >= 4 && first[3] == '-') {
                // 多行应答
                val code = first.substring(0, 3)
                var line: String?
                do {
                    line = reader.readLine()
                } while (line != null && !(line.startsWith(code) && line.length > 3 && line[3] == ' '))
            }
            return first
        }

        override fun close() {
            runCatching { cmd("QUIT") }
            socket.close()
        }
    }

    private fun parsePasv(reply: String): Pair<String, Int> {
        val nums = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
            .find(reply)!!.groupValues.drop(1).map { it.toInt() }
        return "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}" to (nums[4] * 256 + nums[5])
    }

    @Test
    fun wrongPasswordIsRejected() {
        FtpClient("127.0.0.1", port).use { c ->
            c.cmd("USER camera")
            val reply = c.cmd("PASS wrongpass")
            assertTrue("expected 530, got $reply", reply.startsWith("530"))
        }
    }

    @Test
    fun passiveModeUploadTriggersCallback() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() } // 256KB 模拟照片

        FtpClient("127.0.0.1", port).use { c ->
            assertTrue(c.cmd("USER camera").startsWith("331"))
            assertTrue(c.cmd("PASS secret").startsWith("230"))
            assertTrue(c.cmd("TYPE I").startsWith("200"))

            val pasvReply = c.cmd("PASV")
            assertTrue("expected 227, got $pasvReply", pasvReply.startsWith("227"))
            val (host, dataPort) = parsePasv(pasvReply)

            val storReplyFirst: String
            Socket(host, dataPort).use { dataSocket ->
                storReplyFirst = c.cmd("STOR IMG001.JPG")
                assertTrue("expected 150, got $storReplyFirst", storReplyFirst.startsWith("150"))
                dataSocket.getOutputStream().use { it.write(payload) }
            }
            assertTrue(c.readReply().startsWith("226")) // 传输完成
        }

        assertTrue(
            "upload callback not fired",
            uploadedLatch.await(15, TimeUnit.SECONDS)
        )
        assertEquals("IMG001.JPG", uploadedFile!!.name)

        val onDisk = File(uploadDir, "IMG001.JPG")
        assertTrue(onDisk.exists())
        assertEquals(payload.size.toLong(), onDisk.length())
        assertTrue(onDisk.readBytes().contentEquals(payload))
    }

    @Test
    fun multipleSequentialUploads() {
        FtpClient("127.0.0.1", port).use { c ->
            c.cmd("USER camera"); c.cmd("PASS secret"); c.cmd("TYPE I")
            for (i in 1..3) {
                val (host, dataPort) = parsePasv(c.cmd("PASV"))
                Socket(host, dataPort).use { ds ->
                    assertTrue(c.cmd("STOR IMG00$i.JPG").startsWith("150"))
                    ds.getOutputStream().use { it.write(ByteArray(1024 * i)) }
                }
                assertTrue(c.readReply().startsWith("226"))
            }
        }
        val files = uploadDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("IMG001.JPG", "IMG002.JPG", "IMG003.JPG"), files)
    }
}
