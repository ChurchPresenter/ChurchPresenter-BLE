package engine.engine

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
)
