package engine

import engine.bible.SpbLoader
import engine.detection.BookResolver
import engine.engine.DetectionEngine
import engine.socket.Broadcaster
import engine.socket.SttSocketClient
import engine.socket.bibleEngineSocket
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*

/** Handle to a running engine instance; call [stop] to shut it (and its STT client) down. */
class EngineHandle internal constructor(private val stopFn: () -> Unit) {
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
        Config.outputPort = port

        // Book names are registered from every SPB (cheap header scan), but full verse data + BM25
        // index are built only for the requested bibles (ChurchPresenter's primary + secondary).
        BookResolver.register(SpbLoader.scanAllBookManifests())
        val translations = SpbLoader.loadSelected(bibleFiles)
        if (translations.isEmpty()) {
            System.err.println("bible-engine: no translations loaded from $bibleRoot")
            return null
        }
        val detectionEngine = DetectionEngine(translations)
        val broadcaster = Broadcaster()

        val sttClient: SttSocketClient? =
            if (sttUrl.isNotBlank()) SttSocketClient(sttUrl, detectionEngine, broadcaster).also { it.connect() }
            else null

        val server = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriodMillis = 30_000
                timeoutMillis = 60_000
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing { bibleEngineSocket(detectionEngine, broadcaster) }
        }.start(wait = false)

        return EngineHandle {
            runCatching { sttClient?.disconnect() }
            runCatching { server.stop(500, 1000) }
        }
    }
}
