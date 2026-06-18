package engine.socket

import engine.Config
import engine.engine.DetectionEngine
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.bibleEngineSocket(engine: DetectionEngine, broadcaster: Broadcaster) {
    webSocket("/bible-engine") {
        val remoteAddr = call.request.local.remoteHost
        println("WebSocket connected: $remoteAddr")
        broadcaster.register(this)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()

                val obj = try {
                    json.parseToJsonElement(raw).jsonObject
                } catch (e: Exception) {
                    System.err.println("Invalid JSON from $remoteAddr: $raw")
                    continue
                }

                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""

                when (type) {
                    "ping" -> send(Frame.Text("""{"type":"pong"}"""))
                    "set_tuning" -> {
                        val level = obj["level"]?.jsonPrimitive?.contentOrNull
                        if (level != null) Config.applyLevel(level)
                    }
                    "transcription_update" -> {
                        if (id.isBlank()) continue
                        engine.processTranscription(id, text).forEach { broadcaster.broadcast(it) }
                    }
                    "translation_update" -> {
                        if (id.isBlank()) continue
                        engine.processTranslation(id, text).forEach { broadcaster.broadcast(it) }
                    }
                }
            }
        } catch (e: Exception) {
            println("WebSocket error ($remoteAddr): ${e.message}")
        } finally {
            broadcaster.unregister(this)
            println("WebSocket disconnected: $remoteAddr")
        }
    }
}
