package com.example.sony_ftp.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE contentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE fileName = :name AND uploadComplete = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getByName(name: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PhotoEntity?

    /** 网页照片墙分页查询，最新照片在前 */
    @Query(
        "SELECT * FROM photos WHERE uploadComplete = 1 " +
            "ORDER BY createTime DESC, id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getPage(limit: Int, offset: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE uploadComplete = 1")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE uploadComplete = 1")
    fun countFlow(): Flow<Int>

    @Query("SELECT MAX(id) FROM photos WHERE uploadComplete = 1")
    suspend fun latestId(): Long?

    @Query("SELECT contentUri FROM photos")
    suspend fun getAllUris(): List<String>

    /** 重启恢复：找出缩略图未生成的记录继续处理 */
    @Query("SELECT * FROM photos WHERE uploadComplete = 1 AND thumbnailStatus = 0")
    suspend fun getPendingThumbnails(): List<PhotoEntity>

    @Query("UPDATE photos SET thumbnailPath = :thumbPath, thumbnailStatus = :status WHERE contentUri = :uri")
    suspend fun updateThumbnailByUri(uri: String, thumbPath: String?, status: Int)

    @Query("DELETE FROM photos WHERE contentUri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM photos WHERE contentUri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /** 清空照片索引（一键清空存储目录时调用） */
    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}
