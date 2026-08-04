package com.example.sony_ftp.observer

import android.content.Context
import android.os.FileObserver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecursiveFileObserverTest {

    private lateinit var rootDir: File
    private var observer: RecursiveFileObserver? = null
    private val events = ConcurrentLinkedQueue<Pair<Int, String>>()

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        rootDir = File(ctx.cacheDir, "observer_test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun teardown() {
        observer?.stop()
    }

    private fun startObserver(latch: CountDownLatch, expectEvent: Int, expectName: String) {
        observer = RecursiveFileObserver(rootDir) { event, file ->
            events.add(event to file.name)
            if (event == expectEvent && file.name == expectName) latch.countDown()
        }.also { it.start() }
    }

    @Test
    fun closeWriteEventIsDelivered() {
        val latch = CountDownLatch(1)
        startObserver(latch, FileObserver.CLOSE_WRITE, "IMG001.JPG")

        File(rootDir, "IMG001.JPG").outputStream().use { it.write(ByteArray(2048)) }

        assertTrue(
            "expected CLOSE_WRITE, got: $events",
            latch.await(10, TimeUnit.SECONDS)
        )
    }

    @Test
    fun newSubdirectoryIsWatchedAutomatically() {
        val latch = CountDownLatch(1)
        startObserver(latch, FileObserver.CLOSE_WRITE, "IMG002.JPG")

        // 相机按日期新建目录后再上传
        val sub = File(rootDir, "2026-07-27").apply { mkdirs() }
        Thread.sleep(500) // 等待新目录 watcher 注册
        File(sub, "IMG002.JPG").outputStream().use { it.write(ByteArray(1024)) }

        assertTrue(
            "expected CLOSE_WRITE in subdir, got: $events",
            latch.await(10, TimeUnit.SECONDS)
        )
    }

    @Test
    fun deleteEventIsDelivered() {
        val f = File(rootDir, "DEL.JPG").apply { writeBytes(ByteArray(10)) }
        val latch = CountDownLatch(1)
        startObserver(latch, FileObserver.DELETE, "DEL.JPG")
        Thread.sleep(200)
        f.delete()
        assertTrue("expected DELETE, got: $events", latch.await(10, TimeUnit.SECONDS))
    }
}
