package com.example.sony_ftp.observer

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 递归目录监听（事件驱动，禁止轮询扫描）。
 * FTP 上传完成 -> FileObserver 检测变化 -> 更新 Room -> 生成缩略图 -> 更新网页图库。
 *
 * API 29+ 使用 FileObserver(File, mask) 构造函数。
 * inotify 不递归，因此对每个子目录单独建立 watcher，
 * 新建目录时（相机按日期建目录）动态添加。
 */
class RecursiveFileObserver(
    private val rootDir: File,
    private val listener: (event: Int, file: File) -> Unit
) {

    companion object {
        const val MASK = FileObserver.CREATE or
            FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or
            FileObserver.DELETE or
            FileObserver.DELETE_SELF
    }

    /** 必须强引用持有所有 observer，否则会被 GC 回收导致事件丢失 */
    private val observers = ConcurrentHashMap<String, SingleObserver>()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        rootDir.mkdirs()
        watchRecursively(rootDir)
    }

    fun stop() {
        running = false
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun watchRecursively(dir: File) {
        if (!dir.isDirectory) return
        addWatch(dir)
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) watchRecursively(child)
        }
    }

    private fun addWatch(dir: File) {
        if (!running) return
        observers.computeIfAbsent(dir.absolutePath) {
            SingleObserver(dir).also { it.startWatching() }
        }
    }

    private fun removeWatch(path: String) {
        observers.remove(path)?.stopWatching()
    }

    private inner class SingleObserver(private val dir: File) : FileObserver(dir, MASK) {
        override fun onEvent(event: Int, path: String?) {
            val e = event and ALL_EVENTS
            if (path == null) {
                if (e == DELETE_SELF) removeWatch(dir.absolutePath)
                return
            }
            val file = File(dir, path)
            when (e) {
                CREATE, MOVED_TO -> {
                    if (file.isDirectory) {
                        // 相机新建子目录：递归加监听
                        watchRecursively(file)
                    }
                    listener(e, file)
                }
                CLOSE_WRITE -> listener(e, file)
                DELETE, MOVED_FROM -> {
                    removeWatch(file.absolutePath)
                    listener(e, file)
                }
                DELETE_SELF -> removeWatch(dir.absolutePath)
            }
        }
    }
}
