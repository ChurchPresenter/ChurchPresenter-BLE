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
    // Min fraction of a verse's words that must appear in a track (transcript / translation) for that
    // track to be marked as corroborating the detection (the per-chip transcription/translation icons).
    var trackCoverageMin = 0.4
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

    // Precision gate (independent of the aggressiveness level): when the STT engine labels a segment
    // as music, skip explicit/sticky reference detection on it. Sung lyrics quote scripture but are
    // not references being looked up, and they must not seed the sticky context. Requires a reliable
    // speech_type from the STT stream; safe to disable if that signal is unreliable.
    var suppressDuringMusic = true

    // ── Aggressiveness-gated recall toggles (set by applyLevel) ───────────────────
    // Risky inferences ride the existing level chip rather than being on unconditionally.
    // Defaults mirror BALANCED so a fresh Config (no applyLevel) behaves as the default level.

    // Fold STT spelling variants before book resolution (Cyrillic э→е so "эфесянам"→"ефесянам").
    // Cheap and almost always harmless (most э-words are fillers), but off at CONSERVATIVE.
    var normalizeStt: Boolean = true

    // Allow a Book named AFTER its chapter/verse numbers in the same utterance to attach to them
    // (e.g. "14 стих 3 главы … Матфея" → Matt 3:14) instead of flushing them as sticky. Higher
    // false-positive risk → AGGRESSIVE only.
    var inferBookAtEnd: Boolean = false

    /** Maps the client's aggressiveness level to reverse-lookup tuning + gated recall. */
    fun applyLevel(level: String) {
        when (level.lowercase()) {
            "off"          -> { reverseEnabled = false; normalizeStt = false; inferBookAtEnd = false }
            "conservative" -> { reverseEnabled = true; minConfidenceEmit = 0.6; reverseMinScoreRatio = 2.5; stickyTtlMs = 240_000L; normalizeStt = false; inferBookAtEnd = false }
            "balanced"     -> { reverseEnabled = true; minConfidenceEmit = 0.4; reverseMinScoreRatio = 2.0; stickyTtlMs = 180_000L; normalizeStt = true;  inferBookAtEnd = false }
            "aggressive"   -> { reverseEnabled = true; minConfidenceEmit = 0.3; reverseMinScoreRatio = 1.5; stickyTtlMs = 90_000L;  normalizeStt = true;  inferBookAtEnd = true }
        }
    }
}
