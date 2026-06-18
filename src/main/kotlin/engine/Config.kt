package engine

object Config {
    var bibleRoot: String = System.getProperty("bible.root", "")
    var sttServerUrl: String = ""
    var outputPort: Int = 8765

    // Detection translations — BM25 index is built only over these
    val defaultTranslations = listOf("ENG_KJV", "RUS_RST")

    // Detection tuning
    val reverseMinScoreRatio = 2.0
    val continuationTimeoutMs = 30_000L
    val dedupWindow = 32
    val minConfidenceEmit = 0.4
    val bm25K1 = 1.5
    val bm25B = 0.75
    val reverseWindowWords = 25
    val reverseTopK = 10
}
