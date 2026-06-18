# bible-engine — BLE (Bible Lookup Engine)

A standalone Kotlin/JVM microservice that listens to live speech-to-text streams and detects Bible references in real time. It serves scripture events over WebSocket to any connected client (e.g. ChurchPresenter).

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

1. **Explicit parser** — recognises spoken references like *"John chapter 3 verse 16"*, *"Matthäus Kapitel 5"*, *"Juan 3:16"*, *"Іоанна 3:16"*. Supports English, Russian, German, French, Spanish, Portuguese, Ukrainian, Romanian, and Polish out of the box. Book names are also auto-loaded from every SPB file at startup, so any language you have a translation for is covered automatically.
2. **Reverse BM25 lookup** — matches the live transcript against indexed verse text. Uses posting-list intersection so only verses that contain every query token are ranked. Fully language-agnostic — works for any language you load an SPB file for.
3. **Continuation engine** — after a verse is detected, tracks whether the next words match the following verse(s) to follow the speaker's position.

Results are gated by a stabiliser: a 32-entry dedup ring buffer and a minimum confidence threshold (0.4) prevent duplicate or low-quality events.

## Requirements

- Java 21 (Eclipse Adoptium JDK 21 recommended)
- Gradle 9.4 (wrapper included — no separate install needed)
- Bible corpus: one or more `.spb` files in the folder configured via `bible-engine.properties` or auto-discovered from ChurchPresenter settings

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

On first run, `bible-engine.properties` is created next to the JAR (or in the working directory when using `./gradlew run`). Edit it and restart.

## Architecture

```
[STT server]  ←── Socket.IO ──→  [ChurchPresenter]
     │
     └── Socket.IO ──→  [bible-engine]  ──→  ws://localhost:8765/bible-engine  ──→  [clients]
```

bible-engine is a **Socket.IO client** for input — it connects to the same STT server as ChurchPresenter. No duplicate calls are made.

It is a **WebSocket server** for output — any number of clients connect to receive scripture events.

## Configuration

Edit `bible-engine.properties` next to the JAR:

```properties
# Socket.IO STT server URL.
# Leave blank for standalone WebSocket input mode (testing with wscat etc.).
# Example: stt.server.url=http://localhost:5000
stt.server.url=

# Path to the Bible SPB files folder.
# Leave blank to auto-discover from ~/.churchpresenter/settings.json
# Example (Windows): bible.root=C:\Users\YourName\Documents\Bibles
# Example (Mac/Linux): bible.root=/home/yourname/Documents/Bibles
bible.root=

# WebSocket output server port
output.port=8765
```

### Bible path resolution order

1. `--bible-root` CLI argument
2. `bible.root` in `bible-engine.properties`
3. Auto-discovery from `~/.churchpresenter/settings.json` → `bibleSettings.storageDirectory`
4. Error — at least one of the above must be set

### CLI arguments (override config file)

| Argument | Effect |
|---|---|
| `--stt-url http://host:port` | Connect to STT server as Socket.IO client |
| `--bible-root /path/to/bibles` | Use this Bible folder |
| `--port 8765` | WebSocket output server port |

## Input modes

### Socket.IO mode (alongside ChurchPresenter)

Set `stt.server.url` in `bible-engine.properties`. bible-engine connects to the STT server as a second client, receives `transcription_update` / `translation_update` events, and emits scripture events to all connected WebSocket output clients.

### WebSocket input mode (standalone / testing)

Leave `stt.server.url` blank. Connect to `ws://localhost:8765/bible-engine` and push messages directly:

| Message | Fields | Description |
|---|---|---|
| `transcription_update` | `id`, `text` | Live speech-to-text (original language) |
| `translation_update` | `id`, `text` | Simultaneous translation of the same utterance |
| `ping` | — | Keepalive; server replies with `{"type":"pong"}` |

## Output events

Both input modes broadcast to all connected WebSocket clients.

| `type` | Meaning |
|---|---|
| `scripture.detected` | New reference detected |
| `scripture.updated` | Same reference re-scored with higher confidence |
| `scripture.continuation` | Speaker has moved to the next verse |

## Language support

### BM25 reverse lookup

Fully language-agnostic. Drop any SPB file into the Bible folder, set it as a `defaultTranslation`, and the engine will index and match its verse text. No code changes needed.

### Explicit reference parser

Recognises spoken references out of the box in:

| Language | Example |
|---|---|
| English | `John chapter 3 verse 16`, `Ps 51:1` |
| Russian | `Иоанна 3:16`, `Псалом 51 стих 1` |
| German | `Johannes Kapitel 3 Vers 16`, `1. Mose 1:1` |
| French | `Jean chapitre 3 verset 16`, `Matthieu 5:3` |
| Spanish | `Juan capítulo 3 versículo 16`, `Mateo 5:3` |
| Portuguese | `João 3:16`, `Mateus 5:3` |
| Ukrainian | `Івана 3:16`, `Матвія 5:3` |
| Romanian | `Ioan 3:16`, `Matei 5:3` |
| Polish | `Jana 3:16`, `Mateusza 5:3` |

In addition, **every SPB file's book manifest is scanned at startup** and its book names registered automatically. This means any translation you add — regardless of language — contributes its book names to the parser for free.

## Tuning

Edit `src/main/kotlin/engine/Config.kt`:

| Key | Default | Description |
|---|---|---|
| `defaultTranslations` | `["ENG_KJV", "RUS_RST"]` | Translations indexed for BM25 reverse lookup |
| `reverseMinScoreRatio` | `2.0` | Min top/second BM25 score ratio for partial-match fallback |
| `continuationTimeoutMs` | `30000` | ms of silence before continuation tracking resets |
| `dedupWindow` | `32` | Ring-buffer size for dedup |
| `minConfidenceEmit` | `0.4` | Minimum confidence to emit any event |
| `bm25K1` / `bm25B` | `1.5` / `0.75` | BM25 tuning parameters |

## Project structure

```
src/main/kotlin/engine/
├── Main.kt                  # Entry point — config, Bible path discovery, startup
├── Config.kt                # Runtime-settable tunables
├── AppConfig.kt             # Config file loading, ChurchPresenter settings discovery
├── bible/
│   ├── BibleModels.kt       # EngineVerse, EngineBook, EngineTranslation
│   ├── SpbLoader.kt         # SPB parser + fast book-manifest scanner
│   └── BibleIndex.kt        # BM25 inverted index with full-coverage search
├── detection/
│   ├── BookResolver.kt      # Book name/abbreviation → book number; multilingual + SPB-derived
│   ├── ExplicitParser.kt    # Spoken reference parser (9 languages + SPB auto-registration)
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

## SPB format

Tab-delimited bible corpus. Header section lists book names and chapter counts; verse data follows after `-----`:

```
##Title:    King James Version
##Abbreviation:    KJV
1	Genesis	50
2	Exodus	40
...
66	Revelation	22
-----
B043C003V016	43	3	16	For God so loved the world...
```

Verse columns: canonical code, book number, display chapter, display verse, text.

All display chapter/verse numbers are stored in **Hebrew numbering** regardless of translation tradition. Russian/Orthodox translations carry `"numbering": "lxx"` as an informational field for the client — no server-side conversion is performed.

Only the `defaultTranslations` are loaded for BM25 indexing. All SPB files are scanned (header only) at startup for book name registration.

## Psalm / LXX numbering

The server outputs whatever Psalm number the SPB stores. It never converts between Hebrew and LXX numbering. The `numbering` field in every output event tells the client which tradition to display.

Verse 0 entries (superscriptions) are indexed but never returned in detection output.
