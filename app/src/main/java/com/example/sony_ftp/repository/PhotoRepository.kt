package com.example.sony_ftp.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.sony_ftp.database.PhotoDao
import com.example.sony_ftp.database.PhotoEntity
import com.example.sony_ftp.exif.ExifParser
import com.example.sony_ftp.thumbnail.ThumbnailGenerator
import com.example.sony_ftp.util.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 照片仓库：通过 MediaStore 将图片写入系统相册 DCIM/<APP_NAME>/，
 * 无需「所有文件访问权限」，也不跳转系统设置页（Scoped Storage 规范）。
 *
 * 流程：
 *   FTP 上传 -> 落盘到应用私有临时目录 -> 复制进 MediaStore（DCIM/PhotoShare）
 *            -> 生成缩略图 -> 索引入库 -> 计数器 +1。
 *
 * 计数器与图库索引均基于 MediaStore 中本应用专属文件夹的真实文件数，保证一致；
 * 启动对账、清空、重置都直接作用于 MediaStore，不再监听目录。
 */
class PhotoRepository(
    private val appContext: Context,
    private val uploadTempDir: File,
    private val galleryRelativePath: String,
    private val dao: PhotoDao,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val serverConfig: ServerConfig
) {
    companion object {
        private const val TAG = "PhotoRepository"
        private const val MIME_JPEG = "image/jpeg"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counterLock = Mutex()

    /**
     * 照片计数器（独立于数据库，便于「清空照片但保留计数」语义）。
     * 启动时按系统相册真实文件数校正。
     */
    private val _counter = MutableStateFlow(serverConfig.photoCounter.coerceAtLeast(0))
    val counter: StateFlow<Int> get() = _counter.asStateFlow()

    private fun persistCounter() {
        serverConfig.photoCounter = _counter.value
    }

    private suspend fun bumpCounter(delta: Int) {
        counterLock.withLock {
            _counter.value = (_counter.value + delta).coerceAtLeast(0)
            persistCounter()
        }
    }

    fun start() {
        uploadTempDir.mkdirs()
        scope.launch { startupSync() }
    }

    fun stop() {
        persistCounter()
        scope.cancel()
    }

    /**
     * FTP 上传完成回调：tempFile 为写入应用私有临时目录的文件。
     * 这里把它登记进系统相册（MediaStore），随后删除临时文件。
     */
    fun addUploadedFile(tempFile: File, displayName: String) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.w(TAG, "addUploadedFile: temp missing $tempFile")
            return
        }
        scope.launch {
            val exif = runCatching { ExifParser.parse(tempFile) }.getOrNull()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
            val width = maxOf(bounds.outWidth, 0)
            val height = maxOf(bounds.outHeight, 0)
            val fileSize = tempFile.length()

            // 1. 写入 MediaStore（DCIM/PhotoShare），返回 contentUri
            val uri = saveToMediaStore(displayName, MIME_JPEG, galleryRelativePath, tempFile)
            if (uri == null) {
                Log.e(TAG, "saveToMediaStore failed for $displayName (left in temp: $tempFile)")
                return@launch
            }
            // 2. 生成缩略图（key 用 uri，删除临时文件后仍可定位缓存）
            val thumb = thumbnailGenerator.generate(uri.toString()) { tempFile.inputStream() }
            // 3. 删除临时文件
            runCatching { tempFile.delete() }
            // 4. 入库 + 计数
            val entity = PhotoEntity(
                id = 0,
                fileName = displayName,
                contentUri = uri.toString(),
                createTime = exif?.dateTimeMillis ?: System.currentTimeMillis(),
                modifyTime = System.currentTimeMillis(),
                width = width,
                height = height,
                fileSize = fileSize,
                thumbnailPath = thumb?.absolutePath,
                thumbnailStatus = if (thumb != null) PhotoEntity.THUMB_READY else PhotoEntity.THUMB_FAILED,
                exifJson = exif?.toJson(),
                uploadComplete = true
            )
            dao.upsert(entity)
            bumpCounter(1)
            Log.i(TAG, "added to gallery: $displayName -> $uri")
        }
    }

    /**
     * 「重置计数器」：重新按系统相册中本应用专属文件夹的真实文件数校正计数。
     */
    suspend fun resyncCounter(): Int {
        reconcileCounter()
        return _counter.value
    }

    /**
     * 「清空所有照片」：删除 DCIM/PhotoShare 下全部照片（MediaStore）+ 索引库 + 缩略图。
     * @param clearCounter true=同步将计数器归零；false=保留当前计数器数值（即使照片已删）
     */
    suspend fun clearAllPhotos(clearCounter: Boolean) {
        val rows = queryGalleryUris()
        val resolver = appContext.contentResolver
        rows.forEach { runCatching { resolver.delete(it.uri, null, null) } }
        dao.deleteAll()
        thumbnailGenerator.clearAll()
        // 清理可能残留的临时文件
        uploadTempDir.listFiles()?.forEach { runCatching { it.delete() } }
        if (clearCounter) {
            counterLock.withLock {
                _counter.value = 0
                persistCounter()
            }
        } else {
            persistCounter()
        }
        Log.i(TAG, "clearAllPhotos done (clearCounter=$clearCounter, removed=${rows.size})")
    }

    // ---------------- MediaStore 读写 ----------------

    private fun galleryCollectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /**
     * 通过 MediaStore API 把source写入 DCIM/<APP_NAME>/（RELATIVE_PATH）。
     * 过程：insert 创建记录(IS_PENDING=1) -> openOutputStream 写入字节 -> IS_PENDING=0 完成登记。
     * 这样图片立即出现在系统相册，且无需任何存储权限、不跳转设置页。
     */
    private fun saveToMediaStore(
        displayName: String,
        mimeType: String,
        relativePath: String,
        source: File
    ): Uri? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(galleryCollectionUri(), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToMediaStore error", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private data class GalleryRow(
        val uri: Uri,
        val displayName: String,
        val dateAddedSec: Long,
        val size: Long,
        val width: Int,
        val height: Int
    )

    /** 查询本应用专属相册文件夹（DCIM/PhotoShare）内的所有图片 */
    private fun queryGalleryUris(): List<GalleryRow> {
        val result = mutableListOf<GalleryRow>()
        val resolver = appContext.contentResolver
        val collection = galleryCollectionUri()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        // 本应用只查询自己写入 RELATIVE_PATH 下的文件，无需 READ 权限（Scoped Storage 下
        // 无权限也能读取自己贡献的媒体）。
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?" else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            arrayOf(galleryRelativePath) else null
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val wIdx = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val hIdx = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                result.add(
                    GalleryRow(
                        uri = uri,
                        displayName = c.getString(nameIdx) ?: "image.jpg",
                        dateAddedSec = c.getLong(addedIdx),
                        size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                        width = if (wIdx >= 0) c.getInt(wIdx) else 0,
                        height = if (hIdx >= 0) c.getInt(hIdx) else 0
                    )
                )
            }
        }
        return result
    }

    // ---------------- 启动对账（DB 与 MediaStore 同步） ----------------

    /**
     * 启动时做一次对账（唯一一次全量遍历，之后全部事件驱动）：
     * 1. 相册中新增/变化的文件 -> 补缩略图并入库
     * 2. 索引库中已不在相册的记录 -> 移除
     * 3. 计数器校正为相册真实文件数
     */
    private suspend fun startupSync() {
        // 清理临时目录残留（异常退出时可能遗留）
        uploadTempDir.listFiles()?.forEach { runCatching { it.delete() } }

        val rows = queryGalleryUris()
        val dbUris = dao.getAllUris().toHashSet()

        // 相册中新增/变化的文件 -> 入库并补缩略图
        for (row in rows) {
            if (row.uri.toString() in dbUris) continue
            val thumb = thumbnailGenerator.generate(row.uri.toString()) {
                appContext.contentResolver.openInputStream(row.uri)
            }
            val entity = PhotoEntity(
                id = 0,
                fileName = row.displayName,
                contentUri = row.uri.toString(),
                createTime = row.dateAddedSec * 1000,
                modifyTime = row.dateAddedSec * 1000,
                width = row.width,
                height = row.height,
                fileSize = row.size,
                thumbnailPath = thumb?.absolutePath,
                thumbnailStatus = if (thumb != null) PhotoEntity.THUMB_READY else PhotoEntity.THUMB_FAILED,
                exifJson = null,
                uploadComplete = true
            )
            dao.upsert(entity)
        }

        // 索引库中已不存在于相册的记录 -> 移除
        val galleryUris = rows.map { it.uri.toString() }.toSet()
        for (uri in dbUris) {
            if (uri !in galleryUris) {
                dao.deleteByUri(uri)
                thumbnailGenerator.deleteThumbFor(uri)
            }
        }

        Log.i(TAG, "startupSync done, gallery=${rows.size}, db=${dbUris.size}")
        // 启动对账后，将计数器校正为相册真实文件数
        reconcileCounter()
    }

    /** 将计数器校正为系统相册中本应用专属文件夹的真实图片数 */
    private suspend fun reconcileCounter() {
        val n = queryGalleryUris().size
        counterLock.withLock {
            _counter.value = n
            persistCounter()
        }
        Log.i(TAG, "counter reconciled to gallery=$n")
    }

    // ---------------- HTTP 层查询接口 ----------------

    suspend fun getPage(page: Int, size: Int): List<PhotoEntity> =
        dao.getPage(size.coerceIn(1, 500), (page.coerceAtLeast(0)) * size)

    suspend fun count(): Int = dao.count()

    suspend fun latestId(): Long = dao.latestId() ?: 0

    suspend fun findByName(name: String): PhotoEntity? = dao.getByName(name)

    suspend fun findById(id: Long): PhotoEntity? = dao.getById(id)
}
