package engine

import engine.detection.BookResolver
import engine.detection.ReferenceWatcher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test fun `book plus chapter with no verse yet does not emit, but still primes the sticky`() {
        // The speaker announces the book+chapter, then goes on a tangent before reading the verse —
        // nothing should appear on screen (no fabricated "verse 1"), but the sticky context must
        // still be primed so the eventual bare verse resolves against it.
        val sticky = TestSticky()
        val announced = ReferenceWatcher.process("1 Коринфянам, 11 глава.", sticky, now = 1_000L)
        assertTrue(announced.isEmpty(), "book+chapter with no verse must not emit, got $announced")
        assertEquals(46, sticky.watchBook)
        assertEquals(11, sticky.watchChapter)

        val afterTangent = ReferenceWatcher.process("23 стих.", sticky, now = 1_000L)
        assertTrue(afterTangent.any { it.triple() == Triple(46, 11, 23) },
            "expected 1 Corinthians 11:23 once the verse is read, got $afterTangent")
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

    // ── Precision negatives: стих*/глав* look-alikes ────────────────────────────
    // Look-alike words and bare verse/chapter words with no real number must NOT emit,
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

    @Test fun `стихотворение look-alike does not emit`() {
        assertNoEmit("где звучали стихотворения, где пелись пения.")
        assertNoEmit("во славу Твоей молитвы Пение, стихотворение слово Твое.")
        assertNoEmit("за все стихотворения, которые были сказаны.")
    }

    @Test fun `главное look-alike does not emit`() {
        assertNoEmit("Это самое главное в нашей жизни.")
    }

    @Test fun `глава семьи with no real number does not emit`() {
        // "семьи" collides with the stem for 7 (семь); it must be rejected as a non-number.
        assertNoEmit("глава семьи всегда готовым от сердца жертвовать собой.")
        assertNoEmit("глава семьи")
    }

    @Test fun `bare plural стихи with no number does not emit`() {
        assertNoEmit("читать стихи, рисовать красиво или еще чего-то.")
    }

    @Test fun `bare один стих and этот стих do not emit`() {
        assertNoEmit("И хочу прочитать один стих.")
        assertNoEmit("этот стих для меня был чужой, непонятный.")
    }

    // ── Epistle (ordinal) disambiguation — 1/2/3 John, 1/2 Peter (2026-06-25 study §2) ──────────

    @Test fun `Послание Иоанна resolves to the epistle not the gospel`() {
        // "1 Послание Иоанна, 4 глава, 3 стиха" → 1 John 4:3 (62), NOT John 4:3 (43).
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

    // ── Numbered-book (ordinal) disambiguation beyond John/Peter ────────────────

    @Test fun `Первая книга царств resolves to 1 Samuel, not left unresolved`() {
        // The real trigger from the 2026-07-05 session log: spelled ordinal + "книга" + stem, with
        // no digit anywhere — the abbreviated "1-я царств" alias never fires here.
        val r = run("Первая книга царств, 15 глава, с 22 по 30 стих.").single()
        assertEquals(Triple(9, 15, 22), r.triple())
        assertEquals(30, r.verseEnd)
    }

    @Test fun `Первое Коринфянам resolves to 1 Corinthians with no книга filler`() {
        val r = run("Первое Коринфянам, 11 глава, 23 стих.").single()
        assertEquals(Triple(46, 11, 23), r.triple())
    }

    @Test fun `Третья царств resolves to 1 Kings (Synodal 3rd Kingdoms) and primes the sticky`() {
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process("Третья царств, 4 глава.", sticky, now = 1_000L)
        assertTrue(refs.isEmpty(), "book+chapter with no verse must not emit, got $refs")
        assertEquals(11, sticky.watchBook)
        assertEquals(4, sticky.watchChapter)
    }

    @Test fun `book, chapter, and verse each resolve independently across separate utterances`() {
        // Real speech rarely announces a full reference as one clean phrase — book, chapter, and
        // verse are usually spoken as separate utterances, often with a tangent in between. Neither
        // of the first two utterances should show anything on screen (no book/chapter alone, and no
        // fabricated verse 1 for the chapter-only continuation) — only the third, once a real verse
        // is finally spoken, should resolve.
        val sticky = TestSticky()
        val bookOnly = ReferenceWatcher.process("Первая книга царств.", sticky, now = 1_000L)
        assertTrue(bookOnly.isEmpty(), "book alone must not emit, got $bookOnly")
        assertEquals(9, sticky.watchBook)

        val chapterOnly = ReferenceWatcher.process("15 глава.", sticky, now = 1_000L)
        assertTrue(chapterOnly.isEmpty(), "chapter-only continuation must not emit a fabricated verse, got $chapterOnly")
        assertEquals(15, sticky.watchChapter)

        val verseOnly = ReferenceWatcher.process("22 стих.", sticky, now = 1_000L)
        assertTrue(verseOnly.any { it.triple() == Triple(9, 15, 22) },
            "expected 1 Samuel 15:22 once the verse is finally spoken, got $verseOnly")
    }

    @Test fun `bare numbered book with a marker but no ordinal stays unresolved`() {
        // Unlike John/Peter, «книга царств» / bare «Коринфянам» has no default "means the 1st"
        // convention — resolving here would guess wrong as often as right, so it must stay silent
        // (the existing, accepted "bare ambiguous numbered book" gap). Checking the sticky directly
        // (not just refs.isEmpty()) matters: book+chapter-with-no-verse never emits a Ref regardless
        // of which book resolved (see the "primes the sticky" tests above), so an empty refs list
        // alone wouldn't catch a regression where "царств"/"коринфянам" wrongly resolved anyway.
        val sticky1 = TestSticky()
        val refs1 = ReferenceWatcher.process("Книга царств, 15 глава.", sticky1, now = 1_000L)
        assertTrue(refs1.isEmpty())
        assertEquals(null, sticky1.watchBook, "bare «книга царств» must stay unresolved, not default to 1 Samuel")

        val sticky2 = TestSticky()
        val refs2 = ReferenceWatcher.process("Коринфянам, 11 глава.", sticky2, now = 1_000L)
        assertTrue(refs2.isEmpty())
        assertEquals(null, sticky2.watchBook, "bare «Коринфянам» must stay unresolved, not default to 1 Corinthians")
    }

    // ── Same-book re-mention must not clobber a just-set sticky chapter (2026-07-05 study) ────

    @Test fun `trailing book mention after its own chapter number does not wipe the chapter just set`() {
        // Real trace (sticky-log 2026-07-05_172751.jsonl, ts 22:19:16.351Z): sticky already watching
        // Revelation from earlier reading. RU's common "N глава [Книги-genitive]" word order names
        // the chapter BEFORE the trailing book mention — the book atom's own end-of-segment flush
        // (curBook=66, chapter=null) must not undo the chapter the same call's earlier flush set.
        val sticky = TestSticky()
        sticky.watchBook = 66
        sticky.watchChapter = 20
        val refs = ReferenceWatcher.process("Мы читаем об этом в 21 главе Откровения.", sticky, now = 1_000L)
        assertTrue(refs.isEmpty(), "book+chapter with no verse must not emit, got $refs")
        assertEquals(66, sticky.watchBook)
        assertEquals(21, sticky.watchChapter, "trailing same-book re-mention must not wipe the chapter just set")
    }

    @Test fun `bilingual track's own book re-mention does not wipe a chapter set earlier in the same call`() {
        // Reconstructed from sticky-log (ts 2026-07-05T22:58:48.089Z: prevChapter=12, newChapter=null)
        // using the exact quoted RU/EN fragments: the RU portion sets book=1/chapter=12/verse=5; the
        // EN translation names "Genesis" again later (forcing an internal flush that correctly emits
        // 1:12:5), then continues with English's keyword-before-number order ("in chapter 12", "in
        // verse 5") whose "in" fillers wipe the buffered numbers before the segment-end flush — which
        // must not null out chapter 12.
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process(
            "И вот в книге Бытия, в 12 главе, в 5 стихе.",
            "And here in the book of Genesis, in chapter 12, in verse 5, there's a passage.",
            sticky, now = 1_000L,
        )
        assertTrue(refs.any { it.triple() == Triple(1, 12, 5) }, "expected Genesis 1 12:5 to have been emitted, got $refs")
        assertEquals(1, sticky.watchBook)
        assertEquals(12, sticky.watchChapter, "EN track's own re-mention of the same book must not wipe chapter 12")
    }

    @Test fun `genuinely different book still resets a stale carried chapter`() {
        // Guards the original behavior the buggy branch was defending: a real book change must still
        // drop a stale chapter rather than let it bind to the new book.
        val sticky = TestSticky()
        sticky.watchBook = 27
        sticky.watchChapter = 6
        val refs = ReferenceWatcher.process("Послание к римлянам.", sticky, now = 1_000L)
        assertTrue(refs.isEmpty())
        assertEquals(45, sticky.watchBook, "book must switch to Romans")
        assertEquals(null, sticky.watchChapter, "a genuinely new book must still drop the stale chapter")
    }

    // ── Ambiguous common-word RU aliases need corroborating context (2026-07-05 study) ─────────

    @Test fun `bare dative Иоанну narrating the apostle does not hijack the sticky book`() {
        // Real trace (sticky-log ts 2026-07-05T22:19:12.320Z): 66→43 false flip while narrating the
        // apostle by name ("...and to John, God reveals..."), not citing the Gospel.
        val sticky = TestSticky()
        sticky.watchBook = 66
        sticky.watchChapter = 21
        ReferenceWatcher.process("И вот Иоанну Бог открывает", sticky, now = 1_000L)
        assertEquals(66, sticky.watchBook, "narrating the apostle by name must not hijack the sticky book to John")
        assertEquals(21, sticky.watchChapter)
    }

    @Test fun `bare dative Иоанну in unrelated narration does not hijack the sticky book`() {
        // Real trace (sticky-log ts 2026-07-05T22:20:14.280Z): 25→43 false flip, full real sentence.
        val sticky = TestSticky()
        sticky.watchBook = 25
        sticky.watchChapter = 3
        ReferenceWatcher.process(
            "Смотрите, вот такой интересный эпизод был предоставлен Иоанну.", sticky, now = 1_000L,
        )
        assertEquals(25, sticky.watchBook)
        assertEquals(3, sticky.watchChapter)
    }

    @Test fun `бытие as ordinary vocabulary does not hijack the sticky book`() {
        // Real trace (sticky-log ts 2026-07-05T22:21:28.143Z): 43→1 false flip; "бытие" here means
        // "being/existence" (a machine-translation tail), not a citation of Genesis.
        val sticky = TestSticky()
        sticky.watchBook = 43
        sticky.watchChapter = 3
        ReferenceWatcher.process("вот-вот вступаем в то бытие.", sticky, now = 1_000L)
        assertEquals(43, sticky.watchBook, "ordinary vocabulary \"бытие\" must not hijack the sticky book to Genesis")
        assertEquals(3, sticky.watchChapter)
    }

    @Test fun `digit-adjacent бытие still resolves as Genesis`() {
        val r = run("Бытие, 1 глава, 1 стих.").single()
        assertEquals(Triple(1, 1, 1), r.triple())
    }

    @Test fun `digit-adjacent dative Иоанну still resolves when corroborated`() {
        val r = run("Иоанну, 3 глава, 16 стих.").single()
        assertEquals(Triple(43, 3, 16), r.triple())
    }

    @Test fun `marker-adjacent быт abbreviation still resolves as Genesis`() {
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process("Книга Быт, 3 глава.", sticky, now = 1_000L)
        assertTrue(refs.isEmpty())
        assertEquals(1, sticky.watchBook, "«книга» marker should corroborate the abbreviation «Быт»")
    }

    // ── Mechanism-level generalization (2026-07-05 study §3) — test the underlying rule across
    // many books/words instead of only the specific transcripts that first exposed it, so the next
    // word/book that falls into the same trap is caught before a live session hits it. ──────────

    @Test fun `same-book reflush preserves chapter across a range of books (general invariant)`() {
        // Not just Revelation/Genesis (the two real traces) — the same "book named again, trailing
        // its own chapter number, with no chapter of its own" shape must hold for any book.
        val cases = listOf(
            Triple(66, "Откровения", 21),
            Triple(1, "Бытия", 12),
            Triple(45, "Римлянам", 8),
            Triple(40, "Матфея", 5),
        )
        for ((book, genitive, chapter) in cases) {
            val sticky = TestSticky()
            sticky.watchBook = book
            sticky.watchChapter = 1 // a different "old" chapter, distinct from the one being set
            val refs = ReferenceWatcher.process("Мы читаем об этом в $chapter главе $genitive.", sticky, now = 1_000L)
            assertTrue(refs.isEmpty(), "book+chapter with no verse must not emit for book $book, got $refs")
            assertEquals(book, sticky.watchBook, "book should stay $book")
            assertEquals(chapter, sticky.watchChapter,
                "chapter $chapter must survive the trailing same-book re-mention for book $book")
        }
    }

    @Test fun `every recorded same-book chapter-clear from the 2026-07-05 service stays fixed`() {
        // The four CHAPTER-CLEARED rows in sticky-log-2026-07-05_172751.jsonl, replayed as their real
        // trigger text. That log is a frozen artifact of the pre-fix engine, so re-running the auditor
        // over it can never show the fix working — these assertions are what actually prove it, and
        // they cover shapes the invariant above does not: a book re-mentioned with no number at all
        // ("...и вот в книге Бытие."), and one re-mentioned twice in one breath before its chapter.
        val cases = listOf(
            Triple(42, 9, "Если вы читаете Евангелие от Луки, откройте Евангелие от Луки на 9 главе. 9 глава, 51 стих."),
            Triple(20, 3, "эту землю, для того, чтобы совершить спасение всего рода человеческого. И в притчах мы читаем 3-й лад."),
            Triple(1, 12, "А пока это всего лишь Авраам, которому Бог повелел пойти в землю, которую Он укажет ему. И вот в книге Бытие."),
            Triple(66, 21, "Да, мы читаем, мы верим в божественные истины, в Слове Божьем многое, что написано. Но, тем не менее, в Откровении."),
        )
        for ((book, chapter, text) in cases) {
            val sticky = TestSticky()
            sticky.watchBook = book
            sticky.watchChapter = chapter
            ReferenceWatcher.process(text, sticky, now = 1_000L)
            assertEquals(book, sticky.watchBook, "book $book must survive: \"$text\"")
            assertEquals(chapter, sticky.watchChapter, "chapter $chapter must survive: \"$text\"")
        }
    }

    @Test fun `ambiguous alias table never hijacks the sticky book without corroborating context (fuzz)`() {
        // Iterates ReferenceWatcher.AMBIGUOUS_BOOK_FORMS itself rather than hardcoding "иоанну"/
        // "бытие" — any future word added to that table is automatically covered by this test with
        // no extra work. Fixed seed for reproducibility; fillers deliberately contain no digits, no
        // epistle markers, and no book names/aliases, so corroboration can never trigger by accident.
        val fillers = listOf(
            "сегодня", "хорошо", "конечно", "братья", "сестры", "давайте", "подумаем", "вместе",
            "немного", "время", "место", "слово", "жизнь", "сердце", "истина", "путь", "человек",
            "земля", "небо", "вода",
        )
        val rnd = Random(20260705)
        for ((word, book) in ReferenceWatcher.AMBIGUOUS_BOOK_FORMS) {
            repeat(50) {
                val before = List(rnd.nextInt(4)) { fillers[rnd.nextInt(fillers.size)] }
                val after = List(rnd.nextInt(4)) { fillers[rnd.nextInt(fillers.size)] }
                val sentence = (before + word + after).joinToString(" ") + "."
                val sticky = TestSticky()
                sticky.watchBook = 45 // Romans — distinct from every AMBIGUOUS_BOOK_FORMS target book
                sticky.watchChapter = 3
                ReferenceWatcher.process(sentence, sticky, now = 1_000L)
                assertEquals(45, sticky.watchBook,
                    "bare \"$word\" with no corroboration must not hijack the sticky to book $book: \"$sentence\"")
            }
        }
    }

    @Test fun `ambiguous alias table resolves once corroborated by a nearby digit (fuzz)`() {
        // Proves the corroboration gate isn't just permanently closed — same table, same fillers,
        // this time with an adjacent chapter number.
        val fillers = listOf("сегодня", "хорошо", "конечно", "братья", "сестры", "давайте")
        val rnd = Random(20260705)
        for ((word, book) in ReferenceWatcher.AMBIGUOUS_BOOK_FORMS) {
            repeat(20) {
                val before = List(rnd.nextInt(3)) { fillers[rnd.nextInt(fillers.size)] }
                val chapterNum = rnd.nextInt(1, 20)
                val sentence = (before + word + chapterNum.toString() + "глава").joinToString(" ") + "."
                val sticky = TestSticky()
                val refs = ReferenceWatcher.process(sentence, sticky, now = 1_000L)
                assertTrue(refs.isEmpty(), "book+chapter alone must not emit, got $refs for \"$sentence\"")
                assertEquals(book, sticky.watchBook, "digit-adjacent \"$word\" should resolve to book $book: \"$sentence\"")
            }
        }
    }

    // ── Inflected ambiguous words (2026-07-12 session, AMBIGUOUS_BOOK_STEMS) ────

    @Test fun `genitive бытия as ordinary vocabulary does not clear sticky chapter mid-passage (real session trace)`() {
        // Real trace (sticky-log-2026-07-12_173830.jsonl line 19, ts 2026-07-12T22:58:39.822Z):
        // reading through an English exposition of Psalm 14 with live Russian translation, "уровне
        // своего бытия" (translation of "...level of his being") falsely resolved as Genesis (book
        // 1), sandwiched between two genuine "Psalm 14"/"Псалма 14" mentions in the same combined
        // transcript+translation call — nulling the sticky chapter mid-sermon.
        val sticky = TestSticky()
        sticky.watchBook = 19
        sticky.watchChapter = 14
        ReferenceWatcher.process(
            "He may say, hallelujah, God is alive, yet in his heart, you know, at the deepest, " +
                "most intimate level of his being, he may confess a different kind of faith, in " +
                "inverted commas. his heart and with his life, he will repeat the words of the " +
                "fool from Psalm 14. 14, it is unlikely that the words, there's no God, spoken by " +
                "a man of God,",
            "Он может сказать, аллелюя, Бог жив, но в своем сердце, вы знаете, на самом глубоком, " +
                "самом интимном уровне своего бытия, он может исповедовать другой вид веры, в " +
                "обернутых кометах. своим сердцем и своей жизнью, он повторит слова дурака из " +
                "Псалма 14. 14, it is unlikely that the words, there's no God, spoken by a man of God,",
            sticky, now = 1_000L,
        )
        assertEquals(19, sticky.watchBook)
        assertEquals(14, sticky.watchChapter,
            "genitive «бытия» must not falsely resolve to Genesis and clear the sticky chapter")
    }

    @Test fun `digit-adjacent genitive бытия still resolves as Genesis`() {
        val r = run("Бытия, 1 глава, 1 стих.").single()
        assertEquals(Triple(1, 1, 1), r.triple())
    }

    // ── English short aliases on the translation track (2026-07-24) ────────────────
    //
    // The ≤4-char gate is keyed on the exact token, so an English plural or possessive walks straight
    // past it — "song" is gated, "songs" is not. And a Bible *version* name carries a book name
    // inside it. Both shapes are from real translation-track traces across the archive.

    @Test fun `plural singing vocabulary does not become Song of Solomon (real session traces)`() {
        for (text in listOf(
            "then we'll have a party. Two songs.",                      // 2026-07-12_092012 14:31:00Z
            "We can sing the songs you've been singing.",               // 2026-07-12_173830 22:41:44Z
            "hymns, songs, the Word of God being preached.",            // 2026-07-19_183945 22:45:56Z
        )) {
            val sticky = TestSticky()
            ReferenceWatcher.process(text, sticky, now = 1_000L)
            assertNull(sticky.watchBook, "ordinary singing vocabulary must not prime a book: \"$text\"")
        }
    }

    @Test fun `a possessive of a short alias is gated like the singular`() {
        // 2026-07-12_092012 14:20:57Z — "Job's first trial did not involve him physically."
        val sticky = TestSticky()
        ReferenceWatcher.process("Notice that Job's first trial did not involve him physically.", sticky, now = 1_000L)
        assertNull(sticky.watchBook)
    }

    @Test fun `a bible version name is not a citation (real session trace)`() {
        // 2026-07-12_173830 22:54:59Z — reading Psalm 14, the translator says which version is being
        // used and the sticky book flipped to James for the rest of the passage.
        val sticky = TestSticky()
        ReferenceWatcher.process(
            "But let us read the first verses of Psalm 14, and I'm going to use New King James version.",
            sticky, now = 1_000L,
        )
        assertEquals(19, sticky.watchBook, "the citation is Psalm 14; \"King James\" names a version")
        // (The chapter does not survive here: prose after a bare number drops it by design — see the
        // colon-only exception in interpret's Filler branch. The book is what mattered for this bug.)
    }

    @Test fun `genuine English citations still resolve`() {
        // The guard for all three gates above — these are the shapes that must keep working.
        assertEquals(42, run("recorded in the Gospel of Luke, chapter 21, verse 28.").single().bookNum)
        assertEquals(Triple(42, 11, 24), run("Luke, chapter 11, verses 24 through 26.").single().triple())
        val psalms = TestSticky()
        ReferenceWatcher.process("this phrase is also found twice in the book of Psalms.", psalms, now = 1_000L)
        assertEquals(19, psalms.watchBook, "an explicit \"book of Psalms\" marker is a real citation")
        assertEquals(Triple(59, 1, 5), run("James, chapter 1, verse 5.").single().triple())
    }

    // ── SPB-registered short aliases that are their own stem (2026-07-19 / 2026-07-24) ─────
    //
    // BookResolver.register() folds every loaded SPB module's book names into the alias table at
    // startup, so what the engine resolves live is wider than the static table these tests otherwise
    // see. The Russian Synodal module names book 65 "Иуда" and book 28 "Осия" — both ordinary words
    // (Judas the man; a "stubbornness"-adjacent noun) and both ≤4 chars, i.e. exactly the shape the
    // corroboration gate exists for. [withRegisteredBookNames] reproduces that startup state.

    private fun withRegisteredBookNames(vararg names: Pair<Int, String>, body: () -> Unit) {
        BookResolver.register(names.toList())
        try {
            body()
        } finally {
            BookResolver.register(emptyList())
        }
    }

    @Test fun `Иуда naming the betrayer does not hijack the sticky book (real session trace)`() {
        // Real trace (sticky-log-2026-07-19_183945.jsonl, ts 2026-07-19T22:47:04.194Z): a sermon
        // illustration about Judas Iscariot ("Не больно ему было, когда Иуда...") flipped the sticky
        // book from Song of Solomon to Jude, with nothing emitted and no citation anywhere in either
        // track. The exact-alias branch correctly refused bare "иуда"; the stem fallback then matched
        // the same token at extension 0 and admitted it with no corroboration at all.
        withRegisteredBookNames(65 to "Иуда") {
            val sticky = TestSticky()
            sticky.watchBook = 22
            sticky.watchChapter = 2
            ReferenceWatcher.process(
                "Мы говорим, друзья нас не поняли, не поддержали в трудную минуту И более того, " +
                    "позорно нас предали, отбросив благочестие, атрибуты. а как Христу. Не больно " +
                    "ему было, когда Иуда...",
                "It's called Look At Christ. Sometimes, when we're being persecuted or arrested, " +
                    "we say, oh, fate is mine, but what if we had a Golgotha cross and a bowl of " +
                    "bitter-burning pussy? Мы говорим, друзья нас не поняли, не поддержали в " +
                    "трудную минуту И более того, позорно нас предали, отбросив благочестие, " +
                    "атрибуты.",
                sticky, now = 1_000L,
            )
            assertEquals(22, sticky.watchBook, "narrating Judas must not make Jude the sticky book")
            assertEquals(2, sticky.watchChapter)
        }
    }

    @Test fun `осия as ordinary vocabulary does not hijack the sticky book (real session trace)`() {
        // The same mechanism, found first: sticky-log-2026-07-12_173830 flipped to Hosea and held it
        // as the wrong sticky book for ~40 minutes, taking a cluster of Psalm 14 references down with
        // it (TRAINING_PLAN's "short-alias corroboration too loose" gap).
        withRegisteredBookNames(28 to "Осия") {
            val sticky = TestSticky()
            sticky.watchBook = 19
            sticky.watchChapter = 14
            ReferenceWatcher.process("Вот в этом и была его осия, его упрямство.", sticky, now = 1_000L)
            assertEquals(19, sticky.watchBook)
            assertEquals(14, sticky.watchChapter)
        }
    }

    @Test fun `a from-the-first-verse opener is a verse, not a chapter (real session trace)`() {
        // Real trace (2026-07-22_183657, segment 635's accumulated translation): the operator's
        // Russian "С первого стиха" is machine-translated to "From the first verse", which carries no
        // chapter number at all. The 2026-07-12 "Book N, verse M" branch fired anyway — a book had
        // been named earlier in the same accumulated text — and took the VERSE ordinal as the chapter,
        // priming the sticky to Psalm 1 while the congregation was in Psalm 23. It then emitted
        // 19:1:1 as a tier-2 continuation, which auto-goes-live.
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process(
            "Это будет 23-й Псалом. Псалом 23. С первого стиха. Псалом Давида. " +
                "Господня земля и что наполняет ее.",
            "It will be Psalm 23. The 23rd Psalm. From the first verse. A song of David. " +
                "To Jehovah belongs the earth and all that is in it.",
            sticky, now = 1_000L,
        )
        assertEquals(23, sticky.watchChapter, "\"from the first verse\" names a verse, not chapter 1")
        assertTrue(refs.none { it.chapter == 1 }, "nothing in Psalm 1 was cited: $refs")
    }

    @Test fun `с первого стиха after a chapter announcement still reads as verse 1`() {
        val r = run("Псалом 23. С первого стиха.")
        assertTrue(r.any { it.triple() == Triple(19, 23, 1) }, "expected Psalm 23:1, got $r")
    }

    @Test fun `an epistle named before its ordinal resolves to the epistle, not the gospel`() {
        // Real trace (2026-07-22_183657.db row 928): "Иоанн говорит в первом послании, в первой главе
        // шестом стихе" — the book is named FIRST and the ordinal+marker follow it. resolveNumberedBookAt
        // only looks backwards ("первое послание Иоанна"), so this resolved to the Gospel and shipped as
        // an explicit tier-1, i.e. straight to the screen. The reverse lookup found the real verse
        // (1 John 1:6) from the quoted text moments later, which is how the mistake is visible at all.
        val r = run("Иоанн говорит в первом послании, в первой главе шестом стихе,")
        assertTrue(
            r.any { it.triple() == Triple(62, 1, 6) },
            "expected 1 John 1:6 (book 62), got $r",
        )
        assertTrue(r.none { it.bookNum == 43 }, "the Gospel of John must not be emitted here: $r")
    }

    @Test fun `a gospel citation with no epistle marker still resolves to the gospel`() {
        // The guard for the fix above: an ordinary Gospel citation must be untouched.
        assertEquals(Triple(43, 3, 16), run("Евангелие от Иоанна, 3 глава, 16 стих.").single().triple())
        assertEquals(Triple(43, 1, 6), run("Иоанна, 1 глава, 6 стих.").single().triple())
    }

    // ── Verse-range announcement against a live sticky (2026-07-22 session) ────────

    @Test fun `a bare verse range binds to the sticky chapter, not as a chapter itself (real session trace)`() {
        // Real trace (2026-07-22_183657.db, seg 797, ts_ms=1784761797829): re-reading Psalm 24 twenty
        // minutes after the first pass, the speaker announced the span without restating the chapter:
        // "стихи 3-го стиха по 6-й" = verses 3 through 6. The leading "3" was bound as a CHAPTER,
        // emitting 19:3:3 / 19:3:6 against a sticky that already knew chapter 24 — so the operator's
        // go-lives on verses 4, 5 and 6 had nothing to match. Verse keywords surround the number
        // ("стихи … стиха … по …"), so there is no ambiguity about what it is.
        val sticky = TestSticky()
        sticky.watchBook = 19
        sticky.watchChapter = 24
        val refs = ReferenceWatcher.process(
            "Я бы хотел обратить внимание на стихи 3-го стиха по 6-й.", sticky, now = 1_000L,
        )
        assertEquals(24, sticky.watchChapter, "the announcement names verses, so the chapter must survive")
        assertTrue(
            refs.any { it.bookNum == 19 && it.chapter == 24 && it.verseStart == 3 },
            "expected Psalm 24:3 (range to 6), got $refs",
        )
    }

    @Test fun `once a verse is bound, a trailing number never becomes a chapter`() {
        // Mechanism-level: the "bare book N = chapter" fallback must stay switched off for the rest of
        // a reference once a verse keyword has claimed a number, whatever phrasing follows. Each of
        // these announces verses of the sticky chapter, in either track's grammar.
        val phrasings = listOf(
            "стихи 3-го стиха по 6-й",
            "стих 3 по 6",
            "с 3 стиха по 6 стих",
            "verses 3 through 6",
            "verse 3 to 6",
        )
        for (text in phrasings) {
            val sticky = TestSticky()
            sticky.watchBook = 19
            sticky.watchChapter = 24
            val refs = ReferenceWatcher.process("$text.", sticky, now = 1_000L)
            assertEquals(24, sticky.watchChapter, "chapter must survive \"$text\"")
            assertTrue(
                refs.any { it.bookNum == 19 && it.chapter == 24 && it.verseStart == 3 },
                "expected Psalm 24:3 from \"$text\", got $refs",
            )
        }
    }

    @Test fun `a verse number spoken before its keyword is not discarded`() {
        // Russian puts the number first ("Псалом 23, 1 стих"), so at the verse keyword the buffer
        // holds BOTH the chapter and the verse. Taking the first as chapter and dropping the rest
        // silently lost the verse and left a chapter-only reference that never emits.
        assertEquals(Triple(19, 23, 1), run("Псалом 23, 1 стих.").single().triple())
        assertEquals(Triple(43, 3, 16), run("Иоанна 3, 16 стих.").single().triple())
        // The English order (number after the keyword) must keep working — it binds via the pending
        // keyword instead, and is the case the 2026-07-12 fix was written for.
        assertEquals(Triple(19, 10, 13), run("The illusion is reflected in Psalm 10, verse 13.").single().triple())
    }

    @Test fun `a bare book and number still reads as a chapter`() {
        // The other side of the same gate: with no verse bound, "book N" must still mean chapter N —
        // both when the verse follows in the same utterance and when it arrives in a later one.
        assertEquals(Triple(19, 23, 1), run("Псалом 23, 1 стих.").single().triple())
        assertTrue(
            run("Псалом 23.", "5 стих.").any { it.triple() == Triple(19, 23, 5) },
            "the bare book-number must still prime the sticky chapter for a later verse",
        )
    }

    @Test fun `открой in ordinary prose does not hijack the sticky book (real session trace)`() {
        // Real trace (sticky-log-2026-07-19_183945.jsonl, ts 2026-07-19T23:26:00.048Z): a passage
        // about opening a door flipped the sticky from Luke 11 to Revelation. The 2026-07-09
        // over-extension gate does not reach it — "откр" (the Revelation abbreviation alias) is only
        // 4 chars, and Russian verb forms sit 1-2 chars past it: "открой" is +2, under the >=3
        // threshold, so it resolved unconditionally. ("открываем"/"открывает", which stickyAudit
        // named because it reports the first matching token, are +5 and correctly gated already.)
        val sticky = TestSticky()
        sticky.watchBook = 42
        sticky.watchChapter = 11
        ReferenceWatcher.process(
            "Мы впускаем дверь и открываем дверь для Духа Святого тогда, когда Бог говорит нам. " +
                "Вот это хорошая дверь. Тогда как раз, когда грешник нечестивый, вот он должен " +
                "открыть свое сердце, когда мы говорим, чтобы сердце открой.",
            sticky, now = 1_000L,
        )
        assertEquals(42, sticky.watchBook, "opening a door must not make Revelation the sticky book")
        assertEquals(11, sticky.watchChapter)
    }

    @Test fun `spelled-out Откровение still resolves - it matches a longer stem than the abbreviation`() {
        // The gate keys on the short "откр" stem only. Real citations spell the book out and resolve
        // through "откровен", so they stay unconditional.
        val sticky = TestSticky()
        ReferenceWatcher.process("Читаем в Откровении.", sticky, now = 1_000L)
        assertEquals(66, sticky.watchBook)
    }

    @Test fun `the Откр abbreviation still resolves when a chapter is adjacent`() {
        val r = run("Откр 3 глава 20 стих.").single()
        assertEquals(Triple(66, 3, 20), r.triple())
    }

    @Test fun `a cited Иуда with a chapter still resolves as Jude`() {
        withRegisteredBookNames(65 to "Иуда") {
            val r = run("Иуда, 1 глава, 3 стих.").single()
            assertEquals(Triple(65, 1, 3), r.triple())
        }
    }

    @Test fun `a cited послание Иуды still resolves as Jude`() {
        // The marker noun is the corroboration the gate asks for, so the citation survives it.
        val r = run("Послание Иуды, 1 глава, 9 стих.").single()
        assertEquals(Triple(65, 1, 9), r.triple())
    }

    @Test fun `an inflected book name is still unconditional - only the bare short alias is gated`() {
        // The gate must not spread to ordinary inflected citations, which extend past their stem and
        // carry no ambiguity: "Луки"/"Марка" name a Gospel and nothing else.
        withRegisteredBookNames(65 to "Иуда", 28 to "Осия") {
            for ((text, book) in listOf("От Луки читаем." to 42, "Марка читаем сегодня." to 41)) {
                val sticky = TestSticky()
                ReferenceWatcher.process(text, sticky, now = 1_000L)
                assertEquals(book, sticky.watchBook, "inflected name must still prime the sticky: \"$text\"")
            }
        }
    }

    @Test fun `every registered short book name needs corroboration, in both branches`() {
        // Mechanism-level: whatever a module happens to name a book, a bare ≤4-char name that is
        // ordinary vocabulary must never prime the sticky on its own, and the same name with a
        // chapter number must always resolve. Iterating the table means a module registering a new
        // short name is covered without writing another test.
        val shortNames = listOf(65 to "Иуда", 28 to "Осия", 32 to "Иона", 30 to "Амос", 25 to "Плач")
        withRegisteredBookNames(*shortNames.toTypedArray()) {
            for ((book, name) in shortNames) {
                val bare = TestSticky()
                ReferenceWatcher.process("И тогда $name сказал это слово.", bare, now = 1_000L)
                assertNull(bare.watchBook, "bare \"$name\" must not prime the sticky with no citation context")

                val cited = TestSticky()
                val refs = ReferenceWatcher.process("$name, 1 глава, 2 стих.", cited, now = 1_000L)
                assertEquals(book, refs.single().bookNum, "\"$name\" with a chapter must still resolve")
            }
        }
    }

    // ── Bare chapter number before a verse keyword (2026-07-12 session) ────────

    @Test fun `Psalm 10 verse 13 resolves chapter 10 verse 13, not transposed (real session trace)`() {
        // Real trace: DB row ts_ms=1783897645684 (session 2026-07-12_173830), the corrected/final
        // STT text. No chapter keyword for "10" — it must still bind as chapter by the universal
        // bare "book N" convention, not get swept into verseStart by the trailing "verse" keyword.
        val r = run("The illusion is reflected in Psalm 10, verse 13.").single()
        assertEquals(Triple(19, 10, 13), r.triple())
    }

    @Test fun `Psalm 10 verse 30 (STT mis-hearing variant) still resolves chapter 10`() {
        // Real trace: DB row ts_ms=1783897642945, a transient partial where STT misheard "13" as
        // "30" one second before self-correcting. The engine previously parsed this as chapter=30,
        // verse=10 (transposed) — an "explicit" match, which auto-goes-live with no staging net.
        val r = run("The illusion is reflected in Psalm 10, verse 30.").single()
        assertEquals(Triple(19, 10, 30), r.triple())
    }

    @Test fun `bare chapter number before a verse keyword resolves as chapter, not verse (general invariant)`() {
        // Not just Psalm 10 (the real trace) — the same "book, bare number, verse keyword with no
        // chapter keyword" shape must hold across books and both languages.
        val cases = listOf(
            Triple(19, "Псалом 10 стих 13.", Triple(19, 10, 13)),
            Triple(40, "Matthew 5 verse 3.", Triple(40, 5, 3)),
            Triple(45, "Римлянам 8 стих 28.", Triple(45, 8, 28)),
            Triple(43, "John 3 verse 16.", Triple(43, 3, 16)),
        )
        for ((_, text, expected) in cases) {
            val r = run(text).single()
            assertEquals(expected, r.triple(), "expected $expected for \"$text\", got ${r.triple()}")
        }
    }

    // Real non-reference sentences confirmed benign in past sessions. Append newly-confirmed-benign
    // trigger text here as future sessions surface it (in addition to, not instead of, a dedicated
    // named test for any actual fix) — this list is meant to keep growing every session instead of
    // coverage living only in one-off named tests. See TRAINING_PLAN.md Test Strategy.
    private val NEGATIVE_CORPUS = listOf(
        "вот-вот вступаем в то бытие.",
        "Смотрите, вот такой интересный эпизод был предоставлен Иоанну.",
        "Затем вся молодежь выйдет и споет «Когда Христос меня простил».",
        "Как можно курить в переполненном автобусе, где много детей?",
        "Мы поем ее часто в нашей Второй Одесской Церкви.",
        // Documented Known Engine Gaps (TRAINING_PLAN): stem over-extension + short real-word
        // aliases in prose — closed by classify's over-extension/short-alias gates.
        "он удостоил его тому, что открылся народу.",
        "нужно повторить этот отрывок еще раз.",
        "we will sing a song together this morning.",
        "he did a good job on the presentation.",
        "при этом мы видим важную деталь.",
        // 2026-07-12 session: genitive "бытия" as ordinary vocabulary ("...level of his being"),
        // closed by AMBIGUOUS_BOOK_STEMS.
        "на самом глубоком, самом интимном уровне своего бытия, он может исповедовать другой вид веры.",
    )

    @Test fun `growing negative corpus never emits or hijacks an unset sticky book`() {
        for (line in NEGATIVE_CORPUS) {
            val sticky = TestSticky()
            val refs = ReferenceWatcher.process(line, sticky, now = 1_000L)
            assertTrue(refs.isEmpty(), "negative-corpus line should not emit: \"$line\" -> $refs")
            assertEquals(null, sticky.watchBook, "negative-corpus line should not seed an unset sticky book: \"$line\"")
        }
    }

    // ── Bilingual transcript/translation disagreement (mistranslated book) ─────

    @Test fun `transcript book wins over mistranslated translation book (1 Samuel not 1 Kings)`() {
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process(
            "1-я царств, 15 глава, с 22 по 30 стих.",
            "1 Kings, chapter 15, from 22 to 30.",
            sticky, now = 1_000L,
        )
        assertTrue(
            refs.any { it.bookNum == 9 && it.chapter == 15 && it.verseStart == 22 && it.verseEnd == 30 },
            "expected 1 Samuel 15:22-30 from the transcript, got $refs",
        )
        assertEquals(9, sticky.watchBook, "sticky must carry the transcript's book, not the mistranslated 1 Kings")
        assertEquals(15, sticky.watchChapter)

        // A later utterance where the translation is empty/lagging must still continue against the
        // uncorrupted sticky.
        val late = ReferenceWatcher.process("31 стих.", "", sticky, now = 1_000L)
        assertTrue(late.any { it.bookNum == 9 && it.chapter == 15 && it.verseStart == 31 },
            "expected sticky continuation into 1 Samuel 15:31, got $late")
    }

    @Test fun `transcript book wins over mistranslated translation book (Lamentations not Jeremiah)`() {
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process(
            "Книга Плач Иеремии, 3 глава, 21 стих.",
            "The book Lamentations of Jeremiah, 3 chapters, 21 verses.",
            sticky, now = 1_000L,
        )
        assertTrue(refs.any { it.bookNum == 25 && it.chapter == 3 },
            "expected Lamentations 3 from the transcript, got $refs")
        assertEquals(25, sticky.watchBook, "sticky must not be corrupted to Jeremiah (24)")
    }

    @Test fun `translation-only book citation is still picked up when transcript has none`() {
        // Book+chapter only (no verse spoken) never emits a Ref (see the tangent test above) — the
        // signal to check for a translation-only citation is that it still primes the sticky.
        val sticky = TestSticky()
        val refs = ReferenceWatcher.process(
            "и мы читаем",
            "1 Corinthians 11.",
            sticky, now = 1_000L,
        )
        assertTrue(refs.isEmpty(), "book+chapter with no verse must not emit, got $refs")
        assertEquals(46, sticky.watchBook, "translation-only book citation must still prime the sticky")
        assertEquals(11, sticky.watchChapter)

        // A later bare verse (transcript only, translation empty/lagging) resolves against it.
        val late = ReferenceWatcher.process("23 стих.", "", sticky, now = 1_000L)
        assertTrue(late.any { it.bookNum == 46 && it.chapter == 11 && it.verseStart == 23 },
            "expected 1 Corinthians 11:23 via the translation-primed sticky, got $late")
    }

    @Test fun `agreeing tracks still combine normally`() {
        val refs = ReferenceWatcher.process(
            "Послание к римлянам, 6 глава, 4 стих.",
            "Romans chapter 6 verse 4.",
            TestSticky(), now = 1_000L,
        )
        assertTrue(refs.any { it.bookNum == 45 && it.chapter == 6 && it.verseStart == 4 },
            "expected Romans 6:4 when both tracks agree, got $refs")
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

    @Test fun `normalizeStt gates э to е book resolution`() {
        // Book + chapter only (no verse) never emits a Ref, so check the sticky it primes instead.
        val line = "Послание к эфесянам, 6 глава, начинается следующими словами."
        fun stickyBookChapter(): Pair<Int?, Int?> {
            val sticky = TestSticky()
            ReferenceWatcher.process(line, sticky, now = 1_000L)
            return sticky.watchBook to sticky.watchChapter
        }
        try {
            Config.applyLevel("conservative")
            assertEquals(null to null, stickyBookChapter(), "conservative must not normalize э→е")
            Config.applyLevel("balanced")
            assertEquals(49 to 6, stickyBookChapter(), "balanced should resolve Ephesians 6")
            Config.applyLevel("aggressive")
            assertEquals(49 to 6, stickyBookChapter(), "aggressive should resolve Ephesians 6")
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


    // ── FP gates: stem over-extension + short aliases (Known Engine Gaps, closed 2026-07) ──────

    @Test fun `short alias with adjacent number still resolves`() {
        // Gate B must not cost recall when the citation carries its number nearby.
        // (EN keyword-after-number forms like "Job chapter 3 verse 2" still mis-parse for a
        // pre-existing, unrelated reason — see the keyword-order gap note in TRAINING_PLAN.md.)
        val jobRefs = run("Job 3:2 tells us more.")
        assertTrue(jobRefs.any { it.bookNum == 18 && it.chapter == 3 && it.verseStart == 2 }, "Job 3:2 should resolve, got $jobRefs")
        val revRefs = run("откр 3 глава 12 стих.")
        assertTrue(revRefs.any { it.bookNum == 66 && it.chapter == 3 }, "Rev 3:12 should resolve, got $revRefs")
    }

    @Test fun `multi-word short-book aliases are not gated`() {
        val refs = run("Song of Solomon 2:1")
        assertTrue(refs.any { it.bookNum == 22 && it.chapter == 2 }, "Song of Solomon 2:1 should resolve, got $refs")
    }

    @Test fun `normal grammatical stem endings are not gated`() {
        // Extension of 1-2 chars over the stem (ordinary inflection) stays ungated.
        val refs = run("Матфея 5 глава 3 стих.")
        assertTrue(refs.any { it.bookNum == 40 && it.chapter == 5 }, "Matthew 5:3 should resolve, got $refs")
    }

    @Test fun `over-extended stems in prose never hijack the sticky`() {
        // "открылся"/"повторить" share prefixes with book stems; bare in prose they must
        // neither emit nor move the sticky book (the sticky-pollution path behind many
        // chapter-history FPs in the recorded 2026-07-08 session).
        val sticky = TestSticky()
        ReferenceWatcher.process("Послание к римлянам, 6 глава, 4 стих.", sticky, 1_000L)
        val bookBefore = sticky.watchBook
        ReferenceWatcher.process("он удостоил его тому, что открылся народу.", sticky, 2_000L)
        ReferenceWatcher.process("нужно повторить этот отрывок.", sticky, 3_000L)
        assertEquals(bookBefore, sticky.watchBook, "prose stem look-alikes must not move the sticky book")
    }

    @Test fun `short aliases in bare prose never emit or hijack - fuzz`() {
        // Mechanism-level guard over every registered single-token alias of length <= 4:
        // bare in prose (no digits, no markers) it must not emit; framing it as a citation
        // with a number must still resolve to SOME book (recall check on a sample).
        val shortAliases = BookResolver.ALIASES.keys.filter { !it.contains(' ') && it.length in 3..4 }
        assertTrue(shortAliases.isNotEmpty())
        for (alias in shortAliases) {
            val refs = run("we were talking about $alias yesterday evening.")
            assertTrue(refs.isEmpty(), "bare short alias '$alias' emitted $refs")
        }
        for (alias in shortAliases.shuffled(kotlin.random.Random(20260709)).take(10)) {
            val refs = run("$alias 3 глава 2 стих.")
            assertTrue(refs.isNotEmpty(), "corroborated short alias '$alias' should resolve")
        }
    }

    // ── Colon-bound citations survive trailing prose (found via DB row 843, 2026-07-09) ────────

    @Test fun `colon citation followed by prose keeps its verse`() {
        // Real transcript row 843: explicit dot-form citation + the quoted verse text. The prose
        // word after the citation used to wipe the buffered verse (name+count guard over-reach),
        // downgrading an explicit tier-1 detection to a weak staged chapter-scan.
        val ru = run("Исайя 26.3 написано «Твердого духом ты хранишь в совершенном мире».")
        assertTrue(
            ru.any { it.bookNum == 23 && it.chapter == 26 && it.verseStart == 3 && it.tier == 1 },
            "Исайя 26.3 + prose should stay an explicit tier-1 ref, got $ru"
        )
        val en = run("Isaiah 26:3 says, you will keep him in perfect peace.")
        assertTrue(
            en.any { it.bookNum == 23 && it.chapter == 26 && it.verseStart == 3 && it.tier == 1 },
            "Isaiah 26:3 + prose should stay an explicit tier-1 ref, got $en"
        )
    }

    @Test fun `dot between digits is citation notation`() {
        val refs = run("Псалом 118.105")
        assertTrue(
            refs.any { it.bookNum == 19 && it.chapter == 118 && it.verseStart == 105 },
            "Псалом 118.105 should resolve as chapter:verse, got $refs"
        )
    }

    @Test fun `keyword-bound numbers still drop before prose - not verses`() {
        // colonSeen is deliberately the ONLY filler-survival trigger: a chapter keyword followed
        // by an unrelated count must not fabricate a verse.
        val refs = run("Матфея 3 глава, мы видим 5 причин почему.")
        assertTrue(
            refs.none { it.verseStart == 5 },
            "count after keyword-bound chapter must not become verse 5, got $refs"
        )
    }

    // ── Punctuation robustness: apostrophe aliases + ё folding (2026-07-09 audit) ──────────────

    @Test fun `ukrainian apostrophe alias resolves in both spellings`() {
        // "об'явлення" tokenizes to two tokens (apostrophe → space); the registered
        // space/deleted variants are what make the greedy join land on Revelation.
        val withApostrophe = run("Об'явлення 3 глава 12 вірш.")
        assertTrue(
            withApostrophe.any { it.bookNum == 66 && it.chapter == 3 && it.verseStart == 12 },
            "Об'явлення 3:12 should resolve, got $withApostrophe"
        )
        val without = run("Обявлення 3 глава 12 вірш.")
        assertTrue(
            without.any { it.bookNum == 66 && it.chapter == 3 && it.verseStart == 12 },
            "Обявлення 3:12 should resolve, got $without"
        )
    }

    @Test fun `ё spelling of a book name resolves`() {
        // ё→е folds before alias/stem lookup, so a ё-spelled inflection still resolves.
        val refs = run("Матфёя 5 глава 3 стих.")
        assertTrue(
            refs.any { it.bookNum == 40 && it.chapter == 5 && it.verseStart == 3 },
            "ё-spelled Матфёя should fold to Матфея and resolve, got $refs"
        )
    }

    // ── Keyword-first citations (2026-07-09: keywords now bind the FOLLOWING number too) ────────

    @Test fun `keyword-first russian citation parses in order`() {
        // Was inverted before the pending-keyword fix: chapter=3, verse=26.
        val refs = run("Матфея глава 26 стих 3.")
        assertTrue(
            refs.any { it.bookNum == 40 && it.chapter == 26 && it.verseStart == 3 },
            "глава 26 стих 3 must parse as 26:3, got $refs"
        )
    }

    @Test fun `keyword-first english citation parses in order`() {
        // The remaining half of the TRAINING_PLAN keyword-order gap.
        val refs = run("Job chapter 3 verse 2.")
        assertTrue(
            refs.any { it.bookNum == 18 && it.chapter == 3 && it.verseStart == 2 },
            "Job chapter 3 verse 2 must parse as 3:2, got $refs"
        )
    }

    @Test fun `number-first forms are unchanged by pending keywords`() {
        val refs = run("Матфея 26 глава 3 стих.")
        assertTrue(
            refs.any { it.bookNum == 40 && it.chapter == 26 && it.verseStart == 3 },
            "26 глава 3 стих must still parse as 26:3, got $refs"
        )
    }

    @Test fun `pending keyword does not bind across prose`() {
        // A keyword followed by filler then an unrelated number must not fabricate a chapter.
        val refs = run("В этой главе, друзья мои, 26 человек упомянуты.")
        assertTrue(refs.isEmpty(), "keyword across prose must not bind, got $refs")
    }
}
