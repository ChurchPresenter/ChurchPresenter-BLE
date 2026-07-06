package engine

import engine.bible.SpbLoader
import engine.detection.BookResolver
import engine.engine.DetectionEngine
import engine.engine.DetectionLogger
import engine.socket.Broadcaster
import engine.socket.SttSocketClient
import engine.socket.bibleEngineSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.io.File

/**
 * Handle to a running engine instance; call [stop] to shut it (and its STT client) down.
 * [boundPort] is the port the WS server actually bound (may differ from the requested one when that
 * was taken) — local clients connect there.
 */
class EngineHandle internal constructor(val boundPort: Int, private val stopFn: () -> Unit) {
    fun stop() { runCatching { stopFn() } }
}

/**
 * Starts the Bible Lookup Engine in-process, **non-blocking**, and returns a handle (or null on a
 * fatal config error). Used by [main] for standalone runs and by ChurchPresenter, which starts the
 * engine in-process when STT connects and talks to it over the WebSocket.
 */
object EngineServer {
    fun start(sttUrl: String, bibleRoot: String, port: Int, bibleFiles: List<String> = emptyList()): EngineHandle? {
        if (bibleRoot.isBlank()) {
            System.err.println("bible-engine: bible root not configured")
            return null
        }
        Config.bibleRoot = bibleRoot
        Config.sttServerUrl = sttUrl

        // Book names are registered from every SPB (cheap header scan), but full verse data + BM25
        // index are built only for the requested bibles (ChurchPresenter's primary + secondary).
        BookResolver.register(SpbLoader.scanAllBookManifests())
        val translations = SpbLoader.loadSelected(bibleFiles)
        if (translations.isEmpty()) {
            System.err.println("bible-engine: no translations loaded from $bibleRoot")
            return null
        }
        Config.loadedBibles = translations.map { it.id }
        val detectionEngine = DetectionEngine(translations)
        // Grow a labeled corpus for free: every emitted detection + its triggering text is appended
        // here, so each live service becomes regression data without manual annotation.
        DetectionLogger.path = File(bibleRoot, "detection-log.jsonl").absolutePath
        val broadcaster = Broadcaster()

        // Bind the WS server BEFORE connecting the STT client (so we never leak a detecting-but-
        // unreachable STT client). The requested port may be taken — most commonly because it
        // collides with ChurchPresenter's Companion server — so try a small range and use the first
        // free port. Local clients learn the actual port via EngineHandle.boundPort.
        val bound = (port until port + 10).firstNotNullOfOrNull { p ->
            runCatching {
                embeddedServer(Netty, port = p) {
                    install(WebSockets) {
                        pingPeriodMillis = 30_000
                        timeoutMillis = 60_000
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    routing { bibleEngineSocket(detectionEngine, broadcaster) }
                }.start(wait = false)
            }.getOrNull()?.let { it to p }
        }
        if (bound == null) {
            System.err.println("bible-engine: failed to bind WS server on ports $port..${port + 9}")
            return null
        }
        val (server, boundPort) = bound
        if (boundPort != port) System.err.println("bible-engine: port $port busy — bound on $boundPort instead")
        Config.outputPort = boundPort

        val sttClient: SttSocketClient? =
            try {
                if (sttUrl.isNotBlank()) SttSocketClient(sttUrl, detectionEngine, broadcaster).also { it.connect() }
                else null
            } catch (e: Exception) {
                System.err.println("bible-engine: failed to connect STT client — ${e.message}")
                runCatching { server.stop(500, 1000) }
                return null
            }

        return EngineHandle(boundPort) {
            runCatching { sttClient?.disconnect() }
            runCatching { server.stop(500, 1000) }
        }
    }
}
