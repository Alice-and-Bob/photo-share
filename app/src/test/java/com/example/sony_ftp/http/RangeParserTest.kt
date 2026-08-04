package com.example.sony_ftp.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeParserTest {

    private val fileLen = 1000L

    @Test
    fun `full range start-end`() {
        val r = RangeParser.parse("bytes=0-499", fileLen)!!
        assertEquals(0L, r.start)
        assertEquals(499L, r.end)
        assertEquals(500L, r.length)
    }

    @Test
    fun `open ended range`() {
        val r = RangeParser.parse("bytes=200-", fileLen)!!
        assertEquals(200L, r.start)
        assertEquals(999L, r.end)
        assertEquals(800L, r.length)
    }

    @Test
    fun `suffix range last N bytes`() {
        val r = RangeParser.parse("bytes=-100", fileLen)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `suffix larger than file clamps to zero`() {
        val r = RangeParser.parse("bytes=-5000", fileLen)!!
        assertEquals(0L, r.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `end beyond file length is clamped`() {
        val r = RangeParser.parse("bytes=900-99999", fileLen)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `start beyond file length is invalid`() {
        assertNull(RangeParser.parse("bytes=1000-1100", fileLen))
    }

    @Test
    fun `invalid formats return null`() {
        assertNull(RangeParser.parse(null, fileLen))
        assertNull(RangeParser.parse("", fileLen))
        assertNull(RangeParser.parse("bytes=", fileLen))
        assertNull(RangeParser.parse("bytes=-", fileLen))
        assertNull(RangeParser.parse("bytes=abc-def", fileLen))
        assertNull(RangeParser.parse("items=0-100", fileLen))
    }

    @Test
    fun `reversed range is invalid`() {
        assertNull(RangeParser.parse("bytes=500-100", fileLen))
    }

    @Test
    fun `zero length file has no valid range`() {
        assertNull(RangeParser.parse("bytes=0-", 0))
    }
}
