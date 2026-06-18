package engine

object Config {
    var bibleRoot: String = System.getProperty("bible.root", "")
    var sttServerUrl: String = ""
    var outputPort: Int = 8765

    // Optional BM25 allow-list. Empty = index every SPB found in the bible folder (the default);
    // set specific ids only to cap memory when many large translations are present.
    val defaultTranslations = emptyList<String>()

    // Detection tuning (var ones are runtime-settable via the `set_tuning` WebSocket message)
    var reverseMinScoreRatio = 2.0
    val continuationTimeoutMs = 30_000L
    val dedupWindow = 32
    var minConfidenceEmit = 0.4
    val bm25K1 = 1.5
    val bm25B = 0.75
    val reverseWindowWords = 25
    val reverseTopK = 10

    // Gates the BM25 reverse (text) lookup. Explicit parsing + continuation always run.
    var reverseEnabled = true

    /** Maps the client's aggressiveness level to reverse-lookup tuning. */
    fun applyLevel(level: String) {
        when (level.lowercase()) {
            "off"          -> { reverseEnabled = false }
            "conservative" -> { reverseEnabled = true; minConfidenceEmit = 0.6; reverseMinScoreRatio = 2.5 }
            "balanced"     -> { reverseEnabled = true; minConfidenceEmit = 0.4; reverseMinScoreRatio = 2.0 }
            "aggressive"   -> { reverseEnabled = true; minConfidenceEmit = 0.3; reverseMinScoreRatio = 1.5 }
        }
    }
}
