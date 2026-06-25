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
    // STT segment that triggered this detection. Clock-free correlation key shared by the STT
    // transcript rows, the detection log, and the operator's go-live log. Null when the STT stream
    // doesn't provide it (older schema).
    val segmentId: String? = null,
    val sttStartTime: Double? = null,
    // Which STT track(s) corroborate this detection — subset of {"transcription","translation"}.
    // A corroboration/confidence signal for the UI: both present = strongest.
    val tracks: List<String> = emptyList(),
    // ── Diagnostics (logged for training; not used by the UI) ──
    val tier: Int? = null,             // explicit-ref tier (1/2/3); null for reverse/continuation
    val bm25Score: Double? = null,     // reverse: top BM25 score
    val bm25Ratio: Double? = null,     // reverse: top-1/top-2 score ratio (margin over runner-up)
    val speechType: String? = null,    // STT speech_type at decision time (Speaking/Quiet/Music)
    val stickyBook: Int? = null,       // sticky context book when this fired
    val stickyChapter: Int? = null,    // sticky context chapter when this fired
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

    fun processTranscription(
        id: String,
        text: String,
        speechType: String? = null,
        segmentId: String? = null,
        startTime: Double? = null,
    ): List<ScriptureEvent> {
        val state = utterances.getOrPut(id) { UtteranceState(id) }
        state.transcript = text
        if (speechType != null) state.speechType = speechType
        if (segmentId != null) state.segmentId = segmentId
        if (startTime != null) state.sttStartTime = startTime
        state.updatedAt = System.currentTimeMillis()
        return runDetection(state)
    }

    fun processTranslation(
        id: String,
        text: String,
        speechType: String? = null,
        segmentId: String? = null,
        startTime: Double? = null,
    ): List<ScriptureEvent> {
        val state = utterances.getOrPut(id) { UtteranceState(id) }
        state.translation = text
        if (speechType != null) state.speechType = speechType
        if (segmentId != null) state.segmentId = segmentId
        if (startTime != null) state.sttStartTime = startTime
        state.updatedAt = System.currentTimeMillis()
        return runDetection(state)
    }

    private fun isMusic(speechType: String?): Boolean = speechType.equals("Music", ignoreCase = true)

    private fun runDetection(state: UtteranceState): List<ScriptureEvent> {
        val combined = "${state.transcript} ${state.translation}".trim()
        val now = System.currentTimeMillis()

        // 1. Explicit / sticky references (stateful watcher). May yield several per utterance.
        //    Suppressed on music segments (sung lyrics aren't references being looked up).
        val refs = ReferenceWatcher.process(combined, state, now, isMusic = isMusic(state.speechType))
        if (refs.isNotEmpty()) {
            val emitted = ArrayList<ScriptureEvent>()
            for (ref in refs) {
                val event = buildRefEvent(state, ref) ?: continue
                val decision = stabilizer.evaluate(refKey(event), event.confidence)
                val out = decision.toEvent(event)
                if (out != null) {
                    recordDetection(state, out)
                    emitted.add(out)
                } else if (decision is Stabilizer.EmitDecision.Suppress) {
                    logCandidate(state, event, decision.reason)
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
            val event = buildEvent(
                id = state.id,
                verse = verse,
                translation = t,
                confidence = reverse.confidence,
                matchType = "reverse",
                verseEnd = null,
            ).copy(bm25Score = reverse.score, bm25Ratio = reverse.ratio.takeIf { it.isFinite() && it < 1e6 })
            if (agreement >= Config.reverseMinAgreement) {
                val decision = stabilizer.evaluate(refKey(event), event.confidence)
                val out = decision.toEvent(event)
                if (out != null) {
                    recordDetection(state, out)
                    return logged(state, listOf(out))
                } else if (decision is Stabilizer.EmitDecision.Suppress) {
                    logCandidate(state, event, decision.reason)
                }
            } else {
                // BM25 hit that didn't share enough spoken words to fire — the prime near-miss to study.
                logCandidate(state, event, "low-agreement")
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
            val decision = stabilizer.evaluate(refKey(event), event.confidence)
            if (decision !is Stabilizer.EmitDecision.Suppress) {
                recordDetection(state, event)
                return logged(state, listOf(event))
            } else {
                logCandidate(state, event, decision.reason)
            }
        }

        return emptyList()
    }

    /** Maps a stabilizer decision to the event to emit (with the right type), or null to suppress. */
    private fun Stabilizer.EmitDecision.toEvent(event: ScriptureEvent): ScriptureEvent? = when (this) {
        is Stabilizer.EmitDecision.NewDetection -> event
        is Stabilizer.EmitDecision.UpdatedDetection -> event.copy(type = "scripture.updated")
        is Stabilizer.EmitDecision.Suppress -> null
    }

    /**
     * Records a built-but-not-emitted detection to the candidate (near-miss) log for training, stamped
     * with the same segment/track context as a real emission. Floored + toggle-gated; never throws.
     */
    private fun logCandidate(state: UtteranceState, event: ScriptureEvent, reason: String) {
        if (!Config.logCandidates || event.confidence < Config.candidateLogMinConfidence) return
        val stamped = stamp(state, event, System.currentTimeMillis())
        DetectionLogger.logCandidate(state.transcript, state.translation, stamped, reason)
    }

    private fun logged(state: UtteranceState, events: List<ScriptureEvent>): List<ScriptureEvent> {
        // Stamp the triggering STT segment + per-track corroboration onto every emitted event here —
        // the single funnel for all detection paths — so both the broadcast and the detection log
        // carry the correlation key and the transcription/translation markers.
        val now = System.currentTimeMillis()
        val stamped = events.map { stamp(state, it, now) }
        for (e in stamped) DetectionLogger.log(state.transcript, state.translation, e)
        return stamped
    }

    /** Stamps the per-utterance context (segment, tracks, speech type, sticky) onto an event. */
    private fun stamp(state: UtteranceState, event: ScriptureEvent, now: Long): ScriptureEvent =
        event.copy(
            segmentId = state.segmentId ?: event.segmentId,
            sttStartTime = state.sttStartTime ?: event.sttStartTime,
            tracks = corroboratingTracks(state, event, now),
            speechType = state.speechType ?: event.speechType,
            stickyBook = state.watchBook ?: event.stickyBook,
            stickyChapter = state.watchChapter ?: event.stickyChapter,
        )

    /** The STT track(s) that support [event] — verse text read in the track, or its citation spoken there. */
    private fun corroboratingTracks(state: UtteranceState, event: ScriptureEvent, now: Long): List<String> {
        val tracks = ArrayList<String>(2)
        if (trackSupports(state.transcript, event, now)) tracks.add("transcription")
        if (trackSupports(state.translation, event, now)) tracks.add("translation")
        return tracks
    }

    private fun trackSupports(trackText: String, event: ScriptureEvent, now: Long): Boolean {
        if (trackText.isBlank()) return false
        // Verse being read in this track (covers reverse / continuation / explicit-after-read).
        if (AgreementScorer.coverage(event.verseText, trackText) >= Config.trackCoverageMin) return true
        // Citation spoken in this track — a throwaway sticky so we don't disturb the live context
        // (covers explicit references before the verse itself is read aloud).
        val sticky = object : ReferenceWatcher.Sticky {
            override var watchBook: Int? = null
            override var watchChapter: Int? = null
            override var watchExpiresAt: Long = 0L
        }
        return ReferenceWatcher.process(trackText, sticky, now).any {
            it.bookNum == event.reference.bookId && it.chapter == event.reference.chapter
        }
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
            tier = ref.tier,
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
