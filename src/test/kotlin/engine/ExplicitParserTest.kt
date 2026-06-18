package engine

import engine.detection.ExplicitParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExplicitParserTest {

    @Test fun `John 3 colon 16`() {
        val r = ExplicitParser.parse("John 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
        assertNull(r.verseEnd)
    }

    @Test fun `case insensitive`() {
        val r = ExplicitParser.parse("JOHN 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
    }

    @Test fun `embedded reference`() {
        val r = ExplicitParser.parse("Today we read from John 3:16 together")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `Psalm 51 verse 1`() {
        val r = ExplicitParser.parse("Psalm 51:1")
        assertNotNull(r)
        assertEquals(19, r.bookNum)
        assertEquals(51, r.chapter)
        assertEquals(1, r.verseStart)
        assertNull(r.verseEnd)
    }

    @Test fun `Psalm range`() {
        val r = ExplicitParser.parse("Psalm 119 verses 105 to 112")
        assertNotNull(r)
        assertEquals(19, r.bookNum)
        assertEquals(119, r.chapter)
        assertEquals(105, r.verseStart)
        assertEquals(112, r.verseEnd)
    }

    @Test fun `hyphen range`() {
        val r = ExplicitParser.parse("John 3:16-19")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
        assertEquals(19, r.verseEnd)
    }

    @Test fun `Russian reference`() {
        val r = ExplicitParser.parse("Иоанна 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `Russian от Иоанна`() {
        val r = ExplicitParser.parse("от Иоанна 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
    }

    @Test fun `Russian Псалом`() {
        val r = ExplicitParser.parse("Псалом 51:1")
        assertNotNull(r)
        assertEquals(19, r.bookNum)
        assertEquals(51, r.chapter)
        assertEquals(1, r.verseStart)
    }

    @Test fun `chapter only`() {
        val r = ExplicitParser.parse("John 3")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertNull(r.verseStart)
    }

    @Test fun `1 Samuel`() {
        val r = ExplicitParser.parse("1 Samuel 1:1")
        assertNotNull(r)
        assertEquals(9, r.bookNum)
        assertEquals(1, r.chapter)
        assertEquals(1, r.verseStart)
    }

    @Test fun `Song of Solomon`() {
        val r = ExplicitParser.parse("Song of Solomon 1:1")
        assertNotNull(r)
        assertEquals(22, r.bookNum)
    }

    @Test fun `no reference returns null`() {
        assertNull(ExplicitParser.parse("the quick brown fox"))
        assertNull(ExplicitParser.parse(""))
    }

    @Test fun `inverted range is discarded`() {
        val r = ExplicitParser.parse("John 3:19-16")
        assertNotNull(r)
        assertEquals(19, r.verseStart)
        assertNull(r.verseEnd)
    }

    @Test fun `Revelation`() {
        val r = ExplicitParser.parse("Revelation 1:1")
        assertNotNull(r)
        assertEquals(66, r.bookNum)
    }
}
