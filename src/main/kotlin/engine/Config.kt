package engine

object Config {
    var bibleRoot: String = System.getProperty("bible.root", "")
    var sttServerUrl: String = ""
    var outputPort: Int = 8765

    // Routine lifecycle chatter (STT/WS connect+disconnect) is OFF by default so the host app's
    // terminal stays quiet; enable with -Dengine.verbose=true for connectivity debugging. Genuine
    // errors (System.err — bind/parse/connection failures) are always printed regardless.
    var verboseLog: Boolean = System.getProperty("engine.verbose")?.toBooleanStrictOrNull() ?: false

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
    // Near-miss candidate logging (training data): log detections the engine BUILT but did NOT emit
    // (below the confidence threshold, deduped, or failed reverse-agreement), so false negatives are
    // visible for tuning. Floored to avoid noise. Toggle off with -Dengine.logCandidates=false.
    var logCandidates = System.getProperty("engine.logCandidates")?.toBooleanStrictOrNull() ?: true
    var candidateLogMinConfidence = 0.15
    // Traces every sticky book/chapter change to sticky-log-*.jsonl, even when nothing emits — the
    // diagnostic for an unexplained stale/wrong sticky that never produced a logged detection.
    // Low-volume (sticky changes are infrequent, not per-utterance); same default spirit as logCandidates.
    var logStickyChanges = System.getProperty("engine.logStickyChanges")?.toBooleanStrictOrNull() ?: true
    val continuationTimeoutMs = 30_000L

    // Sequential continuation acceptance: fraction of the CANDIDATE VERSE's words that must be
    // present in the text window ("verse-side coverage", AgreementScorer.coverage). Verse-side —
    // not query-side — because the 2-segment sliding window dilutes a query-normalized overlap to
    // a ~50% ceiling even when a verse is read verbatim (the documented sequential-reading FN
    // class; Matthew 9:37 in the 2026-07-08 session). Verses with < 4 distinct scoring words must
    // be fully covered instead (spurious-full-coverage guard).
    var continuationMinCoverage = 0.5
    val dedupWindow = 32
    // Suppress an identical reference only within this window (time-based, replaces the old fixed
    // count-only window) so a passage read again later can re-fire.
    var dedupTtlMs = 45_000L
    // Re-emission churn control for a held passage. A reference already showing only re-emits as an
    // "updated" event when its confidence moves by at least [reEmitMinDelta] AND at least
    // [reEmitCooldownMs] has passed since the last (new or updated) emission. The reverse-lookup
    // confidence oscillates as the window slides, which previously re-presented the same verse many
    // times (Иакова 2:19 fired 11× in one service); these bound it to at most once per cooldown.
    var reEmitMinDelta = 0.15
    var reEmitCooldownMs = 10_000L
    var minConfidenceEmit = 0.4
    val bm25K1 = 1.5
    val bm25B = 0.75
    val reverseWindowWords = 25
    val reverseTopK = 10

    // Chapter-scoped continuation: once book+chapter is known (the sticky), score every verse in that
    // chapter against what was spoken instead of requiring an explicit verse citation. The candidate
    // pool is already narrowed to one chapter (~10-50 verses), so this can use a lower agreement floor
    // than the global reverse lookup without raising false-positive risk — protected instead by the
    // margin-over-runner-up gate below (same safety pattern as reverseMinScoreRatio). Starting values;
    // tune against real training data.
    var chapterScopeMinAgreement = 0.10
    var chapterScopeMinRatio = 1.5

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

    // The active aggressiveness level name (off / conservative / balanced / aggressive), recorded on
    // each logged detection so a row self-describes which tuning produced it (it can change mid-session).
    var level: String = "balanced"

    // Translation ids actually indexed this session (e.g. ["RUS_RST","ENG_KJV"]) — logged in the
    // per-session header so a service's results are tied to the Bibles that produced them.
    var loadedBibles: List<String> = emptyList()

    /** Maps the client's aggressiveness level to reverse-lookup tuning + gated recall. */
    fun applyLevel(level: String) {
        this.level = level.lowercase()
        when (level.lowercase()) {
            "off"          -> { reverseEnabled = false; normalizeStt = false; inferBookAtEnd = false }
            "conservative" -> { reverseEnabled = true; minConfidenceEmit = 0.6; reverseMinScoreRatio = 2.5; stickyTtlMs = 240_000L; normalizeStt = false; inferBookAtEnd = false }
            "balanced"     -> { reverseEnabled = true; minConfidenceEmit = 0.4; reverseMinScoreRatio = 2.0; stickyTtlMs = 180_000L; normalizeStt = true;  inferBookAtEnd = false }
            "aggressive"   -> { reverseEnabled = true; minConfidenceEmit = 0.3; reverseMinScoreRatio = 1.5; stickyTtlMs = 90_000L;  normalizeStt = true;  inferBookAtEnd = true }
        }
    }
}
