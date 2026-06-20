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
    // A reverse (BM25) hit must also share at least this much word-overlap with what was actually
    // spoken (transcript + translation), so a match on a single rare token can't fire on its own.
    var reverseMinAgreement = 0.15
    val continuationTimeoutMs = 30_000L
    val dedupWindow = 32
    // Suppress an identical reference only within this window (time-based, replaces the old fixed
    // count-only window) so a passage read again later can re-fire.
    var dedupTtlMs = 45_000L
    var minConfidenceEmit = 0.4
    val bm25K1 = 1.5
    val bm25B = 0.75
    val reverseWindowWords = 25
    val reverseTopK = 10

    // How long an announced book+chapter stays "sticky" for verse-by-verse reading. Generous by
    // default because expositional reads span minutes; shrunk for aggressive/rapid-fire cadence.
    var stickyTtlMs = 180_000L

    // Gates the BM25 reverse (text) lookup. Explicit parsing + continuation always run.
    var reverseEnabled = true

    /** Maps the client's aggressiveness level to reverse-lookup tuning. */
    fun applyLevel(level: String) {
        when (level.lowercase()) {
            "off"          -> { reverseEnabled = false }
            "conservative" -> { reverseEnabled = true; minConfidenceEmit = 0.6; reverseMinScoreRatio = 2.5; stickyTtlMs = 240_000L }
            "balanced"     -> { reverseEnabled = true; minConfidenceEmit = 0.4; reverseMinScoreRatio = 2.0; stickyTtlMs = 180_000L }
            "aggressive"   -> { reverseEnabled = true; minConfidenceEmit = 0.3; reverseMinScoreRatio = 1.5; stickyTtlMs = 90_000L }
        }
    }
}
