package engine

import engine.detection.ReferenceWatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceWatcherTest {

    private class TestSticky : ReferenceWatcher.Sticky {
        override var watchBook: Int? = null
        override var watchChapter: Int? = null
        override var watchExpiresAt: Long = 0L
    }

    /** Feeds utterances in order through one sticky context; returns all refs emitted. */
    private fun run(vararg utterances: String, now: Long = 1_000L): List<ReferenceWatcher.Ref> {
        val sticky = TestSticky()
        val all = mutableListOf<ReferenceWatcher.Ref>()
        for (u in utterances) all += ReferenceWatcher.process(u, sticky, now)
        return all
    }

    private fun ReferenceWatcher.Ref.triple() = Triple(bookNum, chapter, verseStart)

    // ── Single-utterance explicit references (from real transcripts) ────────────

    @Test fun `Matthew 28 18 with keywords`() {
        val r = run("Евангелие от Матфея, 28 глава, 18 стиха.").single()
        assertEquals(Triple(40, 28, 18), r.triple())
        assertEquals(1, r.tier)
    }

    @Test fun `inflected book without от`() {
        val r = run("Евангелие Матфея, 28 глава, 19 стих").single()
        assertEquals(Triple(40, 28, 19), r.triple())
    }

    @Test fun `digit ordinal book chapter verse`() {
        // "1 Петра 3-я глава 21-й стих" — exercises digit-ordinal stripping (3-я→3, 21-й→21)
        val r = run("1 Петра 3-я глава 21-й стих.")
        assertTrue(r.any { it.triple() == Triple(60, 3, 21) }, "expected 1 Peter 3:21, got $r")
    }

    @Test fun `range with по`() {
        val r = run("Евангелие от Марка, 16 глава, 15 по 16 стих.").single()
        assertEquals(Triple(41, 16, 15), r.triple())
        assertEquals(16, r.verseEnd)
    }

    @Test fun `hyphen range`() {
        val r = run("Деяния 2 глава 37-38 стихи").single()
        assertEquals(Triple(44, 2, 37), r.triple())
        assertEquals(38, r.verseEnd)
    }

    @Test fun `с N по M range`() {
        val r = run("Деяние 16 глава с 30 по 31 стих").single()
        assertEquals(Triple(44, 16, 30), r.triple())
        assertEquals(31, r.verseEnd)
    }

    // ── Word-number ordinals ────────────────────────────────────────────────────

    @Test fun `chapter and verse as ordinal words`() {
        // "Десятая глава, девятый-десятой стихи" → 10:9-10 (sticky book from previous line)
        val r = run("послание к римлянам.", "Десятая глава, девятый-десятой стихи.")
        assertTrue(r.any { it.bookNum == 45 && it.chapter == 10 && it.verseStart == 9 && it.verseEnd == 10 },
            "expected Romans 10:9-10, got $r")
    }

    // ── Sticky verse-by-verse reading (the Daniel 6 chain) ──────────────────────

    @Test fun `verse by verse reading keeps book and chapter`() {
        val refs = run(
            "Место, которое записано в книге пророка Даниила.",
            "Мы с вами прочитаем всю 6 главу.",
            "6 стих.",
            "8 стих.",
            "Семнадцатый стих и принесен был камень.",
            "23 стих.",
        )
        val verses = refs.filter { it.bookNum == 27 && it.chapter == 6 }.mapNotNull { it.verseStart }
        assertTrue(verses.containsAll(listOf(6, 8, 17, 23)), "expected Daniel 6:6/8/17/23, got $refs")
    }

    // ── Split across utterances ─────────────────────────────────────────────────

    @Test fun `book then chapter verse next utterance`() {
        val r = run("...Павла к римлянам, 6 глава, несколько стихов.", "Четвертый стих.")
        assertTrue(r.any { it.bookNum == 45 && it.chapter == 6 && it.verseStart == 4 },
            "expected Romans 6:4, got $r")
    }

    // ── Precision (must NOT emit) ───────────────────────────────────────────────

    @Test fun `bare number in prose does not emit`() {
        assertTrue(run("У меня было три причины так поступить.").isEmpty())
        assertTrue(run("Прошло двадцать лет с того дня.").isEmpty())
    }

    @Test fun `book plus number plus noun does not emit`() {
        // "Марк 5 человек" — a name + count, not Mark 5
        assertTrue(run("Марк 5 человек пришли сегодня.").isEmpty())
    }

    @Test fun `stale sticky expires`() {
        val sticky = TestSticky()
        ReferenceWatcher.process("книга пророка Даниила, 6 глава.", sticky, now = 1_000L)
        // Much later, a bare verse with no book should NOT bind to the expired Daniel context.
        val late = ReferenceWatcher.process("18 стих.", sticky, now = 1_000L + 10 * 60_000L)
        assertTrue(late.isEmpty(), "stale context should have expired, got $late")
    }
}
