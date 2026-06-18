package engine

import engine.bible.SpbLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpbLoaderTest {

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
}
