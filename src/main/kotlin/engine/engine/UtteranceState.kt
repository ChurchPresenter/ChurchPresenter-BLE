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
    // Most recent STT speech_type for this stream (e.g. "Speaking"/"Quiet"/"Music"); drives the
    // music precision gate in ReferenceWatcher. Null = unknown (treated as not music).
    var speechType: String? = null,
    // Most recent STT segment_id + session-relative start_time that fed this utterance. Stamped onto
    // every emitted detection so transcripts, detections, and displays all reference the same STT
    // segment — clock-free cross-machine correlation (no NTP/wall-clock needed). Null until the STT
    // stream provides them.
    var segmentId: String? = null,
    var sttStartTime: Double? = null,
    // ── Sticky reference context (ReferenceWatcher) ──
    // The most recently announced book + chapter, carried across utterances so a later bare
    // "N стих" (verse-by-verse reading) resolves against it. Expires after Config.stickyTtlMs.
    override var watchBook: Int? = null,
    override var watchChapter: Int? = null,
    override var watchExpiresAt: Long = 0L,
) : ReferenceWatcher.Sticky
