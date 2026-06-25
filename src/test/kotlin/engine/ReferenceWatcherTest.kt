package engine

import engine.detection.BookResolver
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

    // ── New true-positives folded in from two archived service backups ──────────
    // (Curated rows from REFERENCE_DETECTION_PLAN.md §5/§7. Hardcoded so coverage holds
    //  without the local .db files present; the same rows are replayed by DbReplayTest.)

    @Test fun `Ephesians 4 6 with keywords (f1#27)`() {
        val r = run("Послание Ефесянам, 4 глава, 6 стих.").single()
        assertEquals(Triple(49, 4, 6), r.triple())
        assertEquals(1, r.tier)
    }

    @Test fun `Ephesians 4 6 sticky on next row (f1#27 to #28)`() {
        val r = run(
            "Послание Ефесянам, 4 глава, 6 стих.",
            "Мы оттолкнемся отсюда, от этого стиха, 4 глава, 6 стих.",
        )
        assertTrue(r.any { it.triple() == Triple(49, 4, 6) }, "expected Ephesians 4:6, got $r")
    }

    @Test fun `Deuteronomy 6 4-9 word-ordinal chapter and range (f1#377)`() {
        val r = run(
            "И если мы прочитаем, откроем священное место, которое записано в книге " +
                "«Второзаконие», шестая глава, с 4 по 9 стих записаны такие слова.",
        ).single()
        assertEquals(Triple(5, 6, 4), r.triple())
        assertEquals(9, r.verseEnd)
    }

    @Test fun `Deuteronomy 6 4 (f1#378)`() {
        val r = run("Второзаконие, 6 глава 4 стиха.").single()
        assertEquals(Triple(5, 6, 4), r.triple())
    }

    @Test fun `Matthew 7 21 split across rows with instrumental стихом (f1#409 to #410)`() {
        val r = run("Как это сказано в Матфея 7 глава.", "21 стихом.")
        assertTrue(r.any { it.triple() == Triple(40, 7, 21) }, "expected Matthew 7:21, got $r")
    }

    @Test fun `Colossians 3 21 with keywords (f2#3)`() {
        val r = run("В послании к колоссянам, 3 глава, 21 стих.").single()
        assertEquals(Triple(51, 3, 21), r.triple())
        assertEquals(1, r.tier)
    }

    @Test fun `word-ordinal chapter then с N стиха sticky book (f2#660 to #661)`() {
        // "Четвертая глава." → "С 5 стиха." resolves against the sticky book (Joshua).
        val r = run("Читаем книгу Иисуса Навина.", "Четвертая глава.", "С 5 стиха.")
        assertTrue(r.any { it.triple() == Triple(6, 4, 5) }, "expected Joshua 4:5, got $r")
    }

    @Test fun `verse-before-chapter order against sticky book (f2#633)`() {
        // "14 стих 3 главы …" — verse named before the chapter; binds to the sticky book.
        val r = run("Читаем книгу Иисуса Навина.", "14 стих 3 главы.")
        assertTrue(r.any { it.triple() == Triple(6, 3, 14) }, "expected Joshua 3:14, got $r")
    }

    @Test fun `garbled Russian book rescued by English translation track (f2#631 to #633)`() {
        // The live engine feeds transcript + translation combined. Here the Russian book is
        // STT-garbled ("Иисуса Новина" — does not match the alias), but the English translation
        // says "Joshua", which resolves via the alias table and seeds the sticky book. A later
        // bare verse/chapter then binds to it — cross-language corroboration.
        val r = run(
            "Иисуса Новина. This is Joshua Noven.",
            "14 стих 3 главы. 14 verse 3.",
        )
        assertTrue(r.any { it.triple() == Triple(6, 3, 14) }, "expected Joshua 3:14 via EN book, got $r")
    }

    // ── New precision negatives folded in from the two backups ───────────────────
    // стих*/глав* look-alikes and bare verse/chapter words with no real number must NOT emit,
    // both standalone and when a live sticky context is present (so they can't bind to it).

    /** Feeds a live reference first, then [line]; returns only the refs [line] itself emits. */
    private fun withSticky(line: String): List<ReferenceWatcher.Ref> {
        val sticky = TestSticky()
        ReferenceWatcher.process("Послание к римлянам, 6 глава, 4 стих.", sticky, 1_000L)
        return ReferenceWatcher.process(line, sticky, 1_000L)
    }

    private fun assertNoEmit(line: String) {
        assertTrue(run(line).isEmpty(), "standalone should not emit: \"$line\" → ${run(line)}")
        assertTrue(withSticky(line).isEmpty(), "should not bind to sticky: \"$line\" → ${withSticky(line)}")
    }

    @Test fun `стихотворение look-alike does not emit (f1#356, f2#12)`() {
        assertNoEmit("где звучали стихотворения, где пелись пения.")
        assertNoEmit("во славу Твоей молитвы Пение, стихотворение слово Твое.")
        assertNoEmit("за все стихотворения, которые были сказаны.")
    }

    @Test fun `главное look-alike does not emit (f2#712)`() {
        assertNoEmit("Это самое главное в нашей жизни.")
    }

    @Test fun `глава семьи with no real number does not emit (f1#332)`() {
        // "семьи" collides with the stem for 7 (семь); it must be rejected as a non-number.
        assertNoEmit("глава семьи всегда готовым от сердца жертвовать собой.")
        assertNoEmit("глава семьи")
    }

    @Test fun `bare plural стихи with no number does not emit (f2#623, #624)`() {
        assertNoEmit("читать стихи, рисовать красиво или еще чего-то.")
    }

    @Test fun `bare один стих and этот стих do not emit (f1#662, #665)`() {
        assertNoEmit("И хочу прочитать один стих.")
        assertNoEmit("этот стих для меня был чужой, непонятный.")
    }

    // ── Epistle (ordinal) disambiguation — 1/2/3 John, 1/2 Peter (2026-06-25 study §2) ──────────

    @Test fun `Послание Иоанна resolves to the epistle not the gospel (seg#3)`() {
        // The real seg#3: "1 Послание Иоанна, 4 глава, 3 стиха" → 1 John 4:3 (62), NOT John 4:3 (43).
        val r = run("1 Послание Иоанна, 4 глава, 3 стиха.").single()
        assertEquals(Triple(62, 4, 3), r.triple())
    }

    @Test fun `bare Послание Иоанна defaults to 1 John`() {
        val r = run("Послание Иоанна, 4 глава, 15 стих.").single()
        assertEquals(Triple(62, 4, 15), r.triple())
    }

    @Test fun `2-е Послание Иоанна selects the second epistle`() {
        val r = run("2 Послание Иоанна, 1 глава, 6 стих.").single()
        assertEquals(Triple(63, 1, 6), r.triple())
    }

    @Test fun `digit-adjacent 1 Иоанна locks canonical id 62`() {
        // "1-е Иоанна 4:15" — digit adjacency already joins via the alias; lock the canonical id so
        // the CP-side positional map (62 → 1 John index 61) has a trustworthy ground truth.
        val r = run("1-е Иоанна 4:15.").single()
        assertEquals(62, r.bookNum)
    }

    @Test fun `Первое Иоанна word-ordinal resolves to the epistle`() {
        val r = run("Первое Иоанна, 4 глава, 15 стих.").single()
        assertEquals(Triple(62, 4, 15), r.triple())
    }

    @Test fun `Послание Петра resolves to 1 Peter`() {
        val r = run("Послание Петра, 1 глава, 3 стих.").single()
        assertEquals(Triple(60, 1, 3), r.triple())
    }

    @Test fun `Второе Петра word-ordinal selects 2 Peter`() {
        val r = run("Второе Петра, 1 глава, 3 стих.").single()
        assertEquals(Triple(61, 1, 3), r.triple())
    }

    @Test fun `gospel of John is preserved without an epistle marker`() {
        // "Евангелие от Иоанна, 3 глава, 16 стих" must stay the Gospel (43), not become an epistle.
        val r = run("Евангелие от Иоанна, 3 глава, 16 стих.").single()
        assertEquals(Triple(43, 3, 16), r.triple())
    }

    @Test fun `bare Иоанна with no marker stays the gospel`() {
        val r = run("Иоанна, 3 глава, 16 стих.").single()
        assertEquals(Triple(43, 3, 16), r.triple())
    }

    @Test fun `engine canonical names confirm the 1 John mapping (study §1 ground truth)`() {
        // Closes the CP-side loop: the engine emits canonical id 62 and 62 is "1 John" in canonical
        // order, so CP's positional map (index = id - 1 = 61) lands on 1 John.
        assertEquals("1 John", BookResolver.canonicalName(62))
        assertEquals("James", BookResolver.canonicalName(59))
        assertEquals("2 Corinthians", BookResolver.canonicalName(47))
    }

    // ── New true-positives folded in from service3 (2026-06-25, new STT schema) ─────────────────

    @Test fun `первое послание Иоанна word-ordinal plus marker resolves to 1 John (f3#3)`() {
        // Real service3 row 3: "первое послание Иоанна, 4 глава, 3 стиха" → 1 John 4:3 (62), not John (43).
        val r = run("первое послание Иоанна, 4 глава, 3 стиха.").single()
        assertEquals(Triple(62, 4, 3), r.triple())
        assertEquals(1, r.tier)
    }

    @Test fun `послание Иакова вторая глава с N по M range (f3#11)`() {
        // Real service3 row 11: word-ordinal chapter «вторая» + «с 19 по 22» range → James 2:19-22.
        val r = run("Это послание Иакова, вторая глава, с 19 по 22 стих.").single()
        assertEquals(Triple(59, 2, 19), r.triple())
        assertEquals(22, r.verseEnd)
    }

    @Test fun `Матфея 10 with глава N стих tail (f3#36)`() {
        // Real service3 row 36: "Матфея 10, глава 32 стих." → Matthew 10:32.
        val r = run("Матфея 10, глава 32 стих.")
        assertTrue(r.any { it.triple() == Triple(40, 10, 32) }, "expected Matthew 10:32, got $r")
    }

    @Test fun `counting ordinal next to a book name does not fabricate an epistle (f3#52, #56)`() {
        // Real service3 rows 52/56: "первое условие … Иоанн" — «первое» counts a condition, not a
        // book; with no «послание» marker and no глава/стих number it must NOT become 1 John / John 1.
        assertNoEmit("Вот понимаете, то есть первое условие здесь Иоанн ставит для того, чтобы мы возрастали в вере.")
    }

    // ── Music precision gate ─────────────────────────────────────────────────────

    @Test fun `music segment is suppressed and does not seed sticky`() {
        val sticky = TestSticky()
        // A reference-shaped utterance that WOULD emit, but tagged as music → nothing, and it must
        // not seed the sticky context.
        val sung = ReferenceWatcher.process(
            "Матфея 7 глава 21 стих.", sticky, now = 1_000L, isMusic = true,
        )
        assertTrue(sung.isEmpty(), "music segment must not emit, got $sung")
        assertEquals(null, sticky.watchBook, "music must not seed the sticky book")

        // Same text as normal speech does emit — proves the gate, not the parser, suppressed it.
        val spoken = ReferenceWatcher.process("Матфея 7 глава 21 стих.", TestSticky(), now = 1_000L)
        assertTrue(spoken.any { it.triple() == Triple(40, 7, 21) },
            "spoken reference should emit Matthew 7:21, got $spoken")
    }

    @Test fun `music gate can be disabled via Config`() {
        try {
            Config.suppressDuringMusic = false
            val r = ReferenceWatcher.process("Матфея 7 глава 21 стих.", TestSticky(), now = 1_000L, isMusic = true)
            assertTrue(r.any { it.triple() == Triple(40, 7, 21) }, "disabled gate should let it through, got $r")
        } finally {
            Config.suppressDuringMusic = true
        }
    }

    // ── Aggressiveness-gated recall: off at lower levels, on at the intended level ─

    @Test fun `normalizeStt gates э to е book resolution (f1#5)`() {
        val line = "Послание к эфесянам, 6 глава, начинается следующими словами."
        try {
            Config.applyLevel("conservative")
            assertTrue(run(line).isEmpty(), "conservative must not normalize э→е, got ${run(line)}")
            Config.applyLevel("balanced")
            assertTrue(run(line).any { it.bookNum == 49 && it.chapter == 6 },
                "balanced should resolve Ephesians 6, got ${run(line)}")
            Config.applyLevel("aggressive")
            assertTrue(run(line).any { it.bookNum == 49 && it.chapter == 6 },
                "aggressive should resolve Ephesians 6, got ${run(line)}")
        } finally {
            Config.applyLevel("balanced")
        }
    }

    @Test fun `inferBookAtEnd gates book named after its numbers`() {
        val line = "14 стих 3 главы Матфея."
        try {
            Config.applyLevel("balanced")
            assertTrue(run(line).none { it.triple() == Triple(40, 3, 14) },
                "balanced should not infer a trailing book, got ${run(line)}")
            Config.applyLevel("aggressive")
            assertTrue(run(line).any { it.triple() == Triple(40, 3, 14) },
                "aggressive should infer Matthew 3:14, got ${run(line)}")
        } finally {
            Config.applyLevel("balanced")
        }
    }

    @Test fun `gated recall does not regress precision negatives at any level`() {
        val negatives = listOf(
            "где звучали стихотворения.",
            "Это самое главное в нашей жизни.",
            "глава семьи",
            "читать стихи.",
            "И хочу прочитать один стих.",
        )
        try {
            for (level in listOf("conservative", "balanced", "aggressive")) {
                Config.applyLevel(level)
                for (n in negatives) {
                    assertTrue(run(n).isEmpty(), "level=$level should not emit on \"$n\", got ${run(n)}")
                    assertTrue(withSticky(n).isEmpty(),
                        "level=$level should not bind \"$n\" to sticky, got ${withSticky(n)}")
                }
            }
        } finally {
            Config.applyLevel("balanced")
        }
    }
}
