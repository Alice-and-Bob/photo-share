package com.example.sony_ftp.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照片索引实体。所有网页/API 请求都从该索引读取，禁止实时扫描目录。
 */
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["fileName"]),
        Index(value = ["createTime"])
    ]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val createTime: Long,
    val modifyTime: Long,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val thumbnailPath: String? = null,
    val thumbnailStatus: Int = THUMB_PENDING,
    val exifJson: String? = null,
    val uploadComplete: Boolean = false
) {
    companion object {
        const val THUMB_PENDING = 0
        const val THUMB_READY = 1
        const val THUMB_FAILED = 2
    }
}
