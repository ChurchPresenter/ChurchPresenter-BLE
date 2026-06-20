package engine.engine

import engine.Config
import engine.bible.BibleIndex
import engine.bible.EngineTranslation
import engine.bible.EngineVerse
import engine.detection.ContinuationEngine
import engine.detection.ReferenceWatcher
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

    // Index every loaded translation by default; an explicit Config.defaultTranslations allow-list
    // can restrict it (e.g. to cap memory when many large translations are present).
    private val indexTranslations =
        if (Config.defaultTranslations.isEmpty()) translations
        else translations.filter { it.id in Config.defaultTranslations }.ifEmpty { translations }
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
        val now = System.currentTimeMillis()

        // 1. Explicit / sticky references (stateful watcher). May yield several per utterance.
        val refs = ReferenceWatcher.process(combined, state, now)
        if (refs.isNotEmpty()) {
            val emitted = ArrayList<ScriptureEvent>()
            for (ref in refs) {
                val event = buildRefEvent(state, ref) ?: continue
                stabilizer.evaluate(refKey(event), event.confidence).toEvent(event)?.let {
                    recordDetection(state, it)
                    emitted.add(it)
                }
            }
            if (emitted.isNotEmpty()) return logged(state, emitted)
        }

        // 2. Reverse BM25 lookup (gated by the client-selected level), validated against what was
        //    actually spoken so a spurious BM25 hit on a single rare word can't fire.
        val reverse = if (Config.reverseEnabled) ReverseLookup.search(combined, index, translations) else null
        if (reverse != null) {
            val t = translations.find { it.id == reverse.translationId } ?: return emptyList()
            val verse = t.lookupVerse(reverse.bookNum, reverse.chapter, reverse.verse)
                ?: return emptyList()
            val agreement = AgreementScorer.score(verse.text, state.transcript, state.translation)
            if (agreement >= Config.reverseMinAgreement) {
                val event = buildEvent(
                    id = state.id,
                    verse = verse,
                    translation = t,
                    confidence = reverse.confidence,
                    matchType = "reverse",
                    verseEnd = null,
                )
                stabilizer.evaluate(refKey(event), event.confidence).toEvent(event)?.let {
                    recordDetection(state, it)
                    return logged(state, listOf(it))
                }
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
            if (stabilizer.evaluate(refKey(event), event.confidence) != Stabilizer.EmitDecision.Suppress) {
                recordDetection(state, event)
                return logged(state, listOf(event))
            }
        }

        return emptyList()
    }

    /** Maps a stabilizer decision to the event to emit (with the right type), or null to suppress. */
    private fun Stabilizer.EmitDecision.toEvent(event: ScriptureEvent): ScriptureEvent? = when (this) {
        is Stabilizer.EmitDecision.NewDetection -> event
        is Stabilizer.EmitDecision.UpdatedDetection -> event.copy(type = "scripture.updated")
        Stabilizer.EmitDecision.Suppress -> null
    }

    private fun logged(state: UtteranceState, events: List<ScriptureEvent>): List<ScriptureEvent> {
        for (e in events) DetectionLogger.log(state.transcript, state.translation, e)
        return events
    }

    private fun buildRefEvent(state: UtteranceState, ref: ReferenceWatcher.Ref): ScriptureEvent? {
        val t = pickTranslation(state.transcript)
        val verseStart = ref.verseStart ?: 1
        val verse = t.lookupVerse(ref.bookNum, ref.chapter, verseStart)
            ?.takeIf { !it.isHeader }
            ?: t.firstContentVerse(ref.bookNum, ref.chapter)
            ?: return null
        val verseEnd = ref.verseEnd?.takeIf { it > verse.verse }
        val endCode = verseEnd?.let { t.lookupVerse(ref.bookNum, ref.chapter, it)?.code }
        val bookName = t.bookName(ref.bookNum)
        val displayRef = if (verseEnd != null) "$bookName ${ref.chapter}:${verse.verse}-$verseEnd"
        else if (ref.verseStart != null) "$bookName ${ref.chapter}:${verse.verse}"
        else "$bookName ${ref.chapter}"

        // Tier 3 (sticky, no book spoken) is corroborated by the spoken verse content; tiers 1/2
        // are explicit citations and trusted outright.
        val confidence = when (ref.tier) {
            1 -> 0.95
            2 -> 0.90
            else -> {
                val agree = AgreementScorer.score(verse.text, state.transcript, state.translation)
                (0.60 + (agree * 0.30)).coerceIn(0.60, 0.88)
            }
        }
        val matchType = if (ref.tier == 3) "continuation" else "explicit"
        val type = if (ref.tier == 3) "scripture.continuation" else "scripture.detected"

        return ScriptureEvent(
            type = type,
            id = state.id,
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
            confidence = confidence,
            matchType = matchType,
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
