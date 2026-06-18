package engine.socket

import engine.engine.ScriptureEvent
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArraySet

class Broadcaster {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()
    private val json = Json { encodeDefaults = true }

    fun register(session: WebSocketSession) { sessions.add(session) }
    fun unregister(session: WebSocketSession) { sessions.remove(session) }

    suspend fun broadcast(event: ScriptureEvent) {
        val frame = Frame.Text(json.encodeToString(event))
        for (session in sessions) {
            runCatching { session.send(frame) }
        }
    }
}
