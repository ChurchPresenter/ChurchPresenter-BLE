package engine

import engine.tools.Category
import engine.tools.StickyRow
import engine.tools.classify
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sticky auditor's bucketing, which decides what a human reads after a service.
 *
 * UNEXPLAINED is the category that has to stay trustworthy — it means "a jump no alias in the text
 * accounts for", i.e. the thing to look at first. Anything the live engine resolves by a route the
 * auditor doesn't ask about lands there wrongly and buries the real ones: across the 2026-07-19
 * sessions, 4 of 5 UNEXPLAINED rows were perfectly ordinary numbered-book citations.
 */
class StickyAuditTest {

    private fun row(
        transcript: String,
        newBook: Int?,
        prevBook: Int? = null,
        prevChapter: Int? = null,
        newChapter: Int? = null,
        translation: String = "",
    ) = StickyRow(
        ts = "2026-07-19T14:27:21.377287Z",
        prevBook = prevBook, prevChapter = prevChapter,
        newBook = newBook, newChapter = newChapter,
        transcript = transcript, translation = translation,
    )

    @Test fun `an ordinal numbered-book citation is explained, not flagged (real session trace)`() {
        // sticky-log-2026-07-19_102718.jsonl, ts 15:15:48Z — "Во втором послании Коринфянам"
        // resolves to 2 Corinthians through resolveNumberedBookAt; no alias spells it.
        val v = classify(
            row(
                "Почему? Теперь давайте задумаемся, собственно, о том, какой был отклик, наш " +
                    "отклик на то, что сделал для нас Господь. Во втором послании Коринфянам.",
                newBook = 47,
            )
        )
        assertEquals(Category.CONFIDENT, v.category, v.detail)
    }

    @Test fun `a bare epistle marker defaulting to the first is explained too (real session trace)`() {
        // sticky-log-2026-07-19_181920.jsonl, ts 22:23:15Z — "в послании к Иоанну" → 1 John by the
        // John/Peter marker-alone convention.
        val v = classify(
            row("Апостол Павел очень интересно говорит в послании к Иоанну.", newBook = 62)
        )
        assertEquals(Category.CONFIDENT, v.category, v.detail)
    }

    @Test fun `a jump with nothing in the text to explain it is still flagged`() {
        val v = classify(row("Мы говорим, друзья нас не поняли, не поддержали.", newBook = 65))
        assertEquals(Category.UNEXPLAINED, v.category)
    }

    @Test fun `a short exact alias is still flagged for review`() {
        val v = classify(row("Я слышал их плач в ту ночь.", newBook = 25))
        assertEquals(Category.SHORT_ALIAS, v.category)
    }

    @Test fun `a same-book chapter clear is still flagged structurally`() {
        val v = classify(row("...", newBook = 19, prevBook = 19, prevChapter = 14, newChapter = null))
        assertEquals(Category.CHAPTER_CLEARED, v.category)
    }
}
