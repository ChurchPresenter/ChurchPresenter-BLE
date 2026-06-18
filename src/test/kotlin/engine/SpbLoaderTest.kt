package engine

import engine.bible.SpbLoader
import engine.detection.BookResolver
import engine.detection.ExplicitParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpbLoaderTest {

    companion object {
        init {
            BookResolver.register(SpbLoader.scanAllBookManifests())
        }
    }

    private val translations by lazy { SpbLoader.loadDefaults() }
    private val kjv by lazy { translations.find { it.id == "ENG_KJV" }!! }
    private val rst by lazy { translations.find { it.id == "RUS_RST" }!! }

    @Test fun `defaults load successfully`() {
        assertTrue(translations.isNotEmpty(), "No translations loaded — check Config.bibleRoot")
    }

    @Test fun `KJV loads with verse data`() {
        assertTrue(kjv.byBCV.size > 31000, "KJV should have 31K+ verses")
    }

    @Test fun `RST loads with verse data`() {
        assertTrue(rst.byBCV.size > 31000, "RST should have 31K+ verses")
    }

    @Test fun `John 3 colon 16 KJV`() {
        val v = kjv.byBCV[Triple(43, 3, 16)]
        assertNotNull(v)
        assertTrue(v.text.contains("God so loved"), "Expected John 3:16 text")
        assertEquals("B043C003V016", v.code)
        assertFalse(v.isHeader)
    }

    @Test fun `Psalm 51 verse 1 KJV`() {
        val v = kjv.byBCV[Triple(19, 51, 1)]
        assertNotNull(v)
        assertTrue(v.text.lowercase().contains("mercy"), "Expected Psalm 51:1 text")
        assertFalse(v.isHeader)
    }

    @Test fun `Psalm 51 verse 1 RST`() {
        val v = rst.byBCV[Triple(19, 51, 1)]
        assertNotNull(v)
        assertFalse(v.isHeader)
        assertEquals(51, v.chapter)
        assertEquals(1, v.verse)
    }

    @Test fun `KJV book manifest includes 66 books`() {
        assertEquals(66, kjv.books.size)
    }

    @Test fun `Hebrew numbering for English`() {
        assertEquals("hebrew", kjv.numbering)
    }

    @Test fun `LXX numbering for Russian`() {
        assertEquals("lxx", rst.numbering)
    }

    @Test fun `no V000 headers in KJV`() {
        val headers = kjv.byBCV.values.count { it.isHeader }
        assertEquals(0, headers, "KJV should have no V000 superscription entries")
    }

    @Test fun `byChapter lookup works`() {
        val ch3 = kjv.byChapter[Pair(43, 3)]
        assertNotNull(ch3)
        assertTrue(ch3.size >= 36, "John chapter 3 has at least 36 verses")
    }

    @Test fun `numberingFor detects language`() {
        assertEquals("lxx", SpbLoader.numberingFor("RUS"))
        assertEquals("lxx", SpbLoader.numberingFor("UKR"))
        assertEquals("hebrew", SpbLoader.numberingFor("ENG"))
        assertEquals("hebrew", SpbLoader.numberingFor("DEU"))
    }

    // SPB-derived book name tests — these forms come from the RST manifest and are
    // NOT in the static alias table, so they only work after BookResolver.register()

    @Test fun `SPB manifests are scannable`() {
        val names = SpbLoader.scanAllBookManifests()
        // 2 SPB files × 66 books each, with deduplication of any shared names
        assertTrue(names.size >= 66, "Expected at least 66 book name entries across SPB files")
    }

    @Test fun `RST SPB form К Римлянам resolves to Romans`() {
        // RST uses "К Римлянам" (not "Римлянам" which is in static aliases)
        val r = ExplicitParser.parse("К Римлянам 8:28")
        assertNotNull(r, "Expected К Римлянам to resolve (registered from RST SPB)")
        assertEquals(45, r.bookNum)
        assertEquals(8, r.chapter)
        assertEquals(28, r.verseStart)
    }

    @Test fun `RST SPB form Книга Судей resolves to Judges`() {
        // RST uses "Книга Судей" (not "Судей" which is in static aliases)
        val r = ExplicitParser.parse("Книга Судей 4:4")
        assertNotNull(r, "Expected Книга Судей to resolve (registered from RST SPB)")
        assertEquals(7, r.bookNum)
        assertEquals(4, r.chapter)
        assertEquals(4, r.verseStart)
    }

    @Test fun `RST SPB form 1-е Коринфянам resolves to 1 Corinthians`() {
        // RST uses "1-е Коринфянам" ordinal form (static has "1 Коринфянам")
        val r = ExplicitParser.parse("1-е Коринфянам 13:4")
        assertNotNull(r, "Expected 1-е Коринфянам to resolve (registered from RST SPB)")
        assertEquals(46, r.bookNum)
        assertEquals(13, r.chapter)
        assertEquals(4, r.verseStart)
    }
}
