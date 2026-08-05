package com.example.sony_ftp.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 缩略图生成器：Original Image -> Thumbnail Generator -> Thumbnail Cache。
 * 浏览器照片墙只加载缩略图，禁止直接加载原图。
 * 使用 BitmapFactory + inSampleSize 采样解码，控制内存与 CPU 占用。
 *
 * 生成不再依赖「文件系统路径」，而是基于一个稳定 key（通常是 MediaStore contentUri 字符串）
 * 打开输入流解码，这样无论是 FTP 上传的临时文件还是系统相册里的图片，都能复用同一套逻辑。
 */
class ThumbnailGenerator(private val cacheDir: File) {

    companion object {
        const val MAX_DIMENSION = 512
        const val JPEG_QUALITY = 82

        fun thumbFileNameFor(key: String): String = md5(key) + ".jpg"

        fun md5(input: String): String =
            MessageDigest.getInstance("MD5")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** 计算 BitmapFactory 采样率（2 的幂） */
        fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
            var sampleSize = 1
            var w = width
            var h = height
            while (w / 2 >= maxDim || h / 2 >= maxDim) {
                w /= 2
                h /= 2
                sampleSize *= 2
            }
            return sampleSize
        }
    }

    init {
        cacheDir.mkdirs()
    }

    /**
     * 生成缩略图（若缓存存在直接复用）。
     * @param key 稳定 key（如 MediaStore uri 字符串），用于缓存文件名与去重
     * @param openStream 打开原图输入流的回调（临时文件或 contentResolver）
     * @return 缩略图文件，null 表示解码失败（不是有效图片）
     */
    fun generate(key: String, openStream: () -> InputStream?): File? {
        if (key.isBlank()) return null
        val thumbFile = File(cacheDir, thumbFileNameFor(key))
        if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile

        val stream = openStream() ?: return null
        val bytes = stream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        // 1. 只解码边界，拿到原图尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // 2. 采样解码
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcW, srcH, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        // 3. 根据 EXIF 方向旋转
        try {
            val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
        } catch (_: Exception) {
            // EXIF 读取失败不影响缩略图
        }

        // 4. 写入缓存（先写临时文件再重命名，避免半成品被读取）
        val tmp = File(cacheDir, thumbFile.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (thumbFile.exists()) thumbFile.delete()
            if (!tmp.renameTo(thumbFile)) {
                tmp.copyTo(thumbFile, overwrite = true)
                tmp.delete()
            }
            thumbFile
        } catch (e: Exception) {
            tmp.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun deleteThumbFor(key: String) {
        File(cacheDir, thumbFileNameFor(key)).delete()
    }

    /** 清空缩略图缓存（一键清空存储目录时调用） */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
