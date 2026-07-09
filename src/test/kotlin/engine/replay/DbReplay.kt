package engine.replay

import engine.Config
import engine.bible.EngineTranslation
import engine.bible.SpbLoader
import engine.detection.BookResolver
import engine.engine.DetectionEngine
import engine.engine.DetectionLogger
import engine.engine.ScriptureEvent
import engine.socket.windowedText
import java.sql.DriverManager
import kotlin.math.roundToInt

/**
 * Replays a recorded STT service database through the real detection pipeline, producing one
 * privacy-safe JSONL line per emitted event (refs only, never transcript text — safe to commit
 * as a golden file).
 *
 * Determinism: the engine is constructed with a clock that returns the current row's `ts_ms`,
 * so every time-dependent gate (sticky TTL, dedup TTL, continuation timeout, re-emit cooldown)
 * is a pure function of the database. Two replays of the same db yield byte-identical output.
 *
 * Text shaping mirrors the live socket path exactly: both tracks are windowed with
 * [engine.socket.windowedText] (last two finalized rows + in-progress), the same function
 * `SttSocketClient` uses via SttPayload.kt.
 *
 * NOTE on fidelity: the db stores finalized rows (plus optionally in-progress snapshots via
 * `is_final = 0`); the live session also saw transient partials that were never persisted, so a
 * replay is not expected to reproduce the live detection-log exactly. Golden comparisons are
 * always replay-vs-replay.
 */
object DbReplay {

    data class Row(
        val id: Long,
        val tsMs: Long,
        val text: String?,
        val translated: String?,
        val speechType: String?,
        val segmentId: String?,
        val sessionId: String?,
        val startTime: Double?,
        val isFinal: Boolean,
        val denied: Boolean,
    )

    data class ReplayResult(
        val lines: List<String>,
        val events: List<ScriptureEvent>,
        /** ts_ms of the row that produced each event (parallel to [events]). */
        val eventTsMs: List<Long>,
    )

    /** Loads translations + registers manifests the same way EngineServer.start does. */
    fun loadTranslations(bibleRoot: String, bibleFiles: List<String>): List<EngineTranslation> {
        Config.bibleRoot = bibleRoot
        BookResolver.register(SpbLoader.scanAllBookManifests())
        return if (bibleFiles.isEmpty()) SpbLoader.loadDefaults() else SpbLoader.loadSelected(bibleFiles)
    }

    fun readRows(dbPath: String, includeDenied: Boolean = false): List<Row> {
        val rows = ArrayList<Row>(1024)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery(
                    "SELECT id, ts_ms, text, translated_text, speech_type, segment_id, session_id, " +
                        "start_time, is_final, denied FROM transcriptions ORDER BY ts_ms, id"
                )
                var lastTs = 0L
                while (rs.next()) {
                    val denied = rs.getInt("denied") == 1
                    if (denied && !includeDenied) continue
                    var ts = rs.getLong("ts_ms")
                    // Old rows may predate the ts_ms column — keep time monotonic so TTL gates
                    // still behave sensibly.
                    if (rs.wasNull() || ts <= 0L) ts = lastTs + 1_000
                    lastTs = ts
                    rows.add(
                        Row(
                            id = rs.getLong("id"),
                            tsMs = ts,
                            text = rs.getString("text")?.trim()?.takeIf { it.isNotEmpty() },
                            translated = rs.getString("translated_text")?.trim()?.takeIf { it.isNotEmpty() },
                            speechType = rs.getString("speech_type")?.trim()?.takeIf { it.isNotEmpty() },
                            segmentId = rs.getString("segment_id")?.trim()?.takeIf { it.isNotEmpty() }
                                ?: rs.getLong("id").toString(),
                            sessionId = rs.getString("session_id")?.trim()?.takeIf { it.isNotEmpty() },
                            startTime = rs.getDouble("start_time").takeIf { !rs.wasNull() },
                            isFinal = rs.getInt("is_final") != 0,
                            denied = denied,
                        )
                    )
                }
            }
        }
        return rows
    }

    fun replay(rows: List<Row>, translations: List<EngineTranslation>, level: String): ReplayResult {
        Config.applyLevel(level)
        val previousLogPath = DetectionLogger.path
        DetectionLogger.path = null // logging disabled during replay
        try {
            var clockNow = rows.firstOrNull()?.tsMs ?: 0L
            val engine = DetectionEngine(translations, clock = { clockNow })

            val txWindow = ArrayDeque<String>()
            val trWindow = ArrayDeque<String>()
            val lines = ArrayList<String>()
            val events = ArrayList<ScriptureEvent>()
            val eventTs = ArrayList<Long>()

            fun push(window: ArrayDeque<String>, text: String) {
                window.addLast(text)
                while (window.size > 2) window.removeFirst()
            }

            for (row in rows) {
                clockNow = row.tsMs
                val emitted = ArrayList<ScriptureEvent>()
                if (row.text != null) {
                    val windowed = if (row.isFinal) {
                        push(txWindow, row.text)
                        windowedText(txWindow.toList(), null)
                    } else {
                        windowedText(txWindow.toList(), row.text)
                    }
                    if (windowed != null) {
                        emitted += engine.processTranscription(
                            "live", windowed, row.speechType, row.segmentId, row.startTime, row.sessionId
                        )
                    }
                }
                if (row.translated != null) {
                    val windowed = if (row.isFinal) {
                        push(trWindow, row.translated)
                        windowedText(trWindow.toList(), null)
                    } else {
                        windowedText(trWindow.toList(), row.translated)
                    }
                    if (windowed != null) {
                        emitted += engine.processTranslation(
                            "live", windowed, row.speechType, row.segmentId, row.startTime, row.sessionId
                        )
                    }
                }
                for (e in emitted) {
                    lines.add(lineFor(row, e))
                    events.add(e)
                    eventTs.add(row.tsMs)
                }
            }
            return ReplayResult(lines, events, eventTs)
        } finally {
            DetectionLogger.path = previousLogPath
        }
    }

    /** One privacy-safe JSONL line per emitted event: refs + scores only, no transcript text. */
    private fun lineFor(row: Row, e: ScriptureEvent): String {
        val ref = "${e.reference.bookId}:${e.reference.chapter}:${e.reference.verseStart}" +
            (e.reference.verseEnd?.let { "-$it" } ?: "")
        val conf = (e.confidence * 1000).roundToInt() / 1000.0
        return buildString {
            append("{\"row\":").append(row.id)
            append(",\"tsMs\":").append(row.tsMs)
            append(",\"segmentId\":").append(jsonStr(e.segmentId))
            append(",\"type\":").append(jsonStr(e.type))
            append(",\"matchType\":").append(jsonStr(e.matchType))
            append(",\"ref\":").append(jsonStr(ref))
            append(",\"conf\":").append(conf)
            append(",\"tier\":").append(e.tier ?: "null")
            append(",\"bible\":").append(jsonStr(e.translation))
            append(",\"tracks\":[").append(e.tracks.joinToString(",") { jsonStr(it) }).append("]")
            append("}")
        }
    }

    private fun jsonStr(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Snapshot of every mutable Config field the replay/tests touch — restore to avoid test bleed. */
    fun snapshotConfig(): Map<String, Any> = mapOf(
        "level" to Config.level,
        "bibleRoot" to Config.bibleRoot,
        "reverseMinScoreRatio" to Config.reverseMinScoreRatio,
        "reverseMinAgreement" to Config.reverseMinAgreement,
        "trackCoverageMin" to Config.trackCoverageMin,
        "dedupTtlMs" to Config.dedupTtlMs,
        "minConfidenceEmit" to Config.minConfidenceEmit,
        "stickyTtlMs" to Config.stickyTtlMs,
        "reverseEnabled" to Config.reverseEnabled,
        "logCandidates" to Config.logCandidates,
    )

    fun restoreConfig(snapshot: Map<String, Any>) {
        Config.level = snapshot["level"] as String
        Config.bibleRoot = snapshot["bibleRoot"] as String
        Config.reverseMinScoreRatio = snapshot["reverseMinScoreRatio"] as Double
        Config.reverseMinAgreement = snapshot["reverseMinAgreement"] as Double
        Config.trackCoverageMin = snapshot["trackCoverageMin"] as Double
        Config.dedupTtlMs = snapshot["dedupTtlMs"] as Long
        Config.minConfidenceEmit = snapshot["minConfidenceEmit"] as Double
        Config.stickyTtlMs = snapshot["stickyTtlMs"] as Long
        Config.reverseEnabled = snapshot["reverseEnabled"] as Boolean
        Config.logCandidates = snapshot["logCandidates"] as Boolean
    }
}
