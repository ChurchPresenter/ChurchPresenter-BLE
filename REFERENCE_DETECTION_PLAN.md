# Bible Reference Detection — Improvement Plan

Reference doc for the reworked explicit-reference stage of the BLE engine. Captures the
stateful detector design, how it stays robust across speaking/transcription styles, the
validation strategy, and the work still outstanding. Driven by real STT data (archived service
`.db` files). **Primary language is Russian** (see project memory).

Status: **CORE IMPLEMENTED** — unit tests green + a skip-when-absent `.db` replay; two archived
services folded in as regression fixtures (§5/§7). The stateful `ReferenceWatcher` (not the legacy
`ExplicitParser`, retained test-only) is the live explicit stage. Remaining work — mostly
data-dependent tuning — is in §6.

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
   - **Folded in (three archived services).** Three backups added as permanent coverage —
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
     (`14 стих 3 главы`), `Четвертая глава` → `С N стиха` sticky. service3 (new STT schema) adds
     real-data **epistle word-ordinal + marker** (`первое послание Иоанна 4:3` → 1 John, not John),
     word-ordinal chapter + range together (`послание Иакова, вторая глава, с 19 по 22`), and the
     `Матфея 10, глава N стих` tail order.
     New precision traps now guarded: `стихотворение`/`стихотворения` (poem),
     `главное`/`главный`, `глава семьи` (`семьи` collided with стем for 7), bare `стихи`,
     bare `один стих`/`этот стих`, and counting-ordinal-next-to-a-book prose
     (`первое условие … Иоанн` must not fabricate 1 John).
2. **Synthetic stress corpus** for styles the files lack — assert **precision stays high**
   (precision is what must generalize): rapid-fire book→book→bare-verse chains;
   single-passage sermons; split-across-rows with filler; STT-corrupted book names (inject
   edit-distance noise, drop keywords); mixed digit/word numbers; out-of-order `глава`/number.
3. **Grow the real corpus for free** — services already archive to `.db`. Add a column
   logging what the engine detected per row; every service becomes labeled-ish regression
   data. Periodically review misses and fold new backups in here (recipe below).
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

Mostly data-dependent (needs more `.db` backups) plus a few engine cleanups.

1. **Synthetic stress corpus generator** — programmatic rapid-fire chains, STT-corrupted book names
   (edit-distance noise), dropped keywords. Precision negatives exist; the generator does not yet.
2. **Cadence-adaptive sticky TTL** — auto-shrink on many distinct book changes/min. Mechanism only;
   needs real multi-speaker data to set the rate thresholds.
3. **Fuzzy book matching** — `resolveStem` is prefix-only (edit distance 0). Raising tolerance for
   STT typos (e.g. `Ивангелие`) needs more typo examples before it's safe.
4. **Per-language alias scoping** — still the full merged table. Short-alias false positives are now
   mitigated two ways: structurally (a bare book only sets sticky; emission still needs глава/стих or
   a number) **and** by dropping ≤2-char single-token aliases in the watcher (kills `so`/`re`/`ap`
   drift on translated prose). Still open: **3-char aliases that are real words** (`job`→Job, `am`,
   `ru`) can fire on the EN track; quantify with backups before extending the block or doing full
   per-language tagging.
5. **Confidence-tiered auto-follow gating on the client** — engine already emits per-tier confidence
   + source; `BibleViewModel`/`autoFollow` could restrict auto-navigation to tier-1/corroborated.
6. **Continuation engine windowing** — `ContinuationEngine` still scores against the whole growing
   transcript (overlap shrinks on long utterances). Window it, or retire it now that the sticky
   watcher covers most continuation cases.
7. **Reverse all-terms path** — `searchAllTerms` requires every window token in one verse, so it
   rarely fires; consider relevance-windowing the query.
8. **`pickTranslation` hardcodes `RUS_RST`/`ENG_KJV`** ids before the language-match fallback —
   fragile if the loaded SPB ids/codes differ.
9. **Known limitations** to revisit with data: ambiguous numbered books with no number spoken
   (bare "Коринфянам"/"Петра"); short genitive stems excluded by the min-4 rule (e.g. "Луки").

### Known-miss examples (kept as future fixtures)
Open: bare `Коринфянам 12` (1 vs 2 Cor, no number spoken); `Луки 22` (stem "лук" < min length). All
low-frequency; revisit when backups quantify them. (The *marked* epistle case `1-е послание Петра …`
is handled — `[1-е/2-е/Первое] Послание Иоанна|Петра` resolves to the epistle via the ordinal +
«послание»/«письмо» marker look-back in `ReferenceWatcher`; covered by `ReferenceWatcherTest`.)

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
transcripts). Three backups are folded into the regression set, referenced by neutral fixture ids:

- **service1** — ~751 rows.
- **service2** — ~802 rows.
- **service3** — ~147 rows (new STT schema: `session_id`, `segment_id`, `ts_ms`, `words_json`).

Curated expected table (fixture, row id → expected ref), replayed by `DbReplayTest`
(`-Dreplay.db=<path> -Dreplay.fixture=service1|service2|service3`) and locked in
`ReferenceWatcherTest`. The pattern column describes the case; the actual transcript text is not
reproduced here.

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
| service3 | 3 | epistle word-ordinal + «послание» marker | 1 John 4:3 | 1 |
| service3 | 11 | word-ordinal chapter «вторая» + «с N по M» range | James 2:19-22 | 1 |
| service3 | 36 | book + chapter num, then «глава N стих» tail | Matt 10:32 | 1 |

Precision negatives (must emit nothing): service1 #332/#356/#401/#662/#665/#701,
service2 #12/#623/#624/#712, service3 #52/#56/#68/#72 (bare «Иоанн» / "первое условие … Иоанн").

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
- `pickTranslation` hardcodes `RUS_RST`/`ENG_KJV` ids before the language fallback (§6 #8) — fragile if
  the loaded SPB ids differ. A wrong translation pick can yield a verse in the wrong numbering.
- Cross-language alias noise: short single-token aliases (`so`, `re`, `ap`, and 3-char real words like
  `job`/`am`/`ru`) can fire on the English translation track and hijack the sticky book (§6 #4).
- Bare ambiguous numbered books with no number spoken (`Коринфянам`, `Петра`) still can't pick 1 vs 2
  (§6 #9) — distinct from the now-fixed *marked* epistle case (`Послание … Петра`).

---

## 9. Matching training artifacts (db ↔ jsonl) — make analysis rock solid

One service produces three artifacts written by three components at three different moments, and in
the field **STT and ChurchPresenter can start 30+ minutes apart** (possibly on different machines):

| Artifact | Written by | Filename key (current) |
|---|---|---|
| `<sessionId>.db` | STT app | STT session id |
| `detection-log-<sessionId>.jsonl` | engine `DetectionLogger` | STT session id (lazy; fallback to engine-start stamp) |
| `live-references-<sessionId>.jsonl` | CP `TrainingDataLogger` | STT session id (lazy; fallback to CP-start stamp) |

**`session_id` is now the primary key (implemented).** STT emits a stable `session_id` in every
socket payload; the engine and CP both **key their log filenames by it** and **stamp it into the
session header and every row**. Binding is **lazy — at first write** — so the start order doesn't
matter: the engine only starts when STT connects and neither logger writes until the first
detection / go-live, by which time `session_id` is always known. If the field is absent (STT hasn't
shipped it yet), both loggers **fall back to their process-start timestamp** — identical to the old
behaviour, so analysis still works on pre-`session_id` data via the content/epoch heuristics below.

So **do NOT match on filename or wall-clock** for legacy data, and **`segment_id` can't identify a
run** (it restarts at ~1 every session). `candidate-log`/`suggestion-outcomes` are written **on
demand** — their absence for a run is normal, not missing data.

### What to match on (invariant + session-unique)
1. **db ↔ detection-log — transcript content (strong key).** Every logged detection was triggered by a
   db row, so for the true db nearly all detection `transcript`s contain one of its row `text`s. Clock-
   and machine-independent. (The reverse — db rows inside detections — is naturally low since most db
   rows never fire; score the **detection→db** direction.)
2. **detection-log ↔ live-references — same process, same clock.** CP hosts the in-process engine, so
   these share the **same wall clock** and the **same `segment_id` space** → match by time-window
   overlap + segment-id overlap.
3. **Cross-check: STT session epoch** = `wall_clock − session_relative_time`, constant across a
   session: db `ts_ms − start_time*1000` vs detection-log `ts − sttStartTime*1000`. Agreement
   corroborates; a content match with a large epoch gap flags **inter-machine clock skew** (matching
   still valid — just don't trust raw timestamps for that run).

### Tool
`tools/match_training_data.py <dir>` implements the above and prints the matched groups with a
confidence (HIGH ≥70% of detections grounded in the db). Verified on real data: it pairs
`…_120605.db` ↔ `detection-log-…_12-04-48` ↔ `live-references-…_12-05-24` at HIGH, ignores the
unrelated `11-18` set, and flags a detection-log whose db isn't present.

### ChurchPresenter restart mid-session (no fragmentation with `session_id`)
If ChurchPresenter restarts while the STT server keeps the **same** session:
- **db** — continuous (STT is separate); `segment_id` keeps incrementing. Nothing lost.
- **detection-log / live-references** — because both files are now **keyed by the STT `session_id`**
  and bound **lazily at first write**, the restarted engine + CP **re-open the SAME files and append**.
  The session header is written **once per file** (a restart that re-attaches to an existing file skips
  the header and just continues it), so **one STT session = one detection-log + one live-references**.
  No more fragmentation.
- **Legacy / no-`session_id` fallback:** when the field is absent the old `runStamp`-named behaviour
  applies, so a restart opens **new** timestamped files — **one STT session spans 2+ fragments**.
  Pre-restart files are already flushed line-by-line → not lost. The matcher still groups these and
  prints `‼ ChurchPresenter RESTART detected — this STT session spans N CP runs`, discriminating real
  restarts from repeated-sermon look-alikes via the **STT session epoch** (`wall − session_relative_time`,
  identical across a true restart, within `EPOCH_TOL_S`).

### STT restart mid-CP (engine rolls to a new file)
If the **STT** server restarts while ChurchPresenter keeps running, STT issues a **new** `session_id`.
The engine's `DetectionLogger` (filename chosen per write) **rolls subsequent writes to the new
`detection-log-<newId>.jsonl`**, and CP's `live-references` follows on the next detection-driven
go-live — correctly splitting the two STT sessions into separate, individually-joinable file sets.

### Matching is now an exact key join (implemented + verified on real data)
With `session_id` keying filenames + headers + rows, matching is a trivial 1:1 join.
`match_training_data.py` **already prefers an explicit `session_id`** when both sides carry one (db
`session_id` column + log header/row `sessionId`), so it needs **no code change** — it picks up the
key automatically. The content/epoch heuristics above remain as the **fallback path for pre-
`session_id` data**. **Verified on a real session_id-enabled service** (the service3 backup): the
matcher reports the db ↔ detection-log ↔ live-references group as `EXPLICIT session_id` (100%
grounded), confirming the end-to-end join.

> **STT side (handoff — separate dev):** STT must (a) emit `session_id` in every socket payload (db
> base name like `2026-06-25_120605`, or a UUID) and (b) **add a `session_id` column to the db** storing
> the same value. The matcher already reads that column when present. The engine/CP sides are landed and
> are regression-safe to ship before STT (they fall back to timestamp names until the field arrives).
