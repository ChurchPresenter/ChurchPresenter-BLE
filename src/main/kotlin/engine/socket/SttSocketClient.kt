package engine.socket

import engine.Config
import engine.engine.DetectionEngine
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class SttSocketClient(
    private val serverUrl: String,
    private val detectionEngine: DetectionEngine,
    private val broadcaster: Broadcaster,
) {
    private lateinit var socket: Socket

    fun connect() {
        val options = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .build()

        socket = IO.socket(serverUrl, options)

        socket.on(Socket.EVENT_CONNECT) {
            if (Config.verboseLog) println("Connected to STT server: $serverUrl")
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            if (Config.verboseLog) println("Disconnected from STT server")
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            System.err.println("STT server connection error: ${args.firstOrNull()}")
        }

        // Both streams feed ONE utterance ("live") so the detector sees the transcript AND its
        // translation together (cross-language corroboration + shared sticky context). Using two ids
        // here previously split them into independent states that never combined.
        socket.on("transcription_update") { args ->
            extractTranscriptText(args)?.let { text ->
                val events = detectionEngine.processTranscription(
                    "live", text, extractSpeechType(args), extractSegmentId(args), extractStartTime(args)
                )
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.on("translation_update") { args ->
            extractTranslationText(args)?.let { text ->
                val events = detectionEngine.processTranslation(
                    "live", text, extractSpeechType(args), extractSegmentId(args), extractStartTime(args)
                )
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.connect()
    }

    fun disconnect() {
        if (::socket.isInitialized) socket.disconnect()
    }

    // Best-effort speech_type from the payload (e.g. "Speaking"/"Quiet"/"Music"); null if absent so
    // detection behaves unchanged until the STT stream provides it. Drives the music precision gate.
    private fun extractSpeechType(args: Array<Any>): String? {
        val payload = args.firstOrNull() as? JSONObject ?: return null
        return payload.optString("speech_type", "").trim().takeIf { it.isNotEmpty() }
    }

    // The STT segment id that produced this update — the clock-free correlation key matching the STT
    // db's `segment_id` column (TEXT = str(id)). Prefers an explicit string `segment_id`, then falls
    // back to the integer `id` (the db primary key the socket already emits) stringified — so it
    // matches the db exactly even before the STT app ships the canonical string field. Probe order:
    // in-progress (newest) → latest completed segment → top-level. Null only if nothing is present.
    private fun extractSegmentId(args: Array<Any>): String? {
        val payload = args.firstOrNull() as? JSONObject ?: return null
        (payload.opt("in_progress") as? JSONObject)?.let { segmentIdOf(it)?.let { id -> return id } }
        val segments = payload.optJSONArray("segments")
        if (segments != null && segments.length() > 0) {
            segments.optJSONObject(segments.length() - 1)?.let { segmentIdOf(it)?.let { id -> return id } }
        }
        return segmentIdOf(payload)
    }

    // `segment_id` (string) if present, else `str(id)` from the integer PK; null if neither exists.
    private fun segmentIdOf(obj: JSONObject): String? {
        obj.optString("segment_id", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
        if (obj.has("id") && !obj.isNull("id")) return obj.optInt("id").toString()
        return null
    }

    // Session-relative start time of the segment above (seconds from session start). Reads the
    // canonical `start_time`, falling back to `start` (the field today's payload uses). Best-effort.
    private fun extractStartTime(args: Array<Any>): Double? {
        val payload = args.firstOrNull() as? JSONObject ?: return null
        (payload.opt("in_progress") as? JSONObject)?.let { startTimeOf(it)?.let { t -> return t } }
        val segments = payload.optJSONArray("segments")
        if (segments != null && segments.length() > 0) {
            segments.optJSONObject(segments.length() - 1)?.let { startTimeOf(it)?.let { t -> return t } }
        }
        return startTimeOf(payload)
    }

    private fun startTimeOf(obj: JSONObject): Double? = when {
        obj.has("start_time") -> obj.optDouble("start_time")
        obj.has("start") -> obj.optDouble("start")
        else -> null
    }

    // Concat last 2 completed transcript segments + in_progress
    private fun extractTranscriptText(args: Array<Any>): String? {
        val payload = args.firstOrNull() as? JSONObject ?: return null
        val parts = mutableListOf<String>()
        val segments = payload.optJSONArray("segments")
        if (segments != null) {
            val start = maxOf(0, segments.length() - 2)
            for (i in start until segments.length()) {
                segments.optJSONObject(i)?.optString("text", "")
                    ?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
        }
        when (val ip = payload.opt("in_progress")) {
            is String -> ip.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            is JSONObject -> ip.optString("text", "").trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        return parts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    // Concat last 2 completed translated segments + in_progress
    private fun extractTranslationText(args: Array<Any>): String? {
        val payload = args.firstOrNull() as? JSONObject ?: return null
        val parts = mutableListOf<String>()
        val segments = payload.optJSONArray("segments")
        if (segments != null) {
            val start = maxOf(0, segments.length() - 2)
            for (i in start until segments.length()) {
                segments.optJSONObject(i)?.optString("translated_text", "")
                    ?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
        }
        when (val ip = payload.opt("in_progress")) {
            is String -> ip.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            is JSONObject -> ip.optString("translated_text", "").trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        return parts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }
}
