package engine.engine

import engine.detection.ReferenceWatcher

data class UtteranceLastRef(
    val bookNum: Int,
    val chapter: Int,
    val verseStart: Int,
)

data class UtteranceState(
    val id: String,
    var transcript: String = "",
    var translation: String = "",
    var lastDetected: UtteranceLastRef? = null,
    var lastDetectedAt: Long = 0L,
    var lastTranslationId: String = "",
    var lastConfidence: Double = 0.0,
    var updatedAt: Long = System.currentTimeMillis(),
    // ── Sticky reference context (ReferenceWatcher) ──
    // The most recently announced book + chapter, carried across utterances so a later bare
    // "N стих" (verse-by-verse reading) resolves against it. Expires after Config.stickyTtlMs.
    override var watchBook: Int? = null,
    override var watchChapter: Int? = null,
    override var watchExpiresAt: Long = 0L,
) : ReferenceWatcher.Sticky
