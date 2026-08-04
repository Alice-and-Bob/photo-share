package com.example.sony_ftp.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDaoTest {

    private lateinit var db: PhotoDatabase
    private lateinit var dao: PhotoDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PhotoDatabase::class.java
        ).build()
        dao = db.photoDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun photo(name: String, path: String, complete: Boolean = true, time: Long = 0) =
        PhotoEntity(
            fileName = name, filePath = path,
            createTime = time, modifyTime = time, fileSize = 100,
            uploadComplete = complete
        )

    @Test
    fun upsertAndQueryByPath() = runBlocking {
        dao.upsert(photo("IMG001.JPG", "/p/IMG001.JPG"))
        val loaded = dao.getByPath("/p/IMG001.JPG")
        assertNotNull(loaded)
        assertEquals("IMG001.JPG", loaded!!.fileName)
    }

    @Test
    fun upsertSamePathReplaces() = runBlocking {
        dao.upsert(photo("IMG001.JPG", "/p/IMG001.JPG"))
        dao.upsert(dao.getByPath("/p/IMG001.JPG")!!.copy(fileSize = 999))
        assertEquals(1, dao.count())
        assertEquals(999L, dao.getByPath("/p/IMG001.JPG")!!.fileSize)
    }

    @Test
    fun incompleteUploadsAreHiddenFromGallery() = runBlocking {
        dao.upsert(photo("A.JPG", "/p/A.JPG", complete = true))
        dao.upsert(photo("B.JPG", "/p/B.JPG", complete = false))
        assertEquals(1, dao.count())
        assertNull(dao.getByName("B.JPG"))
        dao.markComplete("/p/B.JPG")
        assertEquals(2, dao.count())
    }

    @Test
    fun pageOrderIsNewestFirst() = runBlocking {
        dao.upsert(photo("OLD.JPG", "/p/OLD.JPG", time = 1000))
        dao.upsert(photo("NEW.JPG", "/p/NEW.JPG", time = 2000))
        val page = dao.getPage(10, 0)
        assertEquals("NEW.JPG", page[0].fileName)
        assertEquals("OLD.JPG", page[1].fileName)
    }

    @Test
    fun thumbnailUpdateFlow() = runBlocking {
        dao.upsert(photo("A.JPG", "/p/A.JPG"))
        dao.updateThumbnail("/p/A.JPG", "/t/a.jpg", PhotoEntity.THUMB_READY)
        val p = dao.getByPath("/p/A.JPG")!!
        assertEquals(PhotoEntity.THUMB_READY, p.thumbnailStatus)
        assertEquals("/t/a.jpg", p.thumbnailPath)
        assertEquals(0, dao.getPendingThumbnails().size)
    }

    @Test
    fun deleteByPathRemovesRow() = runBlocking {
        dao.upsert(photo("A.JPG", "/p/A.JPG"))
        dao.deleteByPath("/p/A.JPG")
        assertNull(dao.getByPath("/p/A.JPG"))
        assertEquals(0, dao.countFlow().first())
    }
}
