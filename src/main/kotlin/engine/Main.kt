package engine

import engine.bible.SpbLoader
import engine.engine.DetectionEngine
import engine.socket.Broadcaster
import engine.socket.SttSocketClient
import engine.socket.bibleEngineSocket
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*

fun main(args: Array<String>) {
    // 1. Load config file (creates default on first run)
    val configFile = AppConfig.findConfigFile()
    AppConfig.load(configFile)

    // 2. CLI args override config file values
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--stt-url"    -> { Config.sttServerUrl = args.getOrElse(i + 1) { "" }; i += 2 }
            "--bible-root" -> { Config.bibleRoot    = args.getOrElse(i + 1) { "" }; i += 2 }
            "--port"       -> { args.getOrNull(i + 1)?.toIntOrNull()?.let { Config.outputPort = it }; i += 2 }
            else -> i++
        }
    }

    // 3. Discover Bible root from ChurchPresenter settings if not supplied
    if (Config.bibleRoot.isBlank()) {
        Config.bibleRoot = AppConfig.discoverBibleRoot() ?: ""
    }
    if (Config.bibleRoot.isBlank()) {
        System.err.println(
            "ERROR: Bible root not configured.\n" +
            "  Set 'bible.root' in ${configFile.absolutePath}\n" +
            "  or pass --bible-root <path>"
        )
        return
    }

    // 4. Load translations and build detection engine
    println("Loading translations from ${Config.bibleRoot} ...")
    val translations = SpbLoader.loadDefaults()
    if (translations.isEmpty()) {
        System.err.println("No translations loaded — check bible.root path and that .spb files exist there.")
        return
    }
    println("Loaded: ${translations.joinToString(", ") { it.id }}")

    println("Building BM25 index ...")
    val detectionEngine = DetectionEngine(translations)

    // 5. Create broadcaster (shared between WebSocket output and Socket.IO input)
    val broadcaster = Broadcaster()

    // 6. Start Socket.IO client if STT server URL is configured
    var sttClient: SttSocketClient? = null
    if (Config.sttServerUrl.isNotBlank()) {
        println("Connecting to STT server: ${Config.sttServerUrl}")
        sttClient = SttSocketClient(Config.sttServerUrl, detectionEngine, broadcaster)
        sttClient.connect()
    } else {
        println("No stt.server.url set — using WebSocket input mode.")
        println("Send transcription_update messages to ws://localhost:${Config.outputPort}/bible-engine")
    }

    println("Starting WebSocket output on ws://localhost:${Config.outputPort}/bible-engine\n")

    embeddedServer(Netty, port = Config.outputPort) {
        install(WebSockets) {
            pingPeriodMillis = 30_000
            timeoutMillis = 60_000
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            bibleEngineSocket(detectionEngine, broadcaster)
        }
    }.start(wait = true)

    sttClient?.disconnect()
}
