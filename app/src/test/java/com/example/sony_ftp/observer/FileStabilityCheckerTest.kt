package com.example.sony_ftp.observer

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStabilityCheckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `temp and hidden files are detected`() {
        assertTrue(FileStabilityChecker.isTempFile("IMG001.JPG.part"))
        assertTrue(FileStabilityChecker.isTempFile("upload.tmp"))
        assertTrue(FileStabilityChecker.isTempFile(".hidden"))
        assertFalse(FileStabilityChecker.isTempFile("IMG001.JPG"))
    }

    @Test
    fun `image extension detection`() {
        assertTrue(FileStabilityChecker.isImageFile("IMG001.JPG"))
        assertTrue(FileStabilityChecker.isImageFile("photo.jpeg"))
        assertTrue(FileStabilityChecker.isImageFile("raw.ARW"))
        assertFalse(FileStabilityChecker.isImageFile("IMG001.JPG.part"))
        assertFalse(FileStabilityChecker.isImageFile("notes.txt"))
        assertFalse(FileStabilityChecker.isImageFile("noext"))
    }

    @Test
    fun `stability logic pure check`() {
        assertTrue(FileStabilityChecker.isStable(100, 5, 100, 5))
        assertFalse(FileStabilityChecker.isStable(100, 5, 200, 5))   // 大小变化
        assertFalse(FileStabilityChecker.isStable(100, 5, 100, 9))   // 修改时间变化
        assertFalse(FileStabilityChecker.isStable(0, 5, 0, 5))       // 空文件不算稳定
    }

    @Test
    fun `stable file passes awaitStable`() = runTest {
        val f = tmp.newFile("stable.jpg")
        f.writeBytes(ByteArray(1024) { 1 })
        val stable = FileStabilityChecker.awaitStable(f, checkIntervalMs = 50, maxWaitMs = 3000)
        assertTrue(stable)
    }

    @Test
    fun `growing file eventually stabilizes`() = runTest {
        val f = tmp.newFile("growing.jpg")
        f.writeBytes(ByteArray(10))

        val writer = launch {
            // 模拟持续上传后停止
            repeat(3) {
                Thread.sleep(30)
                f.appendBytes(ByteArray(10))
            }
        }
        val stable = FileStabilityChecker.awaitStable(f, checkIntervalMs = 100, maxWaitMs = 10_000)
        writer.join()
        assertTrue(stable)
    }

    @Test
    fun `deleted file returns false`() = runTest {
        val f = tmp.newFile("gone.jpg")
        f.writeBytes(ByteArray(10))
        f.delete()
        assertFalse(FileStabilityChecker.awaitStable(f, checkIntervalMs = 50, maxWaitMs = 500))
    }
}
