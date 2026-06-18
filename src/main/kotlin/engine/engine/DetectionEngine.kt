package engine.engine

import engine.Config
import engine.bible.BibleIndex
import engine.bible.EngineTranslation
import engine.bible.EngineVerse
import engine.detection.ContinuationEngine
import engine.detection.ExplicitParser
import engine.detection.ReverseLookup
import kotlinx.serialization.Serializable

@Serializable
data class ScriptureReference(
    val bookId: Int,
    val bookName: String,
    val chapter: Int,
    val verseStart: Int,
    val verseEnd: Int?,
    val displayRef: String,
    val canonicalCodeStart: String,
    val canonicalCodeEnd: String?,
    val numbering: String,
)

@Serializable
data class ScriptureEvent(
    val type: String,
    val id: String,
    val reference: ScriptureReference,
    val verseText: String,
    val confidence: Double,
    val matchType: String,
    val translation: String,
)

class DetectionEngine(private val translations: List<EngineTranslation>) {

    // BM25 index only over defaults — indexing all 72 translations would exceed 1 GB RAM
    private val indexTranslations = translations.filter { it.id in Config.defaultTranslations }
        .ifEmpty { translations.take(2) }
    private val index = BibleIndex(indexTranslations)
    private val stabilizer = Stabilizer()
    private val utterances = HashMap<String, UtteranceState>()

    fun processTranscription(id: String, text: String): List<ScriptureEvent> {
        val state = utterances.getOrPut(id) { UtteranceState(id) }
        state.transcript = text
        state.updatedAt = System.currentTimeMillis()
        return runDetection(state)
    }

    fun processTranslation(id: String, text: String): List<ScriptureEvent> {
        val state = utterances.getOrPut(id) { UtteranceState(id) }
        state.translation = text
        state.updatedAt = System.currentTimeMillis()
        return runDetection(state)
    }

    private fun runDetection(state: UtteranceState): List<ScriptureEvent> {
        val combined = "${state.transcript} ${state.translation}".trim()

        // 1. Explicit reference
        val explicit = ExplicitParser.parse(combined)
        if (explicit != null) {
            val t = pickTranslation(state.transcript)
            val event = buildExplicitEvent(state.id, explicit, t) ?: return emptyList()
            val key = refKey(event)
            return when (stabilizer.evaluate(key, event.confidence)) {
                is Stabilizer.EmitDecision.NewDetection -> {
                    recordDetection(state, event)
                    listOf(event)
                }
                is Stabilizer.EmitDecision.UpdatedDetection -> {
                    recordDetection(state, event)
                    listOf(event.copy(type = "scripture.updated"))
                }
                Stabilizer.EmitDecision.Suppress -> emptyList()
            }
        }

        // 2. Reverse BM25 lookup (gated by the client-selected level)
        val reverse = if (Config.reverseEnabled) ReverseLookup.search(combined, index, translations) else null
        if (reverse != null) {
            val t = translations.find { it.id == reverse.translationId } ?: return emptyList()
            val verse = t.lookupVerse(reverse.bookNum, reverse.chapter, reverse.verse)
                ?: return emptyList()
            val event = buildEvent(
                id = state.id,
                verse = verse,
                translation = t,
                confidence = reverse.confidence,
                matchType = "reverse",
                verseEnd = null,
            )
            val key = refKey(event)
            return when (stabilizer.evaluate(key, event.confidence)) {
                is Stabilizer.EmitDecision.NewDetection -> {
                    recordDetection(state, event)
                    listOf(event)
                }
                is Stabilizer.EmitDecision.UpdatedDetection -> {
                    recordDetection(state, event)
                    listOf(event.copy(type = "scripture.updated"))
                }
                Stabilizer.EmitDecision.Suppress -> emptyList()
            }
        }

        // 3. Continuation
        val cont = ContinuationEngine.check(state, translations)
        if (cont != null) {
            val event = buildEvent(
                id = state.id,
                verse = cont.verse,
                translation = cont.translation,
                confidence = cont.confidence,
                matchType = "continuation",
                verseEnd = null,
            ).copy(type = "scripture.continuation")
            val key = refKey(event)
            return when (stabilizer.evaluate(key, event.confidence)) {
                is Stabilizer.EmitDecision.Suppress -> emptyList()
                else -> {
                    recordDetection(state, event)
                    listOf(event)
                }
            }
        }

        return emptyList()
    }

    private fun buildExplicitEvent(
        id: String,
        ref: ExplicitParser.ParsedReference,
        t: EngineTranslation,
    ): ScriptureEvent? {
        val verseStart = ref.verseStart ?: 1
        val verse = t.lookupVerse(ref.bookNum, ref.chapter, verseStart)
            ?.takeIf { !it.isHeader }
            ?: t.firstContentVerse(ref.bookNum, ref.chapter)
            ?: return null
        val verseEnd = ref.verseEnd?.takeIf { it > verse.verse }
        val endCode = verseEnd?.let {
            t.lookupVerse(ref.bookNum, ref.chapter, it)?.code
        }
        val bookName = t.bookName(ref.bookNum)
        val displayRef = if (verseEnd != null) "$bookName ${ref.chapter}:${verse.verse}-$verseEnd"
        else "$bookName ${ref.chapter}:${verse.verse}"
        return ScriptureEvent(
            type = "scripture.detected",
            id = id,
            reference = ScriptureReference(
                bookId = ref.bookNum,
                bookName = bookName,
                chapter = ref.chapter,
                verseStart = verse.verse,
                verseEnd = verseEnd,
                displayRef = displayRef,
                canonicalCodeStart = verse.code,
                canonicalCodeEnd = endCode,
                numbering = t.numbering,
            ),
            verseText = verse.text,
            confidence = 0.95,
            matchType = "explicit",
            translation = t.abbreviation,
        )
    }

    private fun buildEvent(
        id: String,
        verse: EngineVerse,
        translation: EngineTranslation,
        confidence: Double,
        matchType: String,
        verseEnd: Int?,
    ): ScriptureEvent {
        val bookName = translation.bookName(verse.bookNum)
        val displayRef = "$bookName ${verse.chapter}:${verse.verse}"
        return ScriptureEvent(
            type = "scripture.detected",
            id = id,
            reference = ScriptureReference(
                bookId = verse.bookNum,
                bookName = bookName,
                chapter = verse.chapter,
                verseStart = verse.verse,
                verseEnd = verseEnd,
                displayRef = displayRef,
                canonicalCodeStart = verse.code,
                canonicalCodeEnd = null,
                numbering = translation.numbering,
            ),
            verseText = verse.text,
            confidence = confidence,
            matchType = matchType,
            translation = translation.abbreviation,
        )
    }

    private fun pickTranslation(transcript: String): EngineTranslation {
        val hasCyrillic = transcript.any { it in 'Ѐ'..'ӿ' }
        return if (hasCyrillic) {
            translations.find { it.id == "RUS_RST" }
                ?: translations.find { it.language == "RUS" }
        } else {
            translations.find { it.id == "ENG_KJV" }
                ?: translations.find { it.language == "ENG" }
        } ?: translations.first()
    }

    private fun recordDetection(state: UtteranceState, event: ScriptureEvent) {
        state.lastDetected = UtteranceLastRef(
            bookNum = event.reference.bookId,
            chapter = event.reference.chapter,
            verseStart = event.reference.verseStart,
        )
        state.lastDetectedAt = System.currentTimeMillis()
        state.lastTranslationId = translations.find { it.abbreviation == event.translation }?.id ?: ""
        state.lastConfidence = event.confidence
    }

    private fun refKey(event: ScriptureEvent): String =
        "${event.reference.bookId}:${event.reference.chapter}:${event.reference.verseStart}"
}
