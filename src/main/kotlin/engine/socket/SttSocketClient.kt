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
        // Payload parsing/windowing lives in SttPayload.kt, shared with the replay harness.
        socket.on("transcription_update") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            transcriptionUpdate(payload)?.let { u ->
                val events = detectionEngine.processTranscription(
                    "live", u.text, u.speechType, u.segmentId, u.startTime, u.sessionId
                )
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.on("translation_update") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            translationUpdate(payload)?.let { u ->
                val events = detectionEngine.processTranslation(
                    "live", u.text, u.speechType, u.segmentId, u.startTime, u.sessionId
                )
                runBlocking { for (e in events) broadcaster.broadcast(e) }
            }
        }

        socket.connect()
    }

    fun disconnect() {
        if (::socket.isInitialized) socket.disconnect()
    }
}
