# Bible Reference Detection — Improvement Plan

Reference doc for the reworked explicit-reference stage of the BLE engine. Captures the
stateful detector design, how it stays robust across speaking/transcription styles, the
validation strategy, and the work still outstanding. Driven by real STT data (archived service
`.db` files). **Primary language is Russian** (see project memory).

Status: **CORE IMPLEMENTED** (unit tests green + a skip-when-absent `.db` replay). Data-dependent
tuning + a few items remain — see §6. Two archived backups folded in (§5/§7).

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

### 2026-06-25 service round (post-deployment fixes)
Driven by a live **auto-follow** service (15 go-lives, all `source:"auto"`, so `live-references`
reflects engine output and the `.db` transcript is ground truth):
- **Epistle ordinal resolution** (`ReferenceWatcher`): `[1-е/2-е/Первое] Послание Иоанна|Петра` now
  resolves to the **epistle** (1/2/3 John = 62–64, 1/2 Peter = 60–61), not the Gospel/bare name. Closes
  the long-standing known-miss "book word between `1` and `Петра`/`Иоанна`". A look-back recognises an
  «послание»/«письмо» marker (+ optional «к» connector + ordinal digit/word); a bare `Иоанна`/`Петра`
  with no marker stays the gospel/alias reading (precision preserved).
- **Re-emission churn control** (`Stabilizer`/`Config`): a held passage re-emits only on a
  ≥`reEmitMinDelta` (0.15) confidence move **and** after `reEmitCooldownMs` (10 s). Stops the 11×
  re-present of a held verse as reverse confidence oscillates while the window slides.
- **Candidate-log cleanup** (`DetectionEngine`): stop logging `deduped` rows (a held passage repeating —
  no tuning signal, they swamped the log); keep only genuine `below-confidence`/`low-agreement` misses.
- **CP-side numbering/order fixes** (ChurchPresenter `BibleViewModel`/`BibleEngineClient`): the engine
  was already correct — the *display* mapping was wrong. See **§8 (gotchas)**. The engine now forwards
  `canonicalCodeStart`/`canonicalCodeEnd` so the app can land the reference in the primary Bible's own
  numbering.

Tests: `ReferenceWatcherTest` (epistle + canonical-name ground truth), new `StabilizerTest` (churn
bounds). Committed on the engine submodule as `f090b0a`.

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
   - **Folded in (two archived services).** Two more backups added as permanent coverage —
     replayed by `DbReplayTest` (sqlite-jdbc, skips when `-Dreplay.db` unset) and locked as
     hardcoded sequences in `ReferenceWatcherTest`. Curated expected table in §7. The replay feeds
     the **combined transcript + translation** per row (mirroring `DetectionEngine.runDetection`),
     so the translation track corroborates/rescues a book whose source was STT-garbled (the Joshua
     read: the source-language book token never matches, the translation's "…Joshua…" does). It uses
     each row's **real `timestamp`** so sticky TTL expires/holds books as it does live. With those
     plus the short-alias guard (below) every curated row resolves **exactly**, including the Joshua
     read (#633/#661) and the split Matthew 7:21 (#410). The lone drop is #660 (chapter-only read
     whose translation appends a spurious verse) — a genuine translation garble, covered cleanly on
     the source-language path in the unit tests.
     New true-positive patterns: word-ordinal chapter (`шестая глава`), `с N по M` verse range,
     split book→chapter→verse with instrumental `стихом`, verse-before-chapter order
     (`14 стих 3 главы`), `Четвертая глава` → `С N стиха` sticky.
     New precision traps now guarded: `стихотворение`/`стихотворения` (poem),
     `главное`/`главный`, `глава семьи` (`семьи` collided with стем for 7), bare `стихи`,
     bare `один стих`/`этот стих`.
2. **Synthetic stress corpus** for styles the files lack — assert **precision stays high**
   (precision is what must generalize): rapid-fire book→book→bare-verse chains;
   single-passage sermons; split-across-rows with filler; STT-corrupted book names (inject
   edit-distance noise, drop keywords); mixed digit/word numbers; out-of-order `глава`/number.
3. **Grow the real corpus for free** — services already archive to `.db`. Add a column
   logging what the engine detected per row; every service becomes labeled-ish regression
   data. Periodically review misses. (More backups incoming — fold them in here.)
   → Two data improvements are tracked as separate handoff specs (kept out of the repo, passed to the
   respective developers): (a) **transcription-side** signals — reliable `speech_type`, millisecond
   timestamps, per-word confidence, partials, language tags; (b) **ChurchPresenter-side** labels —
   what went on screen vs. what the detector emitted, plus opt-in log collection. Note:
   `engine/DetectionLogger.kt` already logs the detector half to `detection-log.jsonl`; the missing
   piece is the go-live ground truth + a collection path.

### Test harness notes
- `DbReplayTest` (built) reads `.db` via sqlite-jdbc, orders by `id`, feeds **`text + " " +
  translated_text`** per row (mirrors `DetectionEngine.runDetection`) through one sticky context,
  using each row's real **`timestamp`** as `now` so sticky TTL behaves as live.
- Windows console can't print Cyrillic (cp1252) — write expected/actual to UTF-8 files or
  assert programmatically; set `PYTHONUTF8=1` for any Python tooling. (Use Python `sqlite3` to dump
  rows to a UTF-8 JSON file, then Read that — `sqlite3` CLI is not installed.)

### How to fold in the next backup (recipe)
1. Keep the `.db` local only (never commit it); assign it a neutral fixture id (`service3`, …) — do
   not record its filename/path in the repo.
2. Dump candidate rows to UTF-8 (`PYTHONUTF8=1 python … json.dump(…, ensure_ascii=False)`), Read the
   JSON, and pick the reference-bearing rows + new false-positive traps. For sticky/translation-rescue
   rows, also pull the `translated_text` and the neighbouring rows (sticky book comes from context).
3. Add **`exactByFixture[id]`** entries (book+ch+verse[+range]) and **`negativeByFixture[id]`** ids to
   `DbReplayTest`. Leave a row out only when the translation genuinely garbles it (document why).
4. Add the same cases as **hardcoded sequences** in `ReferenceWatcherTest` (so coverage holds without
   the `.db`): positives with `assertEquals`/`any`, negatives with `assertNoEmit` (standalone + live
   sticky). Gated-recall behaviour → toggle `Config.applyLevel(...)` in a focused test, restore to
   `balanced` in `finally`.
5. Run: `./gradlew test` then `./gradlew test -Dreplay.db="<local path>" -Dreplay.fixture=<id>`.
6. New always-on guard? Keep it style-independent (ending whitelists / look-alike sets / alias-length
   like the existing ones). New risky recall? Gate it behind a `Config` flag set in `applyLevel`.

---

## 6. Remaining work

Mostly data-dependent (needs the incoming `.db` backups) plus a few engine cleanups.

1. ~~**`.db` replay harness.**~~ **DONE.** `src/test/kotlin/engine/DbReplayTest.kt` replays a
   `.db` (path via `-Dreplay.db=…`) in `id` order through one sticky context and asserts the
   curated table; skips via JUnit `Assumptions.assumeTrue` when the prop/file is absent so CI
   without the local files stays green. Test-scoped `org.xerial:sqlite-jdbc`. `.db` files are
   never committed (`.gitignore` + §7). Hardcoded sequences in `ReferenceWatcherTest` give the
   same coverage without the files.

   **Precision guards added (always-on, style-independent):** the Russian `глав`/`стих` keyword
   detectors now require a whole grammatical ending (not any prefix), so `стихотворение`/`главное`
   no longer fire; `семья`-forms are rejected as numerals (collided with `семь`=7); bare cardinal
   `один`/`one` ("one verse") is treated as filler so it can't bind to sticky — the EN form matters
   because the translation track is fed through the same watcher. Finally, **ultra-short single-token
   aliases (≤2 chars: `so`→Zeph, `re`/`ap`→Rev, `ge`, `ex`…) no longer match in the watcher** — they
   are typed-input abbreviations that fired constantly on translated prose and hijacked the sticky
   book; multi-word aliases (always ≥3 chars with a space) are kept.

   **Music precision gate:** `ReferenceWatcher.process(..., isMusic)` skips detection (and leaves
   sticky untouched) when the STT `speech_type` is `Music` — sung lyrics quote scripture as lyrics,
   not as references. Wired through `DetectionEngine` (`UtteranceState.speechType`) and
   `SttSocketClient` (`speech_type` from the live payload); replayed from the `speech_type` column in
   `DbReplayTest`. Toggle `Config.suppressDuringMusic` (default on; independent of the level chip).
   No-op on the two folded-in services (no sung-reference rows) — forward-looking guard.

   **Aggressiveness-gated recall (rides the OFF/CONSERVATIVE/BALANCED/AGGRESSIVE chip):**
   `Config.normalizeStt` (Cyrillic `э→е` before book resolution; BALANCED+) and
   `Config.inferBookAtEnd` (book named *after* its numbers attaches to them; AGGRESSIVE only).
2. **Synthetic stress corpus generator** — programmatic rapid-fire chains, STT-corrupted book names
   (edit-distance noise), dropped keywords. Precision negatives exist; the generator does not yet.
3. **Cadence-adaptive sticky TTL** — auto-shrink on many distinct book changes/min. Mechanism only;
   needs real multi-speaker data to set the rate thresholds.
4. **Fuzzy book matching** — `resolveStem` is prefix-only (edit distance 0). Raising tolerance for
   STT typos (e.g. `Ивангелие`) needs more typo examples before it's safe.
5. **Per-language alias scoping** — still the full merged table. Short-alias false positives are now
   mitigated two ways: structurally (a bare book only sets sticky; emission still needs глава/стих or
   a number) **and** by dropping ≤2-char single-token aliases in the watcher (kills `so`/`re`/`ap`
   drift on translated prose). Still open: **3-char aliases that are real words** (`job`→Job, `am`,
   `ru`) can fire on the EN track; quantify with backups before extending the block or doing full
   per-language tagging.
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
`1-е послание Петра …` (book word between "1" and "Петра") — **FIXED** in the 2026-06-25 round
(epistle ordinal resolution; see top section + `ReferenceWatcherTest`). Still open: bare `Коринфянам 12`
(1 vs 2 Cor, no number spoken); `Луки 22` (stem "лук" < min length). All low-frequency; revisit when
backups quantify them.

From the two folded-in services (locked as fixtures; fix gated/deferred):
- **`эфесянам`** (STT writes `э`, alias is `ефесянам`) — handled by `Config.normalizeStt` (BALANCED+);
  off at CONSERVATIVE by design.
- **`книга Иисуса на Винах`** = Иисуса Навина (Joshua), book named at END after the numbers — the
  attach-after-numbers path is gated by `Config.inferBookAtEnd` (AGGRESSIVE only). The Russian book
  is STT-garbled (`Новина`/`на Винах`) and never resolves on its own, but the **English translation
  track rescues it**: row #631 EN says "…Joshua…" → resolves via the alias table and seeds the
  sticky book, so `14 стих 3 главы` (#633) and `С 5 стиха` (#661) bind to Joshua. With the real-clock
  replay (TTL keeps Joshua across the 149 s read) and the short-alias guard (kills the `so`/`re`/`ap`
  drift), the replay now asserts these **exactly**. Proven in isolation by `ReferenceWatcherTest`
  (`garbled Russian book rescued by English translation track`).
- **`Первая книга царств`** (word-ordinal numbered book, no digit) — needs numbered-book grammar in
  `BookResolver`; low frequency, deferred this pass.

---

## 7. Sample data (local only — never committed)

Service `.db` backups used for analysis live on the maintainer's machine only; they are **not in the
repo** and their filenames/paths are deliberately not recorded here (they contain full congregation
transcripts). Two backups are folded into the regression set, referenced by neutral fixture ids:

- **service1** — ~751 rows.
- **service2** — ~802 rows.

Curated expected table (fixture, row id → expected ref), replayed by `DbReplayTest`
(`-Dreplay.db=<path> -Dreplay.fixture=service1|service2`) and locked in `ReferenceWatcherTest`. The
pattern column describes the case; the actual transcript text is not reproduced here.

| Fixture | Row | Pattern | Expected | Tier |
|---------|-----|---------|----------|------|
| service1 | 27 | explicit book + chapter + verse | Eph 4:6 | 1 |
| service1 | 28 | sticky verse vs previous row | Eph 4:6 | 3 (sticky) |
| service1 | 377 | word-ordinal chapter + "from N to M" range | Deut 6:4-9 | 1 |
| service1 | 378 | explicit book + chapter + verse | Deut 6:4 | 1 |
| service1 | 409→410 | split: book+chapter, then verse | Matt 7:21 | 3 (sticky) |
| service2 | 3 | explicit book + chapter + verse | Col 3:21 | 1 |
| service2 | 631→633 | book via translation track + verse-before-chapter | Joshua 3:14 | 3 |
| service2 | 660→661 | sticky book+chapter, then "from verse N" | Joshua 4:5 | 3 |

Precision negatives (must emit nothing): service1 #332/#356/#401/#662/#665/#701,
service2 #12/#623/#624/#712.

`DbReplayTest` asserts every curated row **exactly** (book + chapter + verse[+range]). This holds
because the replay feeds the combined transcript + translation with each row's real `timestamp`
(sticky TTL behaves as live) and the short-alias guard stops short cross-language abbreviations from
hijacking the sticky book. One service2 row is intentionally not asserted (a chapter-only reference
whose translation appends a spurious verse) — covered on the clean source-language path in
`ReferenceWatcherTest`.

Future backups: add them locally and extend the fixtures (see §5 "How to fold in the next backup").

---

## 8. Gotchas — what to watch out for (book / reference mismatches)

The engine and the ChurchPresenter UI use **different book/chapter numbering anchors**. Most "the
engine detected the right verse but the wrong thing showed on screen" bugs come from mixing them up.
The engine reasons in **canonical (Protestant) numbering**; a loaded Bible may store books in a
different **order** and chapters/verses in a different **numbering**. Keep these straight:

### 8a. Book id ≠ book position (NEVER map a canonical id positionally)
The engine emits a **canonical book id** (`1=Genesis … 62=1 John … 66=Revelation`, from
`BookResolver`). A Bible's display list (`Bible.getBooks()`) is in the **file's order**, which is NOT
always canonical:
- The **Russian Synodal** Bible places the General Epistles (James, 1–2 Peter, 1–3 John, Jude)
  **right after Acts**, before Paul's letters. So 1 John sits at display **index 47**, and display
  index **61 is 2 Timothy**.
- ⚠️ Mapping `index = canonicalId − 1` then sends **1 John (62) → index 61 → 2 Timothy** — every NT
  epistle shifts. (This was a real regression.)
- ✅ Map by the book's canonical **id field**, not position: `Bible.getDisplayIndexForBookId(id)`
  (it does `books.indexOfFirst { it.bookId == id }`). Works for any Bible / any order, including a
  Protestant-ordered one where it coincidentally equals `id − 1`.

### 8b. Chapter/verse numbering divergence — Psalms (LXX/Synodal vs Hebrew)
Same psalm, different number depending on the tradition: **Hebrew/English Ps 23 = Synodal/LXX Ps 22**
(LXX merges Ps 9+10, so most of the Psalter is shifted by one). Joel, Malachi, 3 John, and a few
others also differ. The `.spb` carries **two** numbers per verse: the internal **code**
(`BXXXCXXXVXXX`, Hebrew) and the **native/display** number (e.g. Synodal for RST). For RST:
`B019C023…` (code 23) is displayed as **Псалом 22**.
- ✅ The engine forwards `canonicalCodeStart`/`canonicalCodeEnd` (the **code**, numbering-independent).
  The app lands it in the **primary** Bible's own numbering with
  `Bible.getVerseDetailsByCode(codeBook, codeChapter, codeVerse)` (uses the per-Bible
  `codeToDisplayMap`) — the same bridge that aligns the secondary Bible.
- The displayed number therefore depends on **which Bible is primary**; the *interpretation* of the
  spoken number depends on **the spoken language** (the engine's `pickTranslation` resolves a Russian
  "Псалом 22" against RST and an English "Psalm 23" against KJV — both produce the same code).
- ⚠️ Do NOT pass the engine's raw `chapter` straight to display when primary ≠ the engine's matched
  translation — that shows the wrong Psalm number. Always go through the code.
- Known limitation: `getVerseDetailsByCode` translates the **chapter** but echoes the **verse**, so a
  titled Psalm (Hebrew superscription counted as verse 1 in Synodal) can be ±1 on the verse. Matches
  the existing secondary-Bible behaviour; revisit if it bites.

### 8c. The ground-truth log must be in canonical numbering
`live-references` (TrainingDataLogger) is the *ground truth* a `.db` analysis diffs against the
engine's `detection-log`. They must use the **same** numbering or correct detections look wrong:
- ⚠️ Logging the raw **display position + 1** caused the original misdiagnosis — 1 John's Synodal index
  47 → `+1` = `48`, which *looks like* Galatians' canonical id, and James/2 Cor/Gal landed on
  Romans/1 Tim/2 Tim the same way. The display was correct; the **log** was in the wrong numbering.
- ✅ Log the **canonical book id** (`getBookId(displayIndex)`), so `live-references.book` ==
  `detection-log.book`. (Chapter is still display-numbered — when comparing Psalm chapters across the
  two logs, remember they have different anchors.)

### 8d. Verification checklist before trusting a run
- Decode `live-references.book` and `detection-log.book` as **canonical** ids (post-fix) and confirm
  they match — but also **watch the actual screen**: the on-screen book name is the only real proof
  (a number in a log can coincide with a different book's id, see 8c).
- Test with **each** Bible set as primary (Synodal-ordered and Protestant-ordered), and exercise a
  **Psalm** in both spoken languages. A change that "works" with RST-primary can still be wrong with an
  English primary, and vice-versa.
- Books most likely to expose order bugs: the **NT General Epistles + Pastorals** (James↔Romans,
  1 John↔2 Timothy, 2 Cor↔1 Tim, Galatians↔2 Tim under the Synodal shift). Chapters most likely to
  expose numbering bugs: **Psalms** (and check Joel/Malachi/3 John).

### 8e. Other matching foot-guns (engine side)
- `pickTranslation` hardcodes `RUS_RST`/`ENG_KJV` ids before the language fallback (§6 #9) — fragile if
  the loaded SPB ids differ. A wrong translation pick can yield a verse in the wrong numbering.
- Cross-language alias noise: short single-token aliases (`so`, `re`, `ap`, and 3-char real words like
  `job`/`am`/`ru`) can fire on the English translation track and hijack the sticky book (§6 #5).
- Bare ambiguous numbered books with no number spoken (`Коринфянам`, `Петра`) still can't pick 1 vs 2
  (§6 #10) — distinct from the now-fixed *marked* epistle case (`Послание … Петра`).
