package com.example.sony_ftp

import android.app.Application
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
     * 上传临时目录：FTP 服务器先把图片写入此处（应用私有目录，无需任何权限）。
     * 上传完成后由仓库转移到系统相册（MediaStore -> DCIM/PhotoShare）。
     */
    val uploadTempDir: File by lazy { File(filesDir, "ftp_upload_temp") }

    /**
     * 系统相册中的 APP 专属文件夹（MediaStore RELATIVE_PATH，需以 / 结尾）。
     * 通过 Scoped Storage 写入，无需「所有文件访问权限」，也不会跳转系统设置页。
     */
    val galleryRelativePath: String = "DCIM/PhotoShare/"

    /** 用于 UI 展示的相册路径文本（不含存储根，如 DCIM/PhotoShare） */
    val galleryDisplayPath: String get() = galleryRelativePath.trimEnd('/')

    /** 缩略图缓存目录（应用私有，无需权限） */
    val thumbnailDir: File by lazy {
        File(externalCacheDir ?: cacheDir, "thumbnails")
    }

    val database: PhotoDatabase by lazy { PhotoDatabase.get(this) }

    val serverConfig: ServerConfig by lazy { ServerConfig(this) }

    val repository: PhotoRepository by lazy {
        PhotoRepository(
            appContext = this,
            uploadTempDir = uploadTempDir,
            galleryRelativePath = galleryRelativePath,
            dao = database.photoDao(),
            thumbnailGenerator = ThumbnailGenerator(thumbnailDir),
            serverConfig = serverConfig
        )
    }
}
