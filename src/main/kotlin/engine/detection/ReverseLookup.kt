package engine.detection

import engine.Config
import engine.bible.BibleIndex
import engine.bible.EngineTranslation

object ReverseLookup {

    data class ReverseResult(
        val translationId: String,
        val bookNum: Int,
        val chapter: Int,
        val verse: Int,
        val text: String,
        val score: Double,
        val confidence: Double,
    )

    fun search(
        query: String,
        index: BibleIndex,
        translations: List<EngineTranslation>,
        topK: Int = Config.reverseTopK,
    ): ReverseResult? {
        val window = query.split(Regex("\\s+")).takeLast(Config.reverseWindowWords).joinToString(" ")
        val queryTerms = index.tokenize(window).toSet()
        if (queryTerms.size < 3) return null

        // First try: only verses that contain ALL query tokens (precise match)
        val fullResults = index.searchAllTerms(window, topK)

        val rawCandidates: List<BibleIndex.SearchResult>
        val threshold: Double

        if (fullResults.size >= 1) {
            rawCandidates = fullResults
            // All candidates have every query term — trust BM25 ranking, no ratio gate
            threshold = 0.0
        } else {
            // Fallback: partial match with strict ratio to avoid false positives
            val allResults = index.search(window, topK)
            if (allResults.size < 2) return null
            rawCandidates = allResults
            threshold = Config.reverseMinScoreRatio
        }

        // Collapse the same verse appearing in multiple translations to a single entry (keeping the
        // best score). Otherwise the top-1/top-2 ratio gate sees two copies of the SAME verse scoring
        // near-identically (ratio ~1.0) and wrongly suppresses a correct detection.
        val candidates = rawCandidates
            .groupBy { Triple(it.verse.bookNum, it.verse.chapter, it.verse.verse) }
            .map { (_, group) -> group.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }
        if (candidates.isEmpty()) return null

        val top = candidates[0]
        val ratio = if (candidates.size >= 2 && candidates[1].score > 0) {
            candidates[0].score / candidates[1].score
        } else {
            Double.MAX_VALUE
        }

        if (ratio < threshold) return null

        val confidence = when {
            ratio >= 10.0 -> 0.90
            ratio >= 5.0 -> 0.80
            ratio >= 3.0 -> 0.70
            else -> 0.60
        }

        if (translations.none { it.id == top.translationId }) return null
        return ReverseResult(
            translationId = top.translationId,
            bookNum = top.verse.bookNum,
            chapter = top.verse.chapter,
            verse = top.verse.verse,
            text = top.verse.text,
            score = top.score,
            confidence = confidence,
        )
    }
}
