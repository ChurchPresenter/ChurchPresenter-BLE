# Bible Reference Detection — Improvement Plan

Reference doc for the reworked explicit-reference stage of the BLE engine. Captures the
stateful detector design, how it stays robust across speaking/transcription styles, the
validation strategy, and the work still outstanding. Driven by real STT data (archived service
`.db` files). **Primary language is Russian** (see project memory).

Status: **CORE IMPLEMENTED** (70 unit tests green). Data-dependent tuning + a few items remain —
see §6. More `.db` backups to come.

### Implemented so far (commit this branch)
New files:
- `detection/NumberWords.kt` (+ `test/NumberWordsTest.kt`) — digits, digit-ordinals, RU/EN number words.
- `detection/ReferenceWatcher.kt` (+ `test/ReferenceWatcherTest.kt`) — stateful evidence-tiered watcher.
- `engine/DetectionLogger.kt` — appends every emission + triggering text to `detection-log.jsonl`.

Changed files:
- `socket/SttSocketClient.kt` — both streams now feed ONE utterance id `"live"` (Bug #1).
- `engine/DetectionEngine.kt` — explicit stage = ReferenceWatcher; AgreementScorer now gates reverse
  + scores sticky (tier 3); emissions logged.
- `engine/UtteranceState.kt` — implements `ReferenceWatcher.Sticky` (watchBook/Chapter/ExpiresAt).
- `detection/BookResolver.kt` — inflection-tolerant `resolveStem()` (Матфея/Даниила/Римлянам…).
- `detection/ReverseLookup.kt` — candidate dedup by (book,ch,verse) before the ratio gate (Bug #3).
- `bible/BibleIndex.kt` — stopword filtering in `tokenize` (Bug #4).
- `engine/Stabilizer.kt` — time-based dedup via `Config.dedupTtlMs` (Bug #7).
- `engine/EngineServer.kt` — sets `DetectionLogger.path`.
- `bible/SpbLoader.kt` — `loadDefaults()` falls back to `loadAll()` when no allow-list (so reverse
  tests/engine actually have data).
- `Config.kt` — `stickyTtlMs`, `dedupTtlMs`, `reverseMinAgreement`; level chip maps to sticky TTL.

`detection/ExplicitParser.kt` is retained (still covered by `ExplicitParserTest`) but no longer on
the live path — the watcher supersedes it.

---

## 0. Where things live

```
ChurchPresenter-BLE/src/main/kotlin/engine/
  detection/ReferenceWatcher.kt    # stateful explicit/sticky detector (live path)
  detection/NumberWords.kt         # digits / ordinals / number words -> Int
  detection/BookResolver.kt        # alias table + inflection-tolerant resolveStem() -> book id
  detection/ReverseLookup.kt       # BM25 text match (safety net)
  detection/ContinuationEngine.kt  # next-verse heuristic (mostly superseded by sticky watcher)
  detection/ExplicitParser.kt      # legacy single-string parser, off the live path (test-only)
  engine/DetectionEngine.kt        # orchestrates watcher -> reverse -> continuation
  engine/UtteranceState.kt         # per-stream state + sticky context (ReferenceWatcher.Sticky)
  engine/Stabilizer.kt             # time-based dedup/emit gate
  engine/DetectionLogger.kt        # appends emissions + triggering text to detection-log.jsonl
  bible/BibleIndex.kt              # BM25 index + tokenize (stopword-filtered)
socket/SttSocketClient.kt          # feeds transcript + translation into one utterance ("live")
```

Client/UI side: `viewmodel/BibleEngineClient.kt`, `viewmodel/BibleViewModel.kt`.

---

## 2. What the real data proves (from two archived services)

DB schema: `transcriptions(id, timestamp, text, translated_text, translation_language, …)`.
`text` = Russian (primary), `translated_text` = English (translation track). This is the
persisted form of the socket.io `transcription_update` / `translation_update` stream
(live adds incremental `in_progress` partials).

48 reference-bearing lines across both files. Key patterns the current single-string parser
**misses**:

- **Split across rows** — book in one utterance, chapter/verse in the next:
  `послание к римлянам.` → `Десятая глава, девятый-десятой стихи.` = Romans 10:9-10.
- **Verse-by-verse reading (sticky book+chapter, only verse changes):**
  `книге пророка Даниила.` → `всю 6 главу.` → `6 стих.` `8 стих.` `11 стих.`
  `Семнадцатый стих…` `18 стих.` `23 стих.` — all Daniel 6:N, currently zero detections.
  **This is the single biggest recall gap.**
- **`глава` number on either side; varied ranges/lists:**
  `28 глава` · `глава 36-37` · `6 главу` · `с 30 по 31 стих` · `15 по 16 стих` ·
  `3, 14 и 19 стихи`.
- **Forms to tolerate:** STT typos (`Ивангелие` for Евангелие — book token "Матфея" still
  resolves), case endings `стиха/стихе/стихи`, `главу`, digit-ordinals `3-я / 21-й / 1-е /
  19-го`, and word-ordinals (`Десятая`, `Пятый`, `Семнадцатый`).

Number-WORDS only (no digit) were rare (~4 lines): digits dominate, so `NumberWords` is
useful but lower priority than the **structural/stateful** changes.

---

## 3. Proposed architecture

Keep BM25 reverse as the safety net. Replace the explicit stage with three pieces:

### 3a. `BookResolver` — pure book→id (refined)
- Scope active aliases to the **loaded Bibles' languages** (kill cross-language noise).
- Match on stable **roots** with stem/prefix + small edit-distance tolerance, not exact
  strings (robust to endings + 1–2 char STT errors): `матфе`, `коринф`, `далии→Даниил`.

### 3b. `NumberWords` — `String → Int?`
- Accept digits, digit-ordinals (`3-я`→3, `19-го`→19), and ordinal/cardinal **words**
  (cover 1–150 for Psalms). Extend with spelled-out English for the translation track.
- One composable grammar (~30 RU base words) — never enumerate variants in the alias table.

### 3c. `ReferenceWatcher` — stateful, lives in `UtteranceState`
Holds `book`, `chapter`, `deadline`. Driven per update:
- **book** hit → set `book`, expect chapter; reset stale verse.
- **`глав*` + adjacent number (either side)** → set `chapter`, emit `book chapter`.
- **`стих*` + number(s)** → set verse(s), emit full ref; handle ranges (`-`, `по`,
  `с N по M`) and lists (`и`, `потом`).
- **Sticky fallback:** bare `N стих` with no book/chapter → resolve against held
  `book`+`chapter` (catches the Daniel 6 read-through).
- Expire held context on TTL or topic break.

---

## 4. Robustness across speaking & transcription styles

Two sermons can't represent everyone. **Do not fit thresholds to the dataset.** Rely on
style-independent invariants (`глав*`/`стих*` keywords, book roots, number grammar) and let
structure absorb cadence/completeness/STT noise.

### Speaking style: rapid-fire vs expositional → **evidence ranking** (no mode switch)
Every emission carries an evidence tier; higher always overrides lower and resets sticky:
```
Tier 1  explicit full ref   (book + глава N + стих M)   ← always wins, resets sticky
Tier 2  book + chapter only
Tier 3  sticky verse        (bare "N стих" vs held book+chapter)
```
- Rapid-fire readers always name the book → stay at Tier 1/2 → stickiness never fires for
  them (degrades gracefully).
- Expositional readers drop bare verses → Tier 3 carries them.

### Content-agreement makes sticky safe (wire in `AgreementScorer` + BM25)
When the watcher *predicts* a ref structurally, confirm against what was actually spoken:
- Reader reads the verse aloud → spoken words BM25-match the predicted verse → corroborated
  → high confidence. Stale context → no match → suppress.
- Citer doesn't read it → no verse text, but book was explicit (Tier 1) → no confirmation
  needed.
The two detectors cover disjoint failures: explicit = "announced but not read",
reverse+agreement = "read but not announced" and **validates risky sticky inferences**.
This is speaker-independent — it depends on content match, not a tuned threshold.

### Confidence-tiered emission (don't force decisions)
Emit `(source, confidence)` (UI already shows chips + gates auto-follow):
- **Auto-follow** only on Tier-1 or content-corroborated detections.
- Sticky/uncorroborated guesses → dim, tappable chips; never wrong auto-navigation.
- Wrong-for-rapid-fire degrades to "an extra dim chip," not "jumped to Galatians mid-Romans."

### Transcription style → normalize + fuzzy, never enumerate
- Book names: stem + prefix/substring with edit-distance, anchored on distinctive root.
- Numbers: one `NumberWords` grammar (digits / ordinals / words / spelled-out EN).
- Keywords: `глав*`/`стих*` prefixes, number on either side.

### Cadence-adaptive TTL (optional, auto)
Track recent rate of *distinct book changes*: many books/min → shrink sticky TTL (trust only
explicit); long dwell → extend. Map to the existing OFF/CONSERVATIVE/BALANCED/AGGRESSIVE
chip; optionally auto-estimate.

---

## 5. Validation strategy (small dataset → still generalizes)

1. **`.db` files = regression fixtures only** (lock known-good asserts; do NOT set thresholds
   from them): Matthew 28:18, 1 Peter 3:21, Romans 10:9-10, Proverbs 30:5, Ephesians 4:5,
   Mark 16:15-16, the **Daniel 6 verse-by-verse chain**, etc. Feed each row in order, assert
   emitted refs.
2. **Synthetic stress corpus** for styles the files lack — assert **precision stays high**
   (precision is what must generalize): rapid-fire book→book→bare-verse chains;
   single-passage sermons; split-across-rows with filler; STT-corrupted book names (inject
   edit-distance noise, drop keywords); mixed digit/word numbers; out-of-order `глава`/number.
3. **Grow the real corpus for free** — services already archive to `.db`. Add a column
   logging what the engine detected per row; every service becomes labeled-ish regression
   data. Periodically review misses. (More backups incoming — fold them in here.)

### Test harness notes
- Read `.db` via SQLite; order by `id`; replay `text` (and `translated_text`) as the stream.
- Mirror existing tests `src/test/kotlin/engine/{ExplicitParserTest,ReverseLookupTest}.kt`.
- Windows console can't print Cyrillic (cp1252) — write expected/actual to UTF-8 files or
  assert programmatically; set `PYTHONUTF8=1` for any Python tooling.

---

## 6. Remaining work

Mostly data-dependent (needs the incoming `.db` backups) plus a few engine cleanups.

1. **`.db` replay harness.** Current regression uses hardcoded sequences (no SQLite dep in the
   module). When more backups arrive, either add a dev-only script (Python, like the analysis here)
   or a test-scoped sqlite-jdbc dependency to replay rows in `id` order and assert. Don't ship the
   `.db` files in the repo.
2. **Synthetic stress corpus generator** — programmatic rapid-fire chains, STT-corrupted book names
   (edit-distance noise), dropped keywords. Precision negatives exist; the generator does not yet.
3. **Cadence-adaptive sticky TTL** — auto-shrink on many distinct book changes/min. Mechanism only;
   needs real multi-speaker data to set the rate thresholds.
4. **Fuzzy book matching** — `resolveStem` is prefix-only (edit distance 0). Raising tolerance for
   STT typos (e.g. `Ивангелие`) needs more typo examples before it's safe.
5. **Per-language alias scoping** — still the full merged table; short-alias false positives are
   currently mitigated structurally (watcher requires глава/стих or a number). Full per-language
   tagging deferred.
6. **Confidence-tiered auto-follow gating on the client** — engine already emits per-tier confidence
   + source; `BibleViewModel`/`autoFollow` could restrict auto-navigation to tier-1/corroborated.
7. **Continuation engine windowing** — `ContinuationEngine` still scores against the whole growing
   transcript (overlap shrinks on long utterances). Window it, or retire it now that the sticky
   watcher covers most continuation cases.
8. **Reverse all-terms path** — `searchAllTerms` requires every window token in one verse, so it
   rarely fires; consider relevance-windowing the query.
9. **`pickTranslation` hardcodes `RUS_RST`/`ENG_KJV`** ids before the language-match fallback —
   fragile if the loaded SPB ids/codes differ.
10. **Known limitations** to revisit with data: ambiguous numbered books with no number spoken
    (bare "Коринфянам"/"Петра"); short genitive stems excluded by the min-4 rule (e.g. "Луки").

### Known-miss examples (kept as future fixtures)
`1-е послание Петра …` (book word between "1" and "Петра"); bare `Коринфянам 12` (1 vs 2 Cor);
`Луки 22` (stem "лук" < min length). All low-frequency; revisit when backups quantify them.

---

## 7. Sample data location (local, not committed)

Archived services used for this analysis (Downloads, not in repo):
`2026-06-03__18-22-50_Church.db`, `2026-06-07__08-10-35_Church.db`.
Future backups: drop here / note paths so the regression set keeps growing.
