package com.example.sony_ftp.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifFormatTest {

    @Test
    fun `fast shutter formats as fraction`() {
        assertEquals("1/200", ExifFormat.formatShutter(0.005))
        assertEquals("1/8000", ExifFormat.formatShutter(0.000125))
        assertEquals("1/2", ExifFormat.formatShutter(0.5))
    }

    @Test
    fun `slow shutter formats in seconds`() {
        assertEquals("30s", ExifFormat.formatShutter(30.0))
        assertEquals("1s", ExifFormat.formatShutter(1.0))
    }

    @Test
    fun `invalid shutter returns null`() {
        assertNull(ExifFormat.formatShutter(0.0))
        assertNull(ExifFormat.formatShutter(-1.0))
        assertNull(ExifFormat.formatShutter(Double.NaN))
    }

    @Test
    fun `focal length formatting`() {
        assertEquals("50mm", ExifFormat.formatFocalLength(50.0))
        assertEquals("35mm", ExifFormat.formatFocalLength(35.01))
        assertEquals("10.5mm", ExifFormat.formatFocalLength(10.5))
        assertNull(ExifFormat.formatFocalLength(0.0))
    }

    @Test
    fun `aperture formatting`() {
        assertEquals("f1.8", ExifFormat.formatAperture(1.8))
        assertEquals("f8", ExifFormat.formatAperture(8.0))
        assertEquals("f2.8", ExifFormat.formatAperture(2.8))
        assertNull(ExifFormat.formatAperture(-1.0))
    }
}
