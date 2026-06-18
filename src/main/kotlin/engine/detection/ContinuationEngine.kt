package engine.detection

import engine.Config
import engine.bible.EngineTranslation
import engine.bible.EngineVerse
import engine.engine.UtteranceState

object ContinuationEngine {

    data class ContinuationResult(
        val verse: EngineVerse,
        val translation: EngineTranslation,
        val confidence: Double,
    )

    fun check(
        state: UtteranceState,
        translations: List<EngineTranslation>,
    ): ContinuationResult? {
        val lastRef = state.lastDetected ?: return null
        val now = System.currentTimeMillis()
        if (now - state.lastDetectedAt > Config.continuationTimeoutMs) return null

        val query = "${state.transcript} ${state.translation}".trim()
        if (query.split(Regex("\\s+")).size < 3) return null

        val t = translations.find { it.id == state.lastTranslationId } ?: return null
        val lastVerse = t.lookupVerse(lastRef.bookNum, lastRef.chapter, lastRef.verseStart)
            ?: return null

        // Look up the next 3 candidate verses
        var candidate: EngineVerse? = t.nextVerse(lastVerse)
        repeat(3) {
            val c = candidate ?: return@repeat
            val score = wordOverlap(c.text, query)
            if (score >= 0.35) {
                return ContinuationResult(c, t, score.coerceIn(0.5, 0.88))
            }
            candidate = t.nextVerse(c)
        }
        return null
    }

    private fun wordOverlap(verseText: String, query: String): Double {
        val vWords = verseText.lowercase().split(Regex("[^a-z\\u0400-\\u04FF]+")).filter { it.length >= 3 }.toSet()
        val qWords = query.lowercase().split(Regex("[^a-z\\u0400-\\u04FF]+")).filter { it.length >= 3 }.toSet()
        if (qWords.isEmpty() || vWords.isEmpty()) return 0.0
        return vWords.intersect(qWords).size.toDouble() / qWords.size
    }
}
