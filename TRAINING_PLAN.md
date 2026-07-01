# BLE Reference Detection — Training Plan

## What this is for

The BLE engine detects Bible references from speech-to-text transcripts. Training data drives
improvements in four areas:

| Goal | Measure | Target |
|---|---|---|
| **Accuracy** | recall = TP / (TP + FN) | Detect more real references |
| **Precision** | precision = TP / (TP + FP) | Fewer wrong suggestions |
| **Speed** | median latency = (live_ts − detection_ts) ms | Engine beats the operator |
| **Continuation** | chained_correct / chained_total | Verse-by-verse reading without repeating chapter |

Ground truth is the **live-references log** — what a human operator actually put on screen.
The **detection-log** is what the engine suggested. The gap between them is what we train.

---

## Artifact Types

| File | What it contains | What it tells us |
|---|---|---|
| `*.db` | Full service transcript (songs + sermons + announcements) | Row text, timestamp, speech_type, translated_text |
| `detection-log-*.jsonl` | Every reference the engine suggested | book, chapter, verse, tier, source, ts, transcript |
| `live-references-*.jsonl` | Every reference the operator put on screen | book, chapter, verse, ts_ms, autoFollow |
| `candidate-log-*.jsonl` | Near-misses the engine considered but didn't fire | Threshold tuning signal |

**Important**: DB files are whole-service transcriptions. ~60–70 % of rows are music
(`speech_type = Music`) or worship. Only sermon rows are relevant. Always filter first.

---

## The Four Training Signals

Cross-referencing detection-log and live-references (within a ±90 s window):

```
TP  — engine detected AND operator confirmed     → preserve; regression-test these
FP  — engine detected, operator NEVER confirmed  → suppress; add as precision-negative fixtures
FN  — operator confirmed, engine NEVER detected  → fix; identify the missing pattern
CC  — operator confirmed N consecutive verses    → continuation chain; test sticky propagation
```

These four signals drive every engine change. Before changing anything, compute all four
for the affected sessions to establish a baseline.

---

## Compact Training Samples (not full DB replay)

Full DB replay (750–820 rows) is expensive and noisy. Instead, extract **15-row windows**
around each reference event:

```
tools/extract_training_samples.py   ← to build when next logs arrive
  Input:  DB path, detection-log path, live-references path
  Output: training-samples-<session>.jsonl

  Per event:
    session_id       string
    event_type       "tp" | "fp" | "fn" | "continuation"
    rows             list[15]  — rows[−12..+2] around the event, Music filtered out
    expected_ref     {book, ch, v, vEnd}  or null
    detected_ref     {book, ch, v, tier, src, latency_ms}  or null
    latency_rows     int  — rows from first engine detection to operator confirmation
                            negative = engine was early (good)
                            positive = engine was late or missed
```

The DB is only read once per session to build this file. All downstream analysis uses
the compact JSONL. Target size: ~50–200 events per session vs 750–820 rows.

---

## Workflow: New Logs Arrive

```
1.  Drop DB + JSONL files into Downloads/bible-stt-logs-mac-arm/
2.  Run: python tools/match_training_data.py
        — confirms artifact pairing, epoch skew, session IDs
3.  Run: python tools/extract_training_samples.py  (build on first use)
        — produces training-samples-<session>.jsonl
4.  Run: python tools/eval_metrics.py             (build on first use)
        — prints precision / recall / median-latency / continuation-rate
5.  Review FN list → identify the missing engine pattern
6.  Review FP list → identify the trigger that should be suppressed
7.  Review latency distribution → if median > 0, see if a pattern can fire earlier
8.  Review continuation chains → identify TTL or chapter-propagation gaps
9.  Implement engine changes (BookResolver aliases, ExplicitParser, Config thresholds)
10. Re-run eval_metrics.py → verify improvement (numbers must go in the right direction)
11. Add a ReferenceWatcherTest case for each new/fixed pattern
12. Add curated rows to DbReplayTest only if the pattern is faithfully reproducible
    in row-by-row replay (i.e., the full reference fits in one row's text)
```

---

## Test Strategy

| Test file | Role | What goes in it |
|---|---|---|
| `ReferenceWatcherTest.kt` | Pattern coverage | One test per distinct pattern found in FN/FP analysis |
| `DbReplayTest.kt` | Session-level regression guard | Only rows where the full reference is self-contained in the row text |

`DbReplayTest` is **not** a training tool — it is a regression guard. A row goes in only when
the engine can reproduce the detection without the rolling-window context of adjacent rows.
When it can't (e.g. sticky from a previous row's rolling window), skip the row and cover the
pattern in `ReferenceWatcherTest` instead.

---

## Known Engine Gaps

From service4 / service5 analysis (2026-06-28). Fix in order: FN first, FP second, latency, continuation.

| Gap | Example | Location | Priority |
|---|---|---|---|
| ~~"царство" singular alias missing~~ | ~~"3 царство 18.42" → nothing~~ | ~~`BookResolver`~~ | ~~FN~~ **fixed** |
| ~~Period notation `N.M` not parsed~~ | tokenizer already strips `.` to space; was only blocked by alias gap above | — | **not a bug** |
| "27-й стих" parsed as chapter when no inline глава | s4r269 → John 27:28 FP | `ExplicitParser` ordinal disambiguation | FP |
| Synthetic stress corpus not built | — | test tooling | coverage |
| Cadence-adaptive sticky TTL | many book changes/min → shrink TTL | `ReferenceWatcher` / `Config` | continuation |
| Fuzzy book matching beyond prefix | "Ивангелие" → still resolves via "Матфея" but not standalone | `BookResolver.resolveStem` | FN (low freq) |
| 3-char real-word aliases fire on EN track | "job"→Job, "am"→Amos, "ru"→Ruth | `BookResolver` per-language scoping | FP |
| `ContinuationEngine` scores against full growing transcript | overlap shrinks on long utterances | `ContinuationEngine` | speed |
| Bare ambiguous numbered books | "Коринфянам" without ordinal → can't pick 1 vs 2 | `ReferenceWatcher` | FN (low freq) |

---

## Metrics Baseline

_Fill in after `eval_metrics.py` is built and run._

| Session | Precision | Recall | Median latency | Continuation rate |
|---|---|---|---|---|
| service4 | — | — | — | — |
| service5 | — | — | — | — |

Record a new row here each time a batch of logs is processed so progress is visible over time.

---

## Critical Gotchas: Book Numbering

These have caused real bugs. Read before touching anything that maps a detection to a Bible verse.

### Book id ≠ book position

The engine emits a **canonical book id** (1=Genesis … 62=1 John … 66=Revelation).
A Bible's display list (`Bible.getBooks()`) is in the **file's order**, which is NOT canonical:

- The **Russian Synodal** Bible places the General Epistles (James, 1–2 Peter, 1–3 John, Jude)
  **right after Acts**, before Paul's letters. So 1 John sits at display **index 47**.
- Mapping `index = canonicalId − 1` sends **1 John (62) → index 61 → 2 Timothy**. Every NT
  epistle shifts. This was a real regression.
- **Fix**: map by book id field, not position: `Bible.getDisplayIndexForBookId(id)`.

### Psalm numbering: Hebrew/EN Ps 23 = Synodal Ps 22

LXX merges Ps 9+10, shifting most of the Psalter by one. Joel, Malachi, and 3 John also differ.

- The engine forwards `canonicalCodeStart/End` (Hebrew numbering, numbering-independent).
- The app resolves display number via `Bible.getVerseDetailsByCode()` using the per-Bible
  `codeToDisplayMap`. Works for any primary Bible.
- **Never** pass the engine's raw `chapter` straight to display when primary ≠ the engine's
  matched translation — that shows the wrong Psalm number.

### Ground-truth logs must use canonical book id

`live-references` is the ground truth. It must log **canonical book id**, not display position + 1.
Logging raw display position caused the original misdiagnosis: 1 John's Synodal index 47 + 1 = 48,
which looks like Galatians' canonical id — correct detection, wrong-looking log.

### Verification checklist before trusting a run

- Decode `live-references.book` and `detection-log.book` as canonical ids and confirm they match.
- Also watch the actual screen — a number in a log can coincide with a different book's id.
- Test with each Bible as primary (Synodal-ordered and Protestant-ordered) and exercise a Psalm.
- Books most likely to expose order bugs: NT General Epistles + Pastorals (James, 1 John, 2 Cor,
  Galatians all shift under the Synodal ordering).

---

## File Locations

```
ChurchPresenter-BLE/
  TRAINING_PLAN.md              — this file
  tools/
    match_training_data.py      — artifact pairing (exists)
    extract_training_samples.py — compact window extraction (to build)
    eval_metrics.py             — precision / recall / latency / continuation (to build)
  src/test/kotlin/engine/
    ReferenceWatcherTest.kt     — hardcoded pattern tests
    DbReplayTest.kt             — session regression guard

Downloads/bible-stt-logs-mac-arm/
  detection-log-<session>.jsonl
  live-references-<session>.jsonl
  candidate-log-<session>.jsonl   (when present)
  training-samples-<session>.jsonl  ← produced by extract_training_samples.py
```
