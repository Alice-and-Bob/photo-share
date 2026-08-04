package com.example.sony_ftp.repository

import android.graphics.BitmapFactory
import android.os.FileObserver
import android.util.Log
import com.example.sony_ftp.database.PhotoDao
import com.example.sony_ftp.database.PhotoEntity
import com.example.sony_ftp.exif.ExifParser
import com.example.sony_ftp.observer.FileStabilityChecker
import com.example.sony_ftp.observer.RecursiveFileObserver
import com.example.sony_ftp.thumbnail.ThumbnailGenerator
import com.example.sony_ftp.util.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 照片仓库：唯一的索引维护入口。
 *
 * 流程：FTP 上传完成 -> FileObserver 检测 -> 稳定性确认 -> 更新 Room
 *      -> 生成缩略图 -> 网页图库自动刷新。
 *
 * 设计要点：
 * - 事件驱动，无轮询；
 * - Channel 队列 + 限并发，支持数万张图片且 CPU 占用低；
 * - startupSync() 做增量对账，重启后恢复索引与未完成的缩略图。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoRepository(
    val photoDir: File,
    private val dao: PhotoDao,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val serverConfig: ServerConfig
) {
    companion object {
        private const val TAG = "PhotoRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 索引任务队列：缩略图/EXIF 处理限制并发，避免占满 CPU */
    private val indexQueue = Channel<File>(capacity = Channel.UNLIMITED)
    private val indexingNow = ConcurrentHashMap.newKeySet<String>()

    private val fileObserver = RecursiveFileObserver(photoDir) { event, file ->
        onFileEvent(event, file)
    }

    private val counterLock = kotlinx.coroutines.sync.Mutex()

    /**
     * 照片计数器（独立于数据库，便于「清空照片但保留计数」）。
     * 初始化为持久化值；启动时按磁盘实际文件数校正；每次新增/删除照片时同步。
     */
    private val _counter = MutableStateFlow(serverConfig.photoCounter.coerceAtLeast(0))
    val counter: StateFlow<Int> get() = _counter.asStateFlow()

    val photoCount: Flow<Int> get() = dao.countFlow()

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
        photoDir.mkdirs()
        fileObserver.start()
        // 2 个 worker 处理索引队列（EXIF + 缩略图为 IO/CPU 混合任务）
        repeat(2) {
            scope.launch(Dispatchers.IO.limitedParallelism(2)) {
                for (file in indexQueue) {
                    runCatching { indexFile(file) }
                        .onFailure { Log.w(TAG, "index failed: $file", it) }
                    indexingNow.remove(file.absolutePath)
                }
            }
        }
        scope.launch { startupSync() }
    }

    fun stop() {
        persistCounter()
        fileObserver.stop()
        scope.cancel()
    }

    // ---------------- 事件入口 ----------------

    private fun onFileEvent(event: Int, file: File) {
        when (event) {
            FileObserver.CLOSE_WRITE, FileObserver.MOVED_TO -> {
                // .part 重命名为正式文件名时走 MOVED_TO
                if (FileStabilityChecker.isImageFile(file.name)) enqueue(file)
            }
            FileObserver.CREATE -> {
                if (file.isDirectory) return
                // CREATE 只做预登记，等 CLOSE_WRITE 再入库，防止显示上传中的图片
            }
            FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                scope.launch {
                    val existed = dao.getByPath(file.absolutePath) != null
                    dao.deleteByPath(file.absolutePath)
                    thumbnailGenerator.deleteThumbFor(file.absolutePath)
                    if (existed) bumpCounter(-1)
                }
            }
        }
    }

    /** FTP Ftplet 的上传完成回调也走这里，双保险 */
    fun notifyUploadFinished(file: File) {
        if (FileStabilityChecker.isImageFile(file.name)) enqueue(file)
    }

    /**
     * 「重置计数器」：将计数器重新校正为磁盘实际图片文件数。
     * 用于即时修复计数与真实文件不一致的情况。
     */
    suspend fun resyncCounter(): Int {
        reconcileCounter()
        return _counter.value
    }

    /**
     * 「清空所有照片」：删除存储目录下全部照片文件与缩略图，并清空索引库。
     * @param clearCounter true=同步将计数器归零；false=保留当前计数器数值（即使照片已删）
     */
    suspend fun clearAllPhotos(clearCounter: Boolean) {
        // 先停止目录监听，避免删除过程产生大量 DELETE 事件重复改动计数器
        fileObserver.stop()
        try {
            photoDir.listFiles()?.forEach { it.deleteRecursively() }
            photoDir.mkdirs()
            dao.deleteAll()
            thumbnailGenerator.clearAll()
            if (clearCounter) {
                counterLock.withLock {
                    _counter.value = 0
                    persistCounter()
                }
            } else {
                persistCounter()
            }
            Log.i(TAG, "clearAllPhotos done (clearCounter=$clearCounter)")
        } finally {
            fileObserver.start()
        }
    }

    private fun enqueue(file: File) {
        if (indexingNow.add(file.absolutePath)) {
            indexQueue.trySend(file)
        }
    }

    // ---------------- 索引逻辑 ----------------

    private suspend fun indexFile(file: File) {
        if (!file.exists() || !file.isFile) return

        // 上传安全机制：大小 + 修改时间稳定后才入库
        if (!FileStabilityChecker.awaitStable(file)) {
            Log.w(TAG, "file never stabilized, skip: $file")
            return
        }

        val existing = dao.getByPath(file.absolutePath)
        if (existing != null &&
            existing.fileSize == file.length() &&
            existing.modifyTime == file.lastModified() &&
            existing.uploadComplete &&
            existing.thumbnailStatus == PhotoEntity.THUMB_READY
        ) {
            return // 增量更新：内容未变化，跳过
        }

        val exif = ExifParser.parse(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val entity = PhotoEntity(
            id = existing?.id ?: 0,
            fileName = file.name,
            filePath = file.absolutePath,
            createTime = exif.dateTimeMillis ?: file.lastModified(),
            modifyTime = file.lastModified(),
            width = maxOf(bounds.outWidth, 0),
            height = maxOf(bounds.outHeight, 0),
            fileSize = file.length(),
            thumbnailPath = null,
            thumbnailStatus = PhotoEntity.THUMB_PENDING,
            exifJson = exif.toJson(),
            uploadComplete = true
        )
        val wasNew = existing == null
        dao.upsert(entity)
        if (wasNew) bumpCounter(1)
        generateThumbnail(file)
    }

    private suspend fun generateThumbnail(file: File) {
        val result = thumbnailGenerator.generate(file)
        if (result != null) {
            dao.updateThumbnail(
                file.absolutePath,
                result.thumbFile.absolutePath,
                PhotoEntity.THUMB_READY
            )
        } else {
            dao.updateThumbnail(file.absolutePath, null, PhotoEntity.THUMB_FAILED)
        }
    }

    // ---------------- 重启恢复 / 增量对账 ----------------

    /**
     * 启动时做一次增量对账（唯一一次全量遍历，之后全部事件驱动）：
     * 1. 磁盘上新增/变化的文件 -> 入队索引
     * 2. 数据库中已删除的文件 -> 移除记录
     * 3. 缩略图未完成的 -> 继续生成
     */
    private suspend fun startupSync() {
        val dbPaths = dao.getAllPaths().toHashSet()
        val diskPaths = HashSet<String>()

        photoDir.walkTopDown()
            .filter { it.isFile && FileStabilityChecker.isImageFile(it.name) }
            .forEach { file ->
                diskPaths.add(file.absolutePath)
                val existing = dao.getByPath(file.absolutePath)
                if (existing == null ||
                    existing.fileSize != file.length() ||
                    existing.modifyTime != file.lastModified() ||
                    !existing.uploadComplete
                ) {
                    enqueue(file)
                }
            }

        val removed = dbPaths.filter { it !in diskPaths }
        if (removed.isNotEmpty()) {
            removed.chunked(500).forEach { dao.deleteByPaths(it) }
            removed.forEach { thumbnailGenerator.deleteThumbFor(it) }
        }

        dao.getPendingThumbnails().forEach { pending ->
            val f = File(pending.filePath)
            if (f.exists()) enqueue(f)
        }
        Log.i(TAG, "startupSync done, disk=${diskPaths.size}, removedFromDb=${removed.size}")
        // 启动对账后，将计数器校正为磁盘实际文件数（修复「上传后计数不刷新」类问题）
        reconcileCounter()
    }

    /** 将计数器校正为磁盘实际图片文件数 */
    private suspend fun reconcileCounter() {
        val disk = photoDir.walkTopDown()
            .count { it.isFile && FileStabilityChecker.isImageFile(it.name) }
        counterLock.withLock {
            _counter.value = disk
            persistCounter()
        }
        Log.i(TAG, "counter reconciled to disk=$disk")
    }

    // ---------------- HTTP 层查询接口 ----------------

    suspend fun getPage(page: Int, size: Int): List<PhotoEntity> =
        dao.getPage(size.coerceIn(1, 500), (page.coerceAtLeast(0)) * size)

    suspend fun count(): Int = dao.count()

    suspend fun latestId(): Long = dao.latestId() ?: 0

    suspend fun findByName(name: String): PhotoEntity? = dao.getByName(name)

    suspend fun findById(id: Long): PhotoEntity? = dao.getById(id)
}
