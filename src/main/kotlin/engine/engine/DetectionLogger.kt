package engine.engine

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
 * One JSON object per line: {ts, ref, book, chapter, verse, source, confidence, segmentId,
 * sttStartTime, transcript, translation}.
 */
object DetectionLogger {
    @Volatile var path: String? = null

    private const val MAX_AGE_DAYS = 30L
    private const val BASE_PREFIX = "detection-log-"

    private val lock = Any()
    private val cleanedUp = AtomicBoolean(false)

    // Frozen at first use (≈ process start), down to the second so quick restarts never share a file.
    private val runStamp: String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    /** Per-run sibling of the configured [path], e.g. `<parent>/detection-log-2026-06-24_14-30-05.jsonl`. */
    private fun datedFile(configured: String): File {
        val parent = File(configured).absoluteFile.parentFile ?: File(".")
        return File(parent, "$BASE_PREFIX$runStamp.jsonl")
    }

    /** Deletes dated detection logs older than [MAX_AGE_DAYS] in [dir]. Once per process. */
    private fun cleanupOldLogsOnce(dir: File?) {
        if (dir == null || !cleanedUp.compareAndSet(false, true)) return
        runCatching {
            val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(BASE_PREFIX) && file.name.endsWith(".jsonl") &&
                    file.lastModified() < cutoff
                ) file.delete()
            }
        }
    }

    fun log(transcript: String, translation: String, event: ScriptureEvent) {
        val configured = path ?: return
        val target = datedFile(configured)
        cleanupOldLogsOnce(target.parentFile)
        runCatching {
            val r = event.reference
            val line = buildString {
                append('{')
                append("\"ts\":\"").append(Instant.now()).append("\",")
                append("\"ref\":\"").append(esc(r.displayRef)).append("\",")
                append("\"book\":").append(r.bookId).append(',')
                append("\"chapter\":").append(r.chapter).append(',')
                append("\"verseStart\":").append(r.verseStart).append(',')
                append("\"verseEnd\":").append(r.verseEnd ?: 0).append(',')
                append("\"source\":\"").append(esc(event.matchType)).append("\",")
                append("\"confidence\":").append(event.confidence).append(',')
                // Clock-free correlation key: ties this detection to the STT transcript row and the
                // operator's go-live log without any wall-clock/NTP. Null when STT didn't provide it.
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
            synchronized(lock) { target.appendText(line + "\n", Charsets.UTF_8) }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
