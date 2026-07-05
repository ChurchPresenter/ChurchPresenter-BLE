# BLE Reference Detection — Training Plan

## What this is for

The BLE engine detects Bible references from speech-to-text transcripts. Training data drives
improvements in four areas:

| Goal | Measure | Target |
|---|---|---|
| **Accuracy** | recall = TP / (TP + FN) | Detect more real references |
| **Precision** | precision = TP / (TP + FP) | Fewer wrong suggestions |
| **Speed** | median latency = (detection_ts − live_ts) ms | Engine beats the operator |
| **Continuation** | fewer premature detections, better verse-chain propagation | No wrong verse, no missed chain |

Ground truth is the **live-references log** — what the human operator actually put on screen.
The **detection-log** is what the engine suggested. The gap between them is what we fix.

---

## Artifact Types

| File | What it contains |
|---|---|
| `*.db` | Full service transcript (songs + sermons + announcements + prayers) |
| `detection-log-*.jsonl` | Every reference the engine suggested (book, chapter, verse, tier, source, ts, transcript) |
| `live-references-*.jsonl` | Every reference the operator put on screen (book, chapter, verse, ts_ms) |
| `candidate-log-*.jsonl` | Near-misses the engine considered but didn't fire (threshold tuning signal) |
| `sticky-log-*.jsonl` | Every sticky book/chapter change, **even when nothing was emitted** (`DetectionLogger.logStickyChange`, gated by `Config.logStickyChanges`) — independent of the other three; not read by `triage_report.py`. Only useful when hand-diagnosing an unexplained stale-sticky FN (see "Stale/unexplained sticky carryover" below) |

**Note**: DB files are whole-service transcriptions — 60–70 % of rows are music or non-sermon.
The training tools use `live-references.jsonl` as the time anchor, so only detections inside
the sermon window (bracketed by the first and last live-reference event) are counted. DB reads
are optional and only needed to see the exact text that was being spoken during an FN.

---

## Training Signals

Cross-referencing detection-log and live-references (within a ±90 s window per go-live event):

```
TP        — engine detected AND operator confirmed     → preserve; regression-test in ReferenceWatcherTest
PREMATURE — engine fired right book+chapter wrong verse first, then corrected
            e.g. "John 3:1" at T-4s then "John 3:16" at T-2s → operator went live on John 3:16
            → tune Stabilizer debounce or CP display hold
FP        — wrong detection inside a live-ref window  → suppress; add precision-negative test
FN        — operator confirmed, engine missed          → fix; find the missing pattern
```

---

## Workflow: New Logs Arrive

```
1.  Drop detection-log + live-references into Downloads/bible-stt-logs-mac-arm/

2.  Run triage_report.py  (no DB needed — fast, tiny output):
        python tools/triage_report.py \
            --dlog detection-log-*.jsonl \
            --lref live-references-*.jsonl
    → paste the plain-text output into a conversation with Claude

3.  For each FN in the report:
        a. Identify the missing pattern from the reference text alone
           (if ambiguous, do a targeted DB query for that timestamp)
        b. If the FN looks like an unexplained stale/wrong sticky (no matching book/chapter anywhere
           in the preceding transcript — the 2026-07-05 Revelation/Proverbs pattern), cross-reference
           the timestamp against that session's sticky-log-*.jsonl (if present) to see exactly which
           utterance mutated the sticky, instead of guessing blind
        c. Write a failing ReferenceWatcherTest (or ContinuationEngineTest for chapter-scan/history)
        d. Fix the engine (BookResolver alias, ReferenceWatcher, ContinuationEngine, Config threshold)

4.  For each FP in the report:
        a. Read the trigger text (already in the report — from detection-log transcript field)
        b. Add a precision-negative assertNoEmit() test in ReferenceWatcherTest if not already covered

5.  For each PREMATURE in the report:
        a. Note the typical wrong→correct delay (shown in the report)
        b. If delay > 2s consistently: tune Stabilizer.minStabilityMs or CP display debounce

6.  Re-run triage_report.py → numbers must improve before committing

7.  For batch JSONL / eval_metrics table (optional, when processing multiple sessions):
        python tools/extract_training_samples.py \
            --db service.db --dlog ... --lref ... --out training-samples-sN.jsonl
        python tools/eval_metrics.py training-samples-s*.jsonl
```

---

## Test Strategy

| Test file | Role | What goes in it |
|---|---|---|
| `ReferenceWatcherTest.kt` | Explicit/sticky parsing regression guard | One test per distinct FN/FP pattern found in triage (book/chapter/verse parsing, ordinal resolution, split-utterance behavior) |
| `ContinuationEngineTest.kt` | Content-matching regression guard | Sequential next-verse, chapter-scan, and chapter-history resolution/ambiguity-gate cases (synthetic in-memory fixtures — no real Bible files needed) |
| `DetectionLoggerTest.kt` | Logging-output guard | Minimal coverage of the sticky-change log's file/field shape |

There is no service-level replay test. Every sermon is different — regression at the row-id level
doesn't transfer. Patterns are what transfer. Each fix to the engine gets a unit test that proves
the pattern works, and that test is the regression guard for all future services.

---

## Known Engine Gaps

Fix in order: FN first, FP second, PREMATURE third, latency last.

| Gap | Example | Location | Priority |
|---|---|---|---|
| "27-й стих" parsed as chapter when no inline глава | s4r269 → John 27:28 FP | `ExplicitParser` ordinal disambiguation | FP |
| PREMATURE verse detections | "John 3:1" before "John 3:16" | `Stabilizer` hold or CP debounce | PREMATURE — **partially resolved, see below** |
| 3-char real-word aliases on EN track | "job"→Job, "am"→Amos | `BookResolver` per-language scoping | FP |
| Cadence-adaptive sticky TTL | many book changes/min → shrink TTL | `ReferenceWatcher` / `Config` | continuation |
| Bare ambiguous numbered books | "Коринфянам"/"Книга царств" without ordinal → which one? | `ReferenceWatcher.resolveNumberedBookAt` | FN (low freq, accepted — deliberately unresolved, see below) |
| Stale/unexplained sticky carryover | Revelation 66:11:1, Proverbs 20:1:15 with no matching text anywhere in the preceding transcript | `ReferenceWatcher.emit` (silent mutation) | FN — **undiagnosed; instrumentation now built, see below** |
| Chapter-scope/history tuning unvalidated | `Config.chapterScopeMinAgreement`/`chapterScopeMinRatio` are starting guesses (0.10 / 1.5) | `Config` | needs real data before trusting default gates |

**Resolved**: spelled-Russian-ordinal + numbered-book resolution ("Первая книга царств" → 1 Samuel,
"Третья царств" → 1 Kings, "Первое Коринфянам" → 1 Corinthians) was previously a total miss — only
digit-adjacent aliases ("1 царств") worked, and word ordinals were special-cased for John/Peter only.
`ReferenceWatcher.resolveNumberedBookAt` (formerly `resolveEpistleAt`) now generalizes this via a
`NumberedBookSpec` table (base id + variant count) covering Царств (9-12), Паралипоменон (13-14),
Коринфянам (46-47), Фессалоникийцам/Солунян (52-53), Тимофею (54-55), plus John/Peter as before —
digit *or* spelled ordinal, with or without an intervening "книга"/"послание" filler word. An explicit
ordinal is always required for the new families (no "bare marker defaults to 1st" convention, unlike
John/Peter) — bare "Коринфянам"/"Книга царств" with no ordinal stays intentionally unresolved (see the
gap row above). Further inflected forms (genitive "тимофея", "коринфян", etc.) should be added to
`NUMBERED_BOOK_FORMS` the same way as future training data surfaces them. Also fixed as part of the same
pass: `ReferenceWatcher.emit`'s bare-chapter-continuation branch (book already known via sticky, a later
utterance names only the chapter) used to fabricate a "verse 1" the same way the book+chapter-together
case once did — now silently primes the sticky and waits for a real verse instead, matching book+chapter
grammar split fully across separate utterances ("Первая книга царств." → "15 глава." → "22 стих."
resolves correctly with nothing shown until the last step).

**Built**: chapter-scoped verse resolution, so **book+chapter alone is enough** — the precise
`"...15 глава, с 22 по 30 стих."` verse-range grammar is no longer required once book+chapter is known.
`ContinuationEngine.checkChapterScope` scores every verse in the known chapter(s) against what was
actually spoken (`AgreementScorer`, the same tool that validates reverse-lookup hits), gated by a
floor + margin-over-runner-up (mirroring `ReverseLookup`'s ratio gate) so it stays silent rather than
guessing when two candidates score close together. Logged as `matchType="chapter-scan"` (distinct from
the cheap sequential `"continuation"` next-3 check) so triage can tell them apart.

**Built** (previously "designed, not yet built" above): chapter-history / multiple stickies. A preacher
revisiting an *earlier* chapter from the same service, without restating its book/chapter, now resolves —
`checkChapterScope`'s candidate pool is `{current sticky} ∪ {UtteranceState.chapterHistory}`, an
unbounded, same-service-only, in-memory, recency-deduplicated set of every chapter the sticky has
pointed at this service (populated in `DetectionEngine.runDetection` right after the explicit/sticky
watcher runs, so a book+chapter-only announcement is remembered even though it no longer emits a `Ref`).
Same scoring/gate as chapter-scan; logged as `matchType="chapter-history"` when the winning verse is a
*different* chapter than the current sticky (rarer/riskier than matching the expected one — worth
telling apart in triage). **Not yet tuned**: `Config.chapterScopeMinAgreement`/`chapterScopeMinRatio`
are the same starting guesses noted in the gap row above — widening the candidate pool to multiple
chapters raises ambiguity risk, so these may need their own, stricter values once real cross-chapter-
jump examples arrive; don't assume the current defaults are safe at scale without checking `chapter-
history`-tagged rows specifically in the next real session's detection log.

**Instrumentation built, still undiagnosed**: the stale-sticky-carryover FNs (Revelation 66:11:1,
Proverbs 20:1:15) from the original training session are still unexplained — the preceding 9 minutes of
transcript/translation never mentioned either book, so it wasn't the translation-track bug (already
fixed) and isn't explainable from the detection-log alone (it only records *emitted* detections; a
silent, never-emitted sticky mutation left no trace). `sticky-log-*.jsonl` (see Artifact Types above)
now traces every sticky change regardless of whether anything emits — if another unexplained jump shows
up in a future session, cross-reference its timestamp against that session's `sticky-log-*.jsonl` to see
exactly what utterance caused it, then fix from there. Independent file, gated by
`Config.logStickyChanges` (default on) — `triage_report.py` doesn't read it and is unaffected.

(Note, unchanged: quoting/reciting an earlier passage by its actual *text*, with no reference cited at
all, already works today regardless of history — `ReverseLookup.search()` runs BM25 over the whole Bible
on every utterance already, independent of the sticky/history mechanism above.)

---

## Critical Gotchas: Book Numbering

### Book id ≠ book position

The engine emits a **canonical book id** (1=Genesis … 62=1 John … 66=Revelation).
A Bible's display list (`Bible.getBooks()`) is in the **file's order**, which is NOT canonical:

- The **Russian Synodal** Bible places the General Epistles (James, 1–2 Peter, 1–3 John, Jude)
  **right after Acts**, before Paul's letters. So 1 John sits at display **index 47**.
- Mapping `index = canonicalId − 1` sends **1 John (62) → index 61 → 2 Timothy**. Every NT
  epistle shifts. This was a real regression.
- **Fix**: map by book id field, not position: `Bible.getDisplayIndexForBookId(id)`.

### Psalm numbering: Hebrew/EN Ps 23 = Synodal Ps 22

LXX merges Ps 9+10, shifting most of the Psalter by one.

- The engine forwards `canonicalCodeStart/End` (Hebrew numbering).
- The app resolves display number via `Bible.getVerseDetailsByCode()` using the per-Bible
  `codeToDisplayMap`. Works for any primary Bible.
- **Never** pass the engine's raw `chapter` straight to display when primary ≠ the engine's
  matched translation.

### Ground-truth logs must use canonical book id

`live-references` must log **canonical book id**, not display position + 1.

---

## File Locations

```
ChurchPresenter-BLE/
  TRAINING_PLAN.md                ← this file
  tools/
    triage_report.py              ← quick plain-text report, no DB needed  ← START HERE
    extract_training_samples.py   ← compact JSONL per session (DB needed for FN anchor text)
    eval_metrics.py               ← precision / recall / premature / latency table
    match_training_data.py        ← artifact pairing helper
  src/test/kotlin/engine/
    ReferenceWatcherTest.kt       ← explicit/sticky parsing regression guard; grows with each new FN/FP
    ContinuationEngineTest.kt     ← chapter-scan / chapter-history regression guard
    DetectionLoggerTest.kt        ← sticky-change log output guard

Downloads/bible-stt-logs-mac-arm/
  detection-log-<session>.jsonl
  live-references-<session>.jsonl
  candidate-log-<session>.jsonl   (when present)
  sticky-log-<session>.jsonl      (when present — hand-diagnosis only, not read by triage_report.py)
  training-samples-<session>.jsonl  ← produced by extract_training_samples.py
```
