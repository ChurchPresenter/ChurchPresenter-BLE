package engine.engine

import java.io.File
import java.time.Instant

/**
 * Appends every emitted detection — with the triggering transcript/translation — to a JSONL file,
 * turning each live service into labeled-ish regression data without manual annotation. Disabled
 * unless [path] is set (EngineServer points it at the bible root). Best-effort; never throws into
 * the detection path.
 *
 * One JSON object per line: {ts, ref, book, chapter, verse, source, confidence, transcript, translation}.
 */
object DetectionLogger {
    @Volatile var path: String? = null

    private val lock = Any()

    fun log(transcript: String, translation: String, event: ScriptureEvent) {
        val p = path ?: return
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
                append("\"transcript\":\"").append(esc(transcript)).append("\",")
                append("\"translation\":\"").append(esc(translation)).append('"')
                append('}')
            }
            synchronized(lock) { File(p).appendText(line + "\n", Charsets.UTF_8) }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
