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
        b. Write a failing ReferenceWatcherTest
        c. Fix the engine (BookResolver alias, ExplicitParser, Config threshold)

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
| `ReferenceWatcherTest.kt` | Pattern regression guard | One test per distinct FN/FP pattern found in triage |

There is no service-level replay test. Every sermon is different — regression at the row-id level
doesn't transfer. Patterns are what transfer. Each fix to the engine gets a unit test that proves
the pattern works, and that test is the regression guard for all future services.

---

## Known Engine Gaps

Fix in order: FN first, FP second, PREMATURE third, latency last.

| Gap | Example | Location | Priority |
|---|---|---|---|
| "27-й стих" parsed as chapter when no inline глава | s4r269 → John 27:28 FP | `ExplicitParser` ordinal disambiguation | FP |
| PREMATURE verse detections | "John 3:1" before "John 3:16" | `Stabilizer` hold or CP debounce | PREMATURE |
| 3-char real-word aliases on EN track | "job"→Job, "am"→Amos | `BookResolver` per-language scoping | FP |
| Cadence-adaptive sticky TTL | many book changes/min → shrink TTL | `ReferenceWatcher` / `Config` | continuation |
| Bare ambiguous numbered books | "Коринфянам" without ordinal → 1 or 2? | `ReferenceWatcher` | FN (low freq) |

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
    ReferenceWatcherTest.kt       ← the only regression guard; grows with each new FN/FP

Downloads/bible-stt-logs-mac-arm/
  detection-log-<session>.jsonl
  live-references-<session>.jsonl
  candidate-log-<session>.jsonl   (when present)
  training-samples-<session>.jsonl  ← produced by extract_training_samples.py
```
