# bible-engine

A standalone Kotlin/JVM microservice that listens to live speech-to-text streams and detects Bible references in real time. It serves scripture events back to a client (e.g. ChurchPresenter) over WebSocket.

## What it does

Given a live transcript like *"for God so loved the world"*, it emits:

```json
{
  "type": "scripture.detected",
  "id": "utterance_123",
  "reference": {
    "bookId": 43,
    "bookName": "John",
    "chapter": 3,
    "verseStart": 16,
    "verseEnd": null,
    "displayRef": "John 3:16",
    "canonicalCodeStart": "B043C003V016",
    "canonicalCodeEnd": null,
    "numbering": "hebrew"
  },
  "verseText": "For God so loved the world, that he gave his only begotten Son...",
  "confidence": 0.90,
  "matchType": "reverse",
  "translation": "KJV"
}
```

Detection runs in three stages in priority order:

1. **Explicit parser** — recognises spoken references like *"John three sixteen"*, *"Psalm 51 verse 1"*, *"Иоанна 3 глава 16 стих"* (English + Russian).
2. **Reverse BM25 lookup** — matches verse text against the transcript. Uses posting-list intersection so only verses containing every query token are ranked, giving precise results even for ambiguous phrases.
3. **Continuation engine** — after a verse is detected, checks whether the next few words match the following verse(s) to track the speaker's position.

Results are gated by a stabiliser: a 32-entry dedup ring buffer and a minimum confidence threshold (0.4) prevent duplicate or low-quality events.

## Requirements

- Java 21 (tested with Eclipse Adoptium JDK 21)
- Gradle 9.4 (wrapper included — no separate install needed)
- Bible corpus: SPB files (configured via `bible-engine.properties` or auto-discovered from ChurchPresenter settings)

## Quick start

```bash
# Build and run
./gradlew run

# Run tests
./gradlew test

# Build a fat JAR
./gradlew jar
java -jar build/libs/bible-engine-1.0.0.jar
```

On first run, `bible-engine.properties` is created in the same directory as the JAR (or current directory when using `./gradlew run`). Edit it before restarting.

## Architecture

```
[STT server]  ←── Socket.IO ──→  [ChurchPresenter]
     │
     └── Socket.IO ──→  [bible-engine]  ──→  ws://localhost:8765/bible-engine
```

bible-engine is a **Socket.IO client** for input — it connects to the same STT server as ChurchPresenter. No duplicate calls are made to the STT server.

It is a **WebSocket server** for output — ChurchPresenter (or any client) connects to it to receive scripture events.

## Configuration

Edit `bible-engine.properties` next to the JAR:

```properties
# Socket.IO STT server URL.
# Leave blank for standalone WebSocket input mode.
stt.server.url=http://localhost:5000

# Path to Bible SPB files folder.
# Leave blank to auto-discover from ~/.churchpresenter/settings.json
bible.root=

# WebSocket output server port
output.port=8765
```

### Bible path resolution order

1. `--bible-root` CLI argument
2. `bible.root` in `bible-engine.properties`
3. Auto-discovery from `~/.churchpresenter/settings.json` → `bibleSettings.storageDirectory`
4. Error — must configure one of the above

### CLI arguments (override config file)

| Argument | Effect |
|---|---|
| `--stt-url http://host:port` | Connect to STT server as Socket.IO client |
| `--bible-root /path/to/bibles` | Use this Bible folder |
| `--port 8765` | WebSocket output server port |

## Input modes

### Socket.IO mode (alongside ChurchPresenter)

Set `stt.server.url` in `bible-engine.properties`. bible-engine connects to the STT server, receives `transcription_update` / `translation_update` events, and emits scripture events to all WebSocket output clients.

### WebSocket input mode (standalone / testing)

Leave `stt.server.url` blank. Connect to `ws://localhost:8765/bible-engine` and send messages directly:

**Client → server**

| Message | Fields | Description |
|---|---|---|
| `transcription_update` | `id`, `text` | Live speech-to-text (original language) |
| `translation_update` | `id`, `text` | Simultaneous translation of the same utterance |
| `ping` | — | Keepalive; server replies with `{"type":"pong"}` |

## Output events

**Server → client** (both modes)

| `type` | Meaning |
|---|---|
| `scripture.detected` | New reference found |
| `scripture.updated` | Same reference re-scored with higher confidence |
| `scripture.continuation` | Speaker has moved to the next verse |

All output events share the JSON shape shown above.

## Tuning parameters

Edit `src/main/kotlin/engine/Config.kt`:

| Key | Default | Description |
|---|---|---|
| `defaultTranslations` | `["ENG_KJV", "RUS_RST"]` | Translations indexed for BM25 |
| `reverseMinScoreRatio` | `2.0` | Min top/second BM25 ratio for partial-match fallback |
| `continuationTimeoutMs` | `30000` | Time after last detection before continuation resets |
| `dedupWindow` | `32` | Ring-buffer size for dedup |
| `minConfidenceEmit` | `0.4` | Minimum confidence to emit any event |
| `bm25K1` / `bm25B` | `1.5` / `0.75` | BM25 tuning parameters |

## Project structure

```
src/main/kotlin/engine/
├── Main.kt                  # Entry point — loads config, discovers Bible path, starts servers
├── Config.kt                # Runtime-settable tunables
├── AppConfig.kt             # Config file loading and ChurchPresenter settings discovery
├── bible/
│   ├── BibleModels.kt       # EngineVerse, EngineBook, EngineTranslation
│   ├── SpbLoader.kt         # SPB parser; loadDefaults() is memory-efficient
│   └── BibleIndex.kt        # BM25 inverted index with searchAllTerms()
├── detection/
│   ├── BookResolver.kt      # Book name/abbreviation → book number (EN + RU)
│   ├── ExplicitParser.kt    # Spoken reference parser
│   ├── ReverseLookup.kt     # BM25 query with full-coverage preference
│   └── ContinuationEngine.kt
├── engine/
│   ├── UtteranceState.kt    # Per-utterance transcript + translation state
│   ├── AgreementScorer.kt   # Word-overlap scorer for continuation
│   ├── Stabilizer.kt        # Dedup ring buffer + confidence gate
│   └── DetectionEngine.kt   # Orchestrates the three-stage pipeline
└── socket/
    ├── Broadcaster.kt       # Thread-safe registry of connected WebSocket sessions
    ├── SocketHandler.kt     # Ktor WebSocket route (input + output)
    └── SttSocketClient.kt   # Socket.IO client for STT server input
```

## Bible corpus (SPB format)

The engine reads `.spb` files — tab-delimited, pre-resolved verse files. Each verse line:

```
B043C003V016	43	3	16	For God so loved the world...
```

Columns: canonical code, book number, display chapter, display verse, text.

All 72 translations store **Hebrew chapter/verse numbers** in the display columns. Russian/Orthodox translations (RST etc.) carry `"numbering": "lxx"` as an informational field for the client — no server-side number conversion is performed.

Only the two `defaultTranslations` are loaded for BM25 indexing (~62 K verses).

## Psalm / LXX numbering

Psalms are numbered as stored in each SPB file's display columns. The server outputs whatever number the SPB says; it never converts between Hebrew and LXX numbering. The `numbering` field in every event tells the client which tradition to display.

Verse 0 (superscriptions/headers) are indexed but never returned in detection output.
