package com.example.sony_ftp

import android.app.Application
import android.os.Environment
import com.example.sony_ftp.database.PhotoDatabase
import com.example.sony_ftp.repository.PhotoRepository
import com.example.sony_ftp.thumbnail.ThumbnailGenerator
import com.example.sony_ftp.util.ServerConfig
import java.io.File

/**
 * 应用级依赖容器（轻量手动 DI）。
 */
class PhotoServerApp : Application() {

    /**
     * 照片上传目录：固定为共享存储 DCIM/PhotoShare。
     * 写入共享存储需「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），已在启动服务器时校验。
     * 放在 DCIM 下，照片可在系统相册中直接显示。
     */
    val photoDir: File
        get() {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "PhotoShare"
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /** 缩略图缓存目录 */
    val thumbnailDir: File by lazy {
        File(externalCacheDir ?: cacheDir, "thumbnails")
    }

    val database: PhotoDatabase by lazy { PhotoDatabase.get(this) }

    val serverConfig: ServerConfig by lazy { ServerConfig(this) }

    val repository: PhotoRepository by lazy {
        PhotoRepository(
            photoDir = photoDir,
            dao = database.photoDao(),
            thumbnailGenerator = ThumbnailGenerator(thumbnailDir),
            serverConfig = serverConfig
        )
    }
}
