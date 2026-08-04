package com.example.sony_ftp.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ThumbnailGeneratorTest {

    private lateinit var srcDir: File
    private lateinit var cacheDir: File
    private lateinit var generator: ThumbnailGenerator

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        srcDir = File(ctx.cacheDir, "thumb_test_src").apply { deleteRecursively(); mkdirs() }
        cacheDir = File(ctx.cacheDir, "thumb_test_cache").apply { deleteRecursively() }
        generator = ThumbnailGenerator(cacheDir)
    }

    private fun createJpeg(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.MAGENTA)
        val f = File(srcDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return f
    }

    @Test
    fun generatesDownscaledThumbnail() {
        val src = createJpeg("big.jpg", 4000, 3000)
        val result = generator.generate(src)
        assertNotNull(result)
        assertEquals(4000, result!!.srcWidth)
        assertEquals(3000, result.srcHeight)
        assertTrue(result.thumbFile.exists())
        assertTrue(result.thumbFile.length() > 0)

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.thumbFile.absolutePath, opts)
        assertTrue("thumb too large: ${opts.outWidth}", opts.outWidth <= ThumbnailGenerator.MAX_DIMENSION * 2)
        assertTrue(opts.outWidth < 4000)
    }

    @Test
    fun cacheIsReused() {
        val src = createJpeg("cached.jpg", 800, 600)
        val first = generator.generate(src)!!
        val firstModified = first.thumbFile.lastModified()
        Thread.sleep(20)
        val second = generator.generate(src)!!
        assertEquals(first.thumbFile.absolutePath, second.thumbFile.absolutePath)
        assertEquals(firstModified, second.thumbFile.lastModified())
    }

    @Test
    fun invalidImageReturnsNull() {
        val bad = File(srcDir, "fake.jpg").apply { writeText("not an image") }
        assertNull(generator.generate(bad))
    }

    @Test
    fun sampleSizeCalculation() {
        assertEquals(1, ThumbnailGenerator.calculateInSampleSize(500, 400, 512))
        assertEquals(4, ThumbnailGenerator.calculateInSampleSize(4000, 3000, 512))
        assertEquals(8, ThumbnailGenerator.calculateInSampleSize(8000, 6000, 512))
    }

    @Test
    fun thumbNameIsStableAndUnique() {
        val a = ThumbnailGenerator.thumbFileNameFor("/p/a.jpg")
        val b = ThumbnailGenerator.thumbFileNameFor("/p/b.jpg")
        assertEquals(a, ThumbnailGenerator.thumbFileNameFor("/p/a.jpg"))
        assertTrue(a != b)
        assertTrue(a.endsWith(".jpg"))
    }
}
