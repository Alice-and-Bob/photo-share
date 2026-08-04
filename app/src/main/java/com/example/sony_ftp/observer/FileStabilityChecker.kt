package com.example.sony_ftp.observer

import kotlinx.coroutines.delay
import java.io.File

/**
 * 上传安全机制：文件必须"大小稳定 + 修改时间稳定"才视为上传完成，
 * 避免把正在上传中的半张图片加入图库。
 * .part / .tmp 等临时文件直接排除。
 */
object FileStabilityChecker {

    val TEMP_SUFFIXES = listOf(".part", ".tmp", ".temp", ".uploading", ".filepart")

    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp",
        "arw", "cr2", "cr3", "nef", "raf", "dng", "orf", "rw2"
    )

    fun isTempFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith(".") || TEMP_SUFFIXES.any { lower.endsWith(it) }
    }

    fun isImageFile(name: String): Boolean {
        if (isTempFile(name)) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /** 纯逻辑判断：连续两次采样的大小与修改时间一致即稳定（可单元测试） */
    fun isStable(size1: Long, mtime1: Long, size2: Long, mtime2: Long): Boolean =
        size1 > 0 && size1 == size2 && mtime1 == mtime2

    /**
     * 等待文件稳定。
     * @param checkIntervalMs 采样间隔
     * @param maxWaitMs 最长等待，超时返回 false
     */
    suspend fun awaitStable(
        file: File,
        checkIntervalMs: Long = 700,
        maxWaitMs: Long = 60_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastSize = file.length()
        var lastMtime = file.lastModified()
        while (System.currentTimeMillis() < deadline) {
            delay(checkIntervalMs)
            if (!file.exists()) return false
            val size = file.length()
            val mtime = file.lastModified()
            if (isStable(lastSize, lastMtime, size, mtime)) return true
            lastSize = size
            lastMtime = mtime
        }
        return false
    }
}
