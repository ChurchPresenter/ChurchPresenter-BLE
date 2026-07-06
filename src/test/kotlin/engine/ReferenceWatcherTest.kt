package engine

import engine.detection.BookResolver
import engine.detection.ReferenceWatcher
import kotlin.random.Random
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

}
