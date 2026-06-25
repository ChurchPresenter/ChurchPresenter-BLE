package engine.engine

import engine.Config
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Appends every emitted detection — with the triggering transcript/translation — to a per-run
 * timestamped JSONL file, turning each live service into labeled-ish regression data without manual
 * annotation. Disabled unless [path] is set (EngineServer/BibleEngineClient point it at the log
 * dir). The configured [path]'s parent + base name `detection-log` is rolled into
 * `detection-log-YYYY-MM-DD_HH-mm-ss.jsonl`, where the timestamp is frozen at process start so
 * separate app starts never combine into one file. Files older than [MAX_AGE_DAYS] are pruned once
 * per process for size control. Best-effort; never throws into the detection path.
 *
 * [logCandidate] writes built-but-not-emitted near-misses to a parallel `candidate-log-*.jsonl`
 * (same rotation/cleanup) for confidence tuning — gated by `Config.logCandidates`.
 *
 * One JSON object per line: {ts, ref, book, chapter, verse, source, confidence, emitted, reason?,
 * segmentId, sttStartTime, tracks, transcript, translation}.
 */
object DetectionLogger {
    @Volatile var path: String? = null

    private const val MAX_AGE_DAYS = 30L
    private const val BASE_PREFIX = "detection-log-"
    private const val CANDIDATE_PREFIX = "candidate-log-"

    private val lock = Any()
    private val cleanedUp = AtomicBoolean(false)
    private val sessionWritten = AtomicBoolean(false)

    // Frozen at first use (≈ process start), down to the second so quick restarts never share a file.
    private val runStamp: String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    /** Per-run sibling of the configured [path], e.g. `<parent>/<prefix>2026-06-24_14-30-05.jsonl`. */
    private fun datedFile(configured: String, prefix: String): File {
        val parent = File(configured).absoluteFile.parentFile ?: File(".")
        return File(parent, "$prefix$runStamp.jsonl")
    }

    /** Deletes dated detection + candidate logs older than [MAX_AGE_DAYS] in [dir]. Once per process. */
    private fun cleanupOldLogsOnce(dir: File?) {
        if (dir == null || !cleanedUp.compareAndSet(false, true)) return
        runCatching {
            val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                val n = file.name
                if (file.isFile && n.endsWith(".jsonl") &&
                    (n.startsWith(BASE_PREFIX) || n.startsWith(CANDIDATE_PREFIX)) &&
                    file.lastModified() < cutoff
                ) file.delete()
            }
        }
    }

    /** An emitted detection → `detection-log-*.jsonl`. */
    fun log(transcript: String, translation: String, event: ScriptureEvent) {
        val configured = path ?: return
        val target = datedFile(configured, BASE_PREFIX)
        cleanupOldLogsOnce(target.parentFile)
        ensureSessionHeader(target)
        runCatching {
            synchronized(lock) { target.appendText(lineFor(transcript, translation, event, reason = null) + "\n", Charsets.UTF_8) }
        }
    }

    /**
     * Writes a one-time per-run session header — the Bibles, level and thresholds that produced this
     * service's rows — as the first line of the detection log, so results tie back to the exact config.
     */
    private fun ensureSessionHeader(target: File) {
        if (!sessionWritten.compareAndSet(false, true)) return
        runCatching {
            val line = buildString {
                append('{')
                append("\"type\":\"session\",")
                append("\"ts\":\"").append(Instant.now()).append("\",")
                append("\"bibles\":[").append(Config.loadedBibles.joinToString(",") { "\"" + esc(it) + "\"" }).append("],")
                append("\"level\":\"").append(esc(Config.level)).append("\",")
                append("\"minConfidenceEmit\":").append(Config.minConfidenceEmit).append(',')
                append("\"reverseMinScoreRatio\":").append(Config.reverseMinScoreRatio).append(',')
                append("\"reverseMinAgreement\":").append(Config.reverseMinAgreement).append(',')
                append("\"trackCoverageMin\":").append(Config.trackCoverageMin).append(',')
                append("\"stickyTtlMs\":").append(Config.stickyTtlMs).append(',')
                append("\"dedupTtlMs\":").append(Config.dedupTtlMs).append(',')
                append("\"logCandidates\":").append(Config.logCandidates).append(',')
                append("\"candidateLogMinConfidence\":").append(Config.candidateLogMinConfidence).append(',')
                append("\"sttServer\":\"").append(esc(Config.sttServerUrl)).append('"')
                append('}')
            }
            synchronized(lock) { target.appendText(line + "\n", Charsets.UTF_8) }
        }
    }

    /**
     * A built-but-not-emitted near-miss → `candidate-log-*.jsonl`. [reason] is why it was dropped
     * ("below-confidence" / "deduped" / "low-agreement"). Training data for confidence tuning.
     */
    fun logCandidate(transcript: String, translation: String, event: ScriptureEvent, reason: String) {
        val configured = path ?: return
        val target = datedFile(configured, CANDIDATE_PREFIX)
        cleanupOldLogsOnce(target.parentFile)
        runCatching {
            synchronized(lock) { target.appendText(lineFor(transcript, translation, event, reason) + "\n", Charsets.UTF_8) }
        }
    }

    /** Builds one JSONL row. [reason] null = emitted detection; non-null = near-miss candidate. */
    private fun lineFor(transcript: String, translation: String, event: ScriptureEvent, reason: String?): String {
        val r = event.reference
        return buildString {
            append('{')
            append("\"ts\":\"").append(Instant.now()).append("\",")
            append("\"ref\":\"").append(esc(r.displayRef)).append("\",")
            append("\"book\":").append(r.bookId).append(',')
            append("\"chapter\":").append(r.chapter).append(',')
            append("\"verseStart\":").append(r.verseStart).append(',')
            append("\"verseEnd\":").append(r.verseEnd ?: 0).append(',')
            append("\"source\":\"").append(esc(event.matchType)).append("\",")
            append("\"confidence\":").append(event.confidence).append(',')
            // Active aggressiveness level when this row was produced (off/conservative/balanced/aggressive).
            append("\"level\":\"").append(esc(Config.level)).append("\",")
            append("\"emitted\":").append(reason == null).append(',')
            if (reason != null) append("\"reason\":\"").append(esc(reason)).append("\",")
            // ── Diagnostics for tuning: which Bible matched, a stable group key, the decision
            //    sub-scores, and the speech/sticky context at the moment it fired. ──
            append("\"matchedBible\":\"").append(esc(event.translation)).append("\",")
            append("\"refKey\":\"").append(r.bookId).append(':').append(r.chapter).append(':').append(r.verseStart).append("\",")
            event.tier?.let { append("\"tier\":").append(it).append(',') }
            event.bm25Score?.let { append("\"bm25Score\":").append(it).append(',') }
            event.bm25Ratio?.let { append("\"bm25Ratio\":").append(it).append(',') }
            event.speechType?.let { append("\"speechType\":\"").append(esc(it)).append("\",") }
            event.stickyBook?.let { append("\"stickyBook\":").append(it).append(',') }
            event.stickyChapter?.let { append("\"stickyChapter\":").append(it).append(',') }
            append("\"agreement\":").append(AgreementScorer.score(event.verseText, transcript, translation)).append(',')
            append("\"coverageTranscript\":").append(AgreementScorer.coverage(event.verseText, transcript)).append(',')
            append("\"coverageTranslation\":").append(AgreementScorer.coverage(event.verseText, translation)).append(',')
            // Clock-free correlation key: ties this row to the STT transcript and the operator's
            // go-live log without any wall-clock/NTP. Null when STT didn't provide it.
            if (event.segmentId != null) append("\"segmentId\":\"").append(esc(event.segmentId)).append("\",")
            else append("\"segmentId\":null,")
            if (event.sttStartTime != null) append("\"sttStartTime\":").append(event.sttStartTime).append(',')
            else append("\"sttStartTime\":null,")
            // Which STT track(s) corroborated this detection: transcription / translation / both.
            append("\"tracks\":[")
            append(event.tracks.joinToString(",") { "\"" + esc(it) + "\"" })
            append("],")
            append("\"transcript\":\"").append(esc(transcript)).append("\",")
            append("\"translation\":\"").append(esc(translation)).append('"')
            append('}')
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
