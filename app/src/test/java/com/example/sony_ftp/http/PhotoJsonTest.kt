package com.example.sony_ftp.http

import com.example.sony_ftp.database.PhotoEntity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoJsonTest {

    private fun samplePhoto() = PhotoEntity(
        id = 7,
        fileName = "IMG001.JPG",
        filePath = "/photos/IMG001.JPG",
        createTime = 1700000000000,
        modifyTime = 1700000001000,
        width = 6000,
        height = 4000,
        fileSize = 12345678,
        thumbnailPath = "/cache/abc.jpg",
        thumbnailStatus = PhotoEntity.THUMB_READY,
        exifJson = """{"focalLength":"50mm","aperture":"f1.8","shutter":"1/200","iso":"100"}""",
        uploadComplete = true
    )

    @Test
    fun `photo list json matches API contract`() {
        val json = PhotoJson.photoListJson(listOf(samplePhoto()))
        val arr = JSONArray(json)
        assertEquals(1, arr.length())

        val obj = arr.getJSONObject(0)
        assertEquals("IMG001.JPG", obj.getString("name"))
        assertEquals("/thumb/IMG001.JPG", obj.getString("thumbnail"))
        assertEquals("/download/IMG001.JPG", obj.getString("original"))

        val exif = obj.getJSONObject("exif")
        assertEquals("50mm", exif.getString("focalLength"))
        assertEquals("f1.8", exif.getString("aperture"))
        assertEquals("1/200", exif.getString("shutter"))
        assertEquals("100", exif.getString("iso"))
    }

    @Test
    fun `filename with spaces is url encoded`() {
        val p = samplePhoto().copy(fileName = "MY PHOTO.JPG")
        val obj = PhotoJson.photoToJson(p)
        assertEquals("/thumb/MY%20PHOTO.JPG", obj.getString("thumbnail"))
    }

    @Test
    fun `empty exif yields empty object`() {
        val p = samplePhoto().copy(exifJson = null)
        val obj = PhotoJson.photoToJson(p)
        assertEquals(0, obj.getJSONObject("exif").length())
    }

    @Test
    fun `path traversal names are rejected`() {
        assertFalse(PhotoJson.isSafeFileName("../secret.txt"))
        assertFalse(PhotoJson.isSafeFileName("a/b.jpg"))
        assertFalse(PhotoJson.isSafeFileName("a\\b.jpg"))
        assertFalse(PhotoJson.isSafeFileName(""))
        assertTrue(PhotoJson.isSafeFileName("IMG001.JPG"))
        assertTrue(PhotoJson.isSafeFileName("DSC_0001 (2).jpg"))
    }
}
