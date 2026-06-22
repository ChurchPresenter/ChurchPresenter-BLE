# BLE (Bible Lookup Engine)

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

The transcript and its translation are fed together (see [Pipeline](#pipeline)), then detection runs in three stages in priority order:

1. **Reference watcher** (`ReferenceWatcher`) — a stateful parser for spoken references like *"John chapter 3 verse 16"*, *"Matthäus Kapitel 5"*, *"Juan 3:16"*, *"Іоанна 3:16"*. It also carries a **sticky** book+chapter across utterances, so a later bare *"verse 8"* during a verse-by-verse reading still resolves. Each hit carries an evidence tier — **1 FULL** (book+chapter+verse), **2 PARTIAL** (book+chapter), **3 STICKY** (resolved against the carried context). Supports English, Russian, German, French, Spanish, Portuguese, Ukrainian, Romanian, and Polish out of the box, plus book names auto-loaded from every SPB file at startup. Detection is **suppressed on music segments** (`speech_type = Music`).
2. **Reverse BM25 lookup** — matches the live transcript against indexed verse text. Uses posting-list intersection so only verses that contain every query token are ranked, and validates a hit against what was actually spoken (agreement scorer). Gated by the **aggressiveness level** (see [Aggressiveness levels](#aggressiveness-levels)). Fully language-agnostic.
3. **Continuation engine** — after a verse is detected, tracks whether the next words match the following verse(s) to follow the speaker's position.

Results are gated by a stabiliser: a time-based dedup window and a minimum confidence threshold (0.4) prevent duplicate or low-quality events.

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
                      Socket.IO: transcription_update + translation_update (+ speech_type)
   [STT server] ───────────────────────────────────────────────────────────────► [BLE]
        ▲                                                                            │
        │ Socket.IO (its own captions)                          scripture.* events  │  ws://…/bible-engine
        │                                                                           ▼
  [ChurchPresenter] ◄──────────────────────────────────────────────────────── [BLE]  ──► [other clients]
        │
        └── starts BLE in-process (EngineServer.start) + pushes set_tuning {level} ──► [BLE]
```

BLE has three links:

- **Input — Socket.IO client to the STT server.** It connects to the *same* STT server as ChurchPresenter (no duplicate calls) and receives **both** the `transcription_update` (original language) and `translation_update` (simultaneous translation) streams for the live utterance.
- **Output — WebSocket server.** Any number of clients connect to `ws://<host>:8765/bible-engine` to receive `scripture.*` events. ChurchPresenter is one such client.
- **Control — from ChurchPresenter.** ChurchPresenter starts BLE **in-process** (`EngineServer.start(sttUrl, bibleRoot, port, bibleFiles)`) when its STT connects, telling it which STT server to use and which bibles (its primary + secondary) to index. Over the same `/bible-engine` WebSocket it pushes the aggressiveness chip as `set_tuning {level}`. BLE connects to the STT server directly — ChurchPresenter does **not** relay the transcript stream.

## Pipeline

How one live utterance flows through the engine:

```
STT server
  ├─ transcription_update  (original, e.g. Russian)   ┐
  └─ translation_update    (translation, e.g. English)┘
            │  (SttSocketClient — both streams share one utterance id "live")
            ▼
   combined "transcript + translation"        ← cross-language corroboration + shared sticky context
            │  speech_type = Music?  → suppressed (no detection, sticky untouched)
            ▼
   1. ReferenceWatcher   explicit + sticky reference, evidence tier 1/2/3
   2. Reverse BM25       (level-gated) verse-text match, validated by agreement scorer
   3. Continuation       follows the speaker into the next verse(s)
            │
            ▼
   Stabiliser            time-based dedup + min-confidence gate
            │
            ▼
   scripture.* event  ──►  WebSocket /bible-engine  ──►  ChurchPresenter (auto-follow / display) + any client
```

Both input modes below (Socket.IO and direct WebSocket push) feed the **same** pipeline.

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

Set `stt.server.url` in `bible-engine.properties` (or let ChurchPresenter start the engine in-process with the URL). BLE connects to the STT server as a second client and receives **both** `transcription_update` and `translation_update`; the two are merged into one utterance and run through the [pipeline](#pipeline) together. Scripture events are emitted to all connected WebSocket output clients.

### WebSocket input mode (standalone / testing)

Leave `stt.server.url` blank. Connect to `ws://localhost:8765/bible-engine` and push messages directly — they feed the same pipeline:

| Message | Fields | Description |
|---|---|---|
| `transcription_update` | `id`, `text` | Live speech-to-text (original language). Pair with the same `id` as its translation to combine them. |
| `translation_update` | `id`, `text` | Simultaneous translation of the same utterance |
| `set_tuning` | `level` | Set the aggressiveness level (`off`/`conservative`/`balanced`/`aggressive`) |
| `ping` | — | Keepalive; server replies with `{"type":"pong"}` |

### Aggressiveness levels

ChurchPresenter pushes a level via `set_tuning` (the level chip); it maps to detection tuning in `Config.applyLevel`:

| Level | Reverse BM25 | Notes |
|---|---|---|
| `off` | disabled | Explicit + sticky watcher only |
| `conservative` | on, strict thresholds | Longest sticky TTL; spelling normalization off |
| `balanced` | on (default) | Default thresholds; STT spelling normalization on |
| `aggressive` | on, loose thresholds | Shortest sticky TTL; also infers a book named after its numbers |

The music gate (`speech_type = Music` → no detection) applies at every level.

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
| `defaultTranslations` | `[]` (empty = index all) | Optional BM25 allow-list; ChurchPresenter passes its primary + secondary bibles instead |
| `reverseMinScoreRatio` | `2.0` | Min top/second BM25 score ratio for partial-match fallback |
| `continuationTimeoutMs` | `30000` | ms of silence before continuation tracking resets |
| `dedupTtlMs` | `45000` | Time window that suppresses a repeat of the same reference |
| `minConfidenceEmit` | `0.4` | Minimum confidence to emit any event |
| `stickyTtlMs` | `180000` | How long an announced book+chapter stays sticky (varies by level) |
| `bm25K1` / `bm25B` | `1.5` / `0.75` | BM25 tuning parameters |

Most thresholds are also set as a group by the aggressiveness level — see [Aggressiveness levels](#aggressiveness-levels).

## Project structure

```
src/main/kotlin/engine/
├── Main.kt                  # Entry point (standalone) — config, Bible path discovery, startup
├── EngineServer.kt          # In-process start/stop API (used by ChurchPresenter and Main)
├── Config.kt                # Runtime-settable tunables + applyLevel (aggressiveness)
├── AppConfig.kt             # Config file loading, ChurchPresenter settings discovery
├── bible/
│   ├── BibleModels.kt       # EngineVerse, EngineBook, EngineTranslation
│   ├── SpbLoader.kt         # SPB parser + fast book-manifest scanner
│   └── BibleIndex.kt        # BM25 inverted index with full-coverage search
├── detection/
│   ├── BookResolver.kt      # Book name/abbreviation → book number; multilingual + SPB-derived
│   ├── NumberWords.kt       # Digits / ordinals / number words → Int
│   ├── ReferenceWatcher.kt  # Stateful explicit + sticky reference detector (live path)
│   ├── ReverseLookup.kt     # BM25 query with full-coverage preference
│   ├── ContinuationEngine.kt
│   └── ExplicitParser.kt    # Legacy single-string parser (test-only; superseded by ReferenceWatcher)
├── engine/
│   ├── UtteranceState.kt    # Per-utterance transcript + translation + sticky context
│   ├── AgreementScorer.kt   # Word-overlap scorer (validates reverse + sticky)
│   ├── Stabilizer.kt        # Time-based dedup + confidence gate
│   ├── DetectionLogger.kt   # Appends each emission + triggering text to detection-log.jsonl
│   └── DetectionEngine.kt   # Orchestrates the pipeline (watcher → reverse → continuation)
└── socket/
    ├── Broadcaster.kt       # Thread-safe registry of connected WebSocket sessions
    ├── SocketHandler.kt     # Ktor WebSocket route (input + output)
    └── SttSocketClient.kt   # Socket.IO client for STT server input (transcription + translation)
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
