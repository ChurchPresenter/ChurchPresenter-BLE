package engine

import engine.bible.EngineBook
import engine.bible.EngineTranslation
import engine.bible.EngineVerse
import engine.detection.ContinuationEngine
import engine.engine.UtteranceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ContinuationEngineTest {

    // A tiny synthetic translation with distinguishable verse texts, so checkChapterScope can be
    // tested hermetically without needing real Bible files installed (unlike ReverseLookupTest, which
    // needs the real KJV/RST data to test BM25 quality against actual scripture).
    private fun fixture(verses: List<EngineVerse>): EngineTranslation {
        val byBCV = verses.associateBy { Triple(it.bookNum, it.chapter, it.verse) }
        val byChapter = verses.groupBy { it.bookNum to it.chapter }
        val byCode = verses.associateBy { it.code }
        return EngineTranslation(
            id = "TEST", title = "Test", abbreviation = "TST", language = "ENG", numbering = "standard",
            books = listOf(EngineBook(9, "1 Samuel", 31)),
            byBCV = byBCV, byChapter = byChapter, byCode = byCode,
        )
    }

    private fun stateWithSticky(book: Int?, chapter: Int?, transcript: String, translation: String = "",
                                expiresAt: Long = System.currentTimeMillis() + 60_000L): UtteranceState {
        val state = UtteranceState(id = "test")
        state.watchBook = book
        state.watchChapter = chapter
        state.watchExpiresAt = expiresAt
        state.transcript = transcript
        state.translation = translation
        return state
    }

    @Test fun `resolves the verse whose text clearly matches what was spoken`() {
        val t = fixture(listOf(
            EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma delta epsilon zeta", false),
            EngineVerse("9-15-23", 9, 15, 23, "eta theta iota kappa lambda mu", false),
        ))
        val state = stateWithSticky(9, 15, "alpha beta gamma delta epsilon zeta")
        val result = ContinuationEngine.checkChapterScope(state, t)
        assertNotNull(result, "expected a chapter-scoped match")
        assertEquals(22, result.verse.verse)
    }

    @Test fun `stays silent when two verses in the chapter score too close together`() {
        // Both verses share the exact same overlap with the query — an ambiguous tie the margin
        // gate must catch rather than guess.
        val t = fixture(listOf(
            EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma delta", false),
            EngineVerse("9-15-23", 9, 15, 23, "alpha beta gamma epsilon", false),
        ))
        val state = stateWithSticky(9, 15, "alpha beta gamma")
        val result = ContinuationEngine.checkChapterScope(state, t)
        assertNull(result, "expected no resolution for an ambiguous tie, got $result")
    }

    @Test fun `no sticky book or chapter returns null`() {
        val t = fixture(listOf(EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma", false)))
        val state = stateWithSticky(null, null, "alpha beta gamma")
        assertNull(ContinuationEngine.checkChapterScope(state, t))
    }

    @Test fun `expired sticky returns null`() {
        val t = fixture(listOf(EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma", false)))
        val state = stateWithSticky(9, 15, "alpha beta gamma", expiresAt = System.currentTimeMillis() - 1_000L)
        assertNull(ContinuationEngine.checkChapterScope(state, t))
    }

    @Test fun `query too short returns null`() {
        val t = fixture(listOf(EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma", false)))
        val state = stateWithSticky(9, 15, "alpha")
        assertNull(ContinuationEngine.checkChapterScope(state, t))
    }

    // ── Chapter history (revisiting an earlier chapter without restating book/chapter) ──────────

    @Test fun `resolves an earlier chapter from history when it matches far better than the current sticky`() {
        val t = fixture(listOf(
            EngineVerse("9-15-1", 9, 15, 1, "gamma delta", false),
            EngineVerse("9-10-1", 9, 10, 1, "alpha beta gamma delta epsilon zeta", false),
        ))
        val state = stateWithSticky(9, 15, "alpha beta gamma delta epsilon zeta")
        state.touchChapterHistory(9, 10)
        val result = ContinuationEngine.checkChapterScope(state, t)
        assertNotNull(result, "expected a match from chapter history")
        assertEquals(9, result.verse.bookNum)
        assertEquals(10, result.verse.chapter)
    }

    @Test fun `stays silent when the current sticky and a historical chapter score equally well`() {
        val t = fixture(listOf(
            EngineVerse("9-15-1", 9, 15, 1, "alpha beta gamma", false),
            EngineVerse("9-10-1", 9, 10, 1, "alpha beta gamma", false),
        ))
        val state = stateWithSticky(9, 15, "alpha beta gamma")
        state.touchChapterHistory(9, 10)
        val result = ContinuationEngine.checkChapterScope(state, t)
        assertNull(result, "expected no resolution for an ambiguous cross-chapter tie, got $result")
    }

    @Test fun `no history still resolves against the current sticky alone`() {
        // Regression guard: an empty chapterHistory (the common case) must behave exactly like Part 4.
        val t = fixture(listOf(EngineVerse("9-15-22", 9, 15, 22, "alpha beta gamma delta", false)))
        val state = stateWithSticky(9, 15, "alpha beta gamma delta")
        assertEquals(0, state.chapterHistory.size)
        val result = ContinuationEngine.checkChapterScope(state, t)
        assertNotNull(result)
        assertEquals(22, result.verse.verse)
    }

    @Test fun `touchChapterHistory dedups and moves the touched entry to most-recent`() {
        val state = UtteranceState(id = "test")
        state.touchChapterHistory(9, 15)
        state.touchChapterHistory(40, 3)
        state.touchChapterHistory(9, 15) // touch again — must move to the end, not duplicate
        assertEquals(listOf(40 to 3, 9 to 15), state.chapterHistory.toList())
    }
}
