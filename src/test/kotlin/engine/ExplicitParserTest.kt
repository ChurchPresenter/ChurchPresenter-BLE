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

    // ── Multilingual explicit-reference tests ─────────────────────────────────

    @Test fun `German Johannes Kapitel Vers`() {
        val r = ExplicitParser.parse("Johannes Kapitel 3 Vers 16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `German Matthäus chapter colon`() {
        val r = ExplicitParser.parse("Matthäus 5:3")
        assertNotNull(r)
        assertEquals(40, r.bookNum)
        assertEquals(5, r.chapter)
        assertEquals(3, r.verseStart)
    }

    @Test fun `German 1 Mose`() {
        val r = ExplicitParser.parse("1. Mose 1:1")
        assertNotNull(r)
        assertEquals(1, r.bookNum)
    }

    @Test fun `German range bis`() {
        val r = ExplicitParser.parse("Johannes 3:16 bis 18")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(16, r.verseStart)
        assertEquals(18, r.verseEnd)
    }

    @Test fun `French Jean chapitre verset`() {
        val r = ExplicitParser.parse("Jean chapitre 3 verset 16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `French Matthieu`() {
        val r = ExplicitParser.parse("Matthieu 5:3")
        assertNotNull(r)
        assertEquals(40, r.bookNum)
    }

    @Test fun `Spanish Juan capítulo versículo`() {
        val r = ExplicitParser.parse("Juan capítulo 3 versículo 16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `Spanish Mateo`() {
        val r = ExplicitParser.parse("Mateo 5:3")
        assertNotNull(r)
        assertEquals(40, r.bookNum)
    }

    @Test fun `Portuguese João`() {
        val r = ExplicitParser.parse("João 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `Portuguese Mateus`() {
        val r = ExplicitParser.parse("Mateus 5:3")
        assertNotNull(r)
        assertEquals(40, r.bookNum)
    }

    @Test fun `Romanian Ioan`() {
        val r = ExplicitParser.parse("Ioan 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
    }

    @Test fun `Ukrainian Івана`() {
        val r = ExplicitParser.parse("Івана 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
        assertEquals(3, r.chapter)
        assertEquals(16, r.verseStart)
    }

    @Test fun `Polish Jana`() {
        val r = ExplicitParser.parse("Jana 3:16")
        assertNotNull(r)
        assertEquals(43, r.bookNum)
    }

    @Test fun `Polish Mateusza`() {
        val r = ExplicitParser.parse("Mateusza 5:3")
        assertNotNull(r)
        assertEquals(40, r.bookNum)
    }
}
