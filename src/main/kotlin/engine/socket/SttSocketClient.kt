package engine.socket

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
            println("Connected to STT server: $serverUrl")
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            println("Disconnected from STT server")
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            System.err.println("STT server connection error: ${args.firstOrNull()}")
        }

        socket.on("transcription_update") { args ->
            extractTranscriptText(args)?.let { text ->
                val events = detectionEngine.processTranscription("live-transcript", text)
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.on("translation_update") { args ->
            extractTranslationText(args)?.let { text ->
                val events = detectionEngine.processTranslation("live-translation", text)
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.connect()
    }

    fun disconnect() {
        if (::socket.isInitialized) socket.disconnect()
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
