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
| `sticky-log-*.jsonl` | Every sticky book/chapter change, **even when nothing was emitted** (`DetectionLogger.logStickyChange`, gated by `Config.logStickyChanges`) — independent of the other three; not read by `triage_report.py`, but read by `stickyAudit` (see Workflow below) |
| `operator-flags-*.jsonl` | Live operator feedback from ChurchPresenter's "Help Dev" mode (`STTTab`'s `helpDevMode` checkbox, `TrainingDataLogger.logOperatorFlag`) — the operator flags a BLE mistake in real time during a service via 3 buttons on the Bible tab: `kind="wrong_passage"` / `"premature"` (right book/chapter, wrong verse — a `Stabilizer` debounce signal, distinct from a parsing bug) both carry `book`/`chapter`/`verseStart`/`verseEnd` for whatever was live at click time; `kind="missed_passage"` carries only `ts_ms`/`segmentId` (no detection to anchor to — cross-reference the `.db` by timestamp during triage, same as any other FN). Not yet read by `triage_report.py`/`stickyAudit` — cross-reference by hand for now |
| `<sessionId>.db` | Also with "Help Dev" on: `STTManager` pulls a fresh read-only copy of the live STT session's `.db` from the STT server (`GET /api/transcription/status` + `/api/file-manager/download`) into this same folder every 60s while connected, so the whole session (db + all jsonl logs above) can be handed off as one folder — no more separate manual pull from the STT server after the fact. Subject to the same 30-day retention sweep as the jsonl logs (`TrainingDataLogger.cleanupOldLogsOnce()`) |

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

2b. If a sticky-log-*.jsonl is present, ALSO run stickyAudit (no DB needed, seconds to run) —
    replaces the old "hand cross-reference every timestamp" step with an automated pre-triage:
        ./gradlew stickyAudit --args="/path/to/sticky-log-<session>.jsonl"
    → paste the plain-text output into the conversation alongside triage_report.py's. It classifies
    every recorded sticky jump using the SAME BookResolver.ALIASES/resolveStem the live engine uses
    (never drifts out of sync as the table grows), grouped by how well-supported the new book was:
        UNEXPLAINED          — no alias/stem match anywhere in the text — a brand-new bug pattern,
                                top priority (this is what "unexplained stale sticky" used to require
                                hours of manual timestamp-hunting to even notice)
        CHAPTER-CLEARED       — same book, chapter nulled — the exact shape of the 2026-07-05
                                same-book-reflush bug; should be near-zero now, any hit is a
                                regression or a new variant
        SHORT ALIAS           — book resolved only via a short (<6-char) exact alias that might
                                double as ordinary vocabulary (the "бытие" shape)
        STEM OVER-EXTENSION   — book resolved only by extending well past its alias's stem (the
                                "открывает"/"откр" shape)
    CONFIDENT/OTHER rows are collapsed to a count — don't need review. See
    `src/main/kotlin/engine/tools/StickyAudit.kt` for the exact heuristic and why a naive
    "any stem match is risky" first version was too noisy to use (flagged 35 of 61 real jumps).

3.  For each FN in the report:
        a. Identify the missing pattern from the reference text alone
           (if ambiguous, do a targeted DB query for that timestamp)
        b. If the FN looks like an unexplained stale/wrong sticky, check the stickyAudit output first
           (step 2b) — it now does the timestamp cross-referencing automatically
        c. Write a failing ReferenceWatcherTest (or ContinuationEngineTest for chapter-scan/history).
           If the fix generalizes a mechanism (not just one word/phrase), also add or extend a
           mechanism-level test — see Test Strategy below — so the next word that falls into the same
           trap is caught automatically, not just the one found today
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
| `ReferenceWatcherTest.kt` | Explicit/sticky parsing regression guard | One test per distinct FN/FP pattern found in triage (book/chapter/verse parsing, ordinal resolution, split-utterance behavior), **plus** mechanism-level generalization tests (see below) |
| `ContinuationEngineTest.kt` | Content-matching regression guard | Sequential next-verse, chapter-scan, and chapter-history resolution/ambiguity-gate cases (synthetic in-memory fixtures — no real Bible files needed) |
| `DetectionLoggerTest.kt` | Logging-output guard | Minimal coverage of the sticky-change log's file/field shape |

Service-level replay exists since 2026-07: `DbReplayTest` replays an archived `.db`
deterministically (injected clock) against a committed golden, and `replayEval` scores a replay
against the operator's live-references/suggestion-outcomes ground truth per matchType. Goldens
lock the CURRENT behavior so any engine change shows up as an explicit, reviewable diff — they
complement (not replace) the pattern-level unit tests below: each fix still gets a unit test
proving the generalized pattern, and the golden diff is regenerated + summarized in the same
commit.

**Mechanism-level tests** (2026-07-05 §3, `ReferenceWatcherTest.kt`): a fix that generalizes a
mechanism (not just one word/transcript) should get a test that generalizes too, so the *next* word
that falls into the same trap is caught before a live session hits it, not after:
- **Invariant tests across multiple inputs** — e.g. "same-book reflush preserves chapter" is tested
  across several different books/chapters, not just the two real transcripts that first exposed it.
- **Fuzz tests over an extensible table** — e.g. the `AMBIGUOUS_BOOK_FORMS` corroboration gate is
  tested by iterating the table itself (with `kotlin.random.Random` + a fixed seed, hand-rolled — no
  property-testing library) rather than hardcoding today's two words, so adding a new ambiguous form
  to the table automatically gets fuzz coverage with no extra test-writing.
- **Growing negative corpus** — a single `NEGATIVE_CORPUS` list of real non-reference sentences,
  asserting none of them ever emits or hijacks an unset sticky. Append newly-confirmed-benign trigger
  text here as future sessions surface it, in addition to (not instead of) a dedicated named test for
  any actual fix — this keeps negative coverage growing every session instead of living only in
  one-off named tests.

---

## Known Engine Gaps

Fix in order: FN first, FP second, PREMATURE third, latency last.

| Gap | Example | Location | Priority |
|---|---|---|---|
| "27-й стих" parsed as chapter when no inline глава | s4r269 → John 27:28 FP | `ReferenceWatcher` ordinal disambiguation (ExplicitParser retired 2026-07) | FP |
| Short-alias corroboration too loose for ordinary counting/naming phrases | "Two songs" (translation of "Два пения")→Song of Solomon (22); "осия" ("stubbornness"-adjacent RU word) → Hosea (28), then stuck as the wrong sticky book for ~40 min, causing a cluster of FNs once the service moved to Psalm 14 | `ReferenceWatcher.hasAmbiguousBookCorroboration` — a bare "N X" count phrase satisfies the digit-within-2-tokens check identically to a real "chapter N" citation | FP / FN (recurred with the same "songs" shape in both 2026-07-12 sessions; the "осия" case shows a single bad corroboration can corrupt the sticky book for the rest of the passage, not just one emission — not root-caused deeply enough this session to fix safely, see 2026-07-12 Resolved entry below for what *was* fixed) |
| PREMATURE verse detections | "John 3:1" before "John 3:16" | `Stabilizer` hold or CP debounce | PREMATURE — **partially resolved, see below** |
| 3-char real-word aliases on EN track | "job"→Job, "am"→Amos | `BookResolver` per-language scoping | FP |
| Cadence-adaptive sticky TTL | many book changes/min → shrink TTL | `ReferenceWatcher` / `Config` | continuation |
| Bare ambiguous numbered books | "Коринфянам"/"Книга царств" without ordinal → which one? | `ReferenceWatcher.resolveNumberedBookAt` | FN (low freq, accepted — deliberately unresolved, see below) |
| Chapter-scope/history tuning unvalidated | `Config.chapterScopeMinAgreement`/`chapterScopeMinRatio` are starting guesses (0.10 / 1.5) | `Config` | **RESOLVED (provisionally) 2026-07-09** — the 2026-07-08 replay showed 94 chapter-history emissions / 0 TPs; now gated structurally (candidate pool = sticky + 5 most-recent chapters, verse-coverage floors 0.45 scan / 0.6 history, agreement 0.20) → 4 emissions, recall unchanged. Values provisional until more services are recorded |
| Sequential verse-by-verse reading latency | Luke 2:41-52, Proverbs 3:3-6 read consecutively (one verse per operator click, 4-15s apart) — engine confirms most verses correctly via reverse/continuation, but 5-10s after the operator already advanced (misses triage's +5s window), and ~3 interior verses get no detection at all | `ContinuationEngine` / `Stabilizer` (root cause undiagnosed — may be inherent STT segment-finalization latency, not an engine defect) | **RESOLVED (coverage half) 2026-07-09** — sequential check now scores verse-side coverage instead of window-diluted overlap; Matthew 9:37/11:30 recovered on the replay. Residual STT segment-finalization latency remains outside the engine |
| Stem-prefix over-match on short RU aliases | resolveStem's 4-letter minimum lets short aliases like "откр" (Revelation) match unrelated longer words sharing the root ("открывает"/"открылся" — ordinary verb forms, "he reveals"/"was revealed") | `BookResolver.resolveStem` / `ReferenceWatcher.classify` | **RESOLVED 2026-07-09** — over-extension gate in classify: a token ≥3 chars longer than its matched stem needs corroboration (digit/marker nearby); verified on the 2026-07-08 replay (removed the Revelation sticky pollution, zero TP loss) |
| "song"/"job"/"при" short-alias false positives | EN translation of Russian singing vocabulary ("петь"/"пение") repeatedly resolves to "song"→Song of Solomon (22); "при" (a common preposition) is itself a registered Proverbs (20) alias | `BookResolver.ALIASES` | **RESOLVED 2026-07-09** — single-token exact aliases ≤4 chars need the same corroboration ambiguous forms do; multi-token and ≥5-char aliases unaffected |
| "повторить" stem-overextension | "повторить" ("to repeat", ordinary verb) shares a stem with a Deuteronomy ("Второзаконие") alias | `BookResolver.resolveStem` | **RESOLVED 2026-07-09** — covered by the over-extension gate above |
| EN keyword-after-number citation order | "Job chapter 3 verse 2" parses with chapter/verse swapped | `ReferenceWatcher.interpret` keyword handling | **RESOLVED 2026-07-09** — pending-keyword binding: a chapter/verse keyword with no preceding number binds the NEXT one (both languages; the replay showed the old inversion emitting Matthew 9:9 / Genesis 24:2 as 0.95 explicits). The other half of this gap (colon citations followed by prose, "Job 3:2 tells us…") was **RESOLVED 2026-07-09**: a colon binds buffered numbers, so trailing prose no longer wipes the verse (`colonSeen` in interpret); dots between digits now normalize to colons ("Исайя 26.3"); "исайя" STT spelling added as an Isaiah alias. Verified on the 2026-07-08 replay: rows 757/769/843 upgraded from weak staged detections to explicit tier-1, zero TP loss |

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

**Resolved** (2026-07-05 session, diagnosed via the `sticky-log` instrumentation described below):
the original session's stale-sticky-carryover FNs (Revelation 66:11:1, Proverbs 20:1:15) had no
single named cause at the time, but a second real session reproduced the same *symptom shape*
(sticky book/chapter jumping with no matching text) twice, and `sticky-log-*.jsonl` timestamps made
both traceable to actual utterances this time. Two distinct, unrelated root causes, both fixed in
`ReferenceWatcher.kt`:
- **Same-book re-mention clobbers the sticky chapter.** `emit()`'s book branch unconditionally set
  `sticky.watchChapter = chapter`, including when `chapter` was null on *this* flush — the comment
  said "a new book always resets the carried chapter," but nothing distinguished a genuinely new
  book from the *same* book merely mentioned again later in the same growing, bilingual utterance
  (recall: `DetectionEngine` feeds the full accumulated transcript+translation through
  `ReferenceWatcher.process()` on every STT update, not a delta — see `runDetection`). Confirmed
  twice: Russian's common "N глава [Книги]" word order (chapter number *before* the trailing book
  name — `"...в 21 главе Откровения."` nulled chapter 21 right after setting it), and a bilingual
  re-mention (`"...Бытия, в 12 главе..."` + EN translation `"...Genesis, in chapter 12..."`, where
  English's keyword-before-number order lets the filler "in" wipe the buffered chapter number before
  the segment-end flush, which then read `chapter=null` off the redundant "Genesis" atom). Fixed by
  only letting an absent chapter reset the sticky when the book atom's `curBook` differs from the
  already-current `sticky.watchBook`; a same-book re-mention with no chapter attached now preserves
  whatever chapter was already there instead of nulling it.
- **Ambiguous common-word RU aliases hijack the sticky book.** The Russian-track sibling of the
  already-known "3-char real-word aliases on EN track" gap above: dative/locative "Иоанну"/"Иоанне"
  ("to/about John") narrate the apostle by name in ordinary prose (Revelation narrates in first
  person) rather than citing the Gospel — a real citation is always genitive/nominative
  ("Иоанна"/"Иоанн", untouched, already well-tested) — and "бытие"/"быт" is ordinary vocabulary
  ("being/existence") with the identical surface form as the book title, no grammatical
  discriminator available. Both hijacked `sticky.watchBook` mid-Revelation-reading with zero
  supporting context. Fixed via a new `ReferenceWatcher.AMBIGUOUS_BOOK_FORMS` table (mirroring
  `NUMBERED_BOOK_FORMS`'s extensibility) plus `hasAmbiguousBookCorroboration`: these specific bare
  forms only resolve to a book atom when reinforced by a chapter/verse digit within 2 tokens, or an
  explicit book/epistle/gospel marker noun in the preceding 2 tokens — bare prepositions (в/от/из)
  deliberately do **not** count as corroboration (too common in ordinary prose; the real "бытие"
  false positive was itself `"...в то бытие"`). Trade-off, stated explicitly: a fully bare "Бытие"
  mention with the chapter given only much later will now fail to prime the sticky (recall loss) —
  there's no textual way to distinguish that from ordinary prose using the same word. This mirrors
  the already-accepted "bare Коринфянам/Царств with a marker but no ordinal stays unresolved" gap
  above. Add further ambiguous forms to `AMBIGUOUS_BOOK_FORMS` the same way as future training data
  surfaces them.
  While verifying these fixes, a **third, mechanically distinct** false-positive was newly observed
  but not fixed this session (see the Known Engine Gaps table above): `resolveStem`'s 4-letter
  minimum stem length lets the short alias "откр" (an abbreviation for Откровение/Revelation) match
  as a *prefix* of unrelated longer words sharing that root — "открывает"/"открылся" ("he
  reveals"/"was revealed", ordinary verb forms) — which the `AMBIGUOUS_BOOK_FORMS` fix above does
  not catch, since that gate keys off the exact input token ("откр"), not the stem a longer token
  happened to match through `resolveStem`. Confirmed in the same real transcript that motivated the
  "Иоанну" fix above (`"...он удостоил его тому, что открылся."` falsely flipped the sticky book to
  Revelation). Needs a different gating point (by matched stem, not exact token) — left for a future
  session rather than bolted on speculatively here.

**Resolved** (2026-07-12 session, two new sessions' `stickyAudit`/`replayEval`/`triage_report.py`
surfaced two previously-undiagnosed bugs, both in `ReferenceWatcher.kt`, both root-caused against
real `.db`/log text rather than guessed):
- **Inflected ambiguous words bypassed the 2026-07-05 corroboration gate.** `AMBIGUOUS_BOOK_FORMS`
  is keyed on exact surface tokens ("бытие"/"быт"), so genitive "бытия" (real trace: a machine-
  translation tail of "...level of his **being**") reached the inflection-tolerant `resolveStem`
  path unconditionally (its stem extension is only 1 char, under the over-extension threshold) and
  falsely resolved to Genesis — sandwiched between two genuine "Psalm 14" mentions in one bilingual
  utterance, nulling the sticky chapter mid-sermon (`stickyAudit`'s own `CHAPTER-CLEARED SAME-BOOK`
  category, "should be ~zero"). Fixed with a new stem-keyed `AMBIGUOUS_BOOK_STEMS` map, gating
  `resolveStem` matches by `BookResolver.stemOf(token)` in addition to the exact-token check —
  covers every case ending of "бытие" without hardcoding each one. **Deliberately scoped to just
  "бытие"**, not folded into a blanket stem-based gate for all of `AMBIGUOUS_BOOK_FORMS`: "иоанну"/
  "иоанне"'s ambiguity is case-specific (only dative/locative), and share a stem with the already-
  unconditional genitive/nominative "иоанна"/"иоанн" — stemming those too would wrongly demand
  corroboration for forms that are already correct today.
- **A verse keyword with no preceding chapter keyword swept the unclaimed chapter number into the
  verse slot.** Real trace: "Psalm 10, verse 13" (no chapter keyword for "10" — chapter by the bare
  "book N" convention) parsed with chapter/verse transposed (`ref: "Psalm 30:10"` — with the STT's
  own transient "13"→"30" mis-hearing folded in) because `interpret()`'s `Atom.VerseKw` branch swept
  *any* non-empty `recent` buffer into `verseStart` with no check for whether a chapter had actually
  been bound. Shipped to the operator as `matchType: explicit` — one of the two match types that
  auto-go-live (no staging safety net). A third, previously-unaddressed shape, distinct from the two
  2026-07-09 keyword-order fixes below. Fixed by reusing `assignChapterFromRecent()` (already used
  by `Atom.ChapKw`/`Atom.Colon`) when a verse keyword arrives with no chapter bound yet and a book
  named this call — the same "bare book number = chapter" convention `flush()`'s own leftover
  fallback already applies.

Both fixes are unit-tested against the real session trigger text (dual-track transcript+translation
for the first; the exact DB row text, both the STT's transient mis-hearing and the corrected final
text, for the second) plus a mechanism-level generalization test, in `ReferenceWatcherTest.kt`.
Golden diff on the two sessions that motivated this: `golden-2026-07-12_092012.jsonl` is
byte-identical (neither bug's shape occurs in that session — clean no-op); `golden-2026-07-12_173830.jsonl`
gained exactly one new correct event (`19:10:13`, previously silently dropped) and nothing else
changed. Note: the specific `CHAPTER-CLEARED` sticky-log event that motivated the first fix does
**not** disappear from a `stickyAudit` re-run of the *same recorded* `sticky-log-*.jsonl` file — that
file is a frozen artifact of the live (pre-fix) run and can't retroactively change; the fix is
verified directly (the exact real trigger text, unit-tested) rather than via re-triaging old logs.
The `.db`-replay's own segment reconstruction didn't reproduce the identical utterance boundary
either, so 173830's overall recall only moved from 13/25 to 14/25 in this pass — most of that
session's remaining Psalm-14-window FNs trace to the separate, unfixed "осия"/Hosea sticky-book
hijack noted in the gap table above (a stuck wrong sticky book from ~40 minutes earlier in the
service), not the two bugs fixed here.

`sticky-log-*.jsonl` (see Artifact Types above) traces every sticky change regardless of whether
anything emits. Independent file, gated by `Config.logStickyChanges` (default on) —
`triage_report.py` doesn't read it and is unaffected.

**Built** (2026-07-05 §3): the manual timestamp cross-referencing above — hours of work each of the
last two sessions — is now automated. `stickyAudit` (`src/main/kotlin/engine/tools/StickyAudit.kt`,
run via `./gradlew stickyAudit --args="<sticky-log path>"`) replays every recorded jump against the
live engine's own `BookResolver.ALIASES`/`resolveStem` and buckets it (see Workflow step 2b above).
Zero production code touched — the tool only *reads* already-public engine data, so it can never
drift out of sync as the alias table grows. Smoke-tested against the 2026-07-05 session's real
`sticky-log`: correctly bucketed all three already-fixed/known bugs (`бытие`, `откр`/`открывает`,
and — a bonus catch — a fourth `CHAPTER-CLEARED` instance from restating "Евангелие от Луки" twice in
one utterance, the exact same-book-reflush shape, that manual review hadn't caught), at zero
`UNEXPLAINED` false negatives and with only 26 of 67 total jumps needing any human look (down from
35+ with a naive "any stem match is risky" first version — see the tool's own doc comment for why
that version was too noisy). Also surfaced two brand-new candidates ("song"/"при", "повторить" — see
the gap table above) that manual review across two full sessions had missed entirely.

(Note, unchanged: quoting/reciting an earlier passage by its actual *text*, with no reference cited at
all, already works today regardless of history — `ReverseLookup.search()` runs BM25 over the whole Bible
on every utterance already, independent of the sticky/history mechanism above.)

**Consuming-app context** (2026-07 — no engine change, documented here since it affects how urgent the
gaps above are): ChurchPresenter's "Follow Along" auto-follow feature now tiers its go-live decision by
`matchType` (see `AGENT.md`'s "Bible Follow Along — Tiered Auto-Follow" note in the main repo). Only
`explicit`/`continuation` detections push to the output screen automatically; `chapter-scan`/
`chapter-history`/`reverse` now just stage the browse view for operator confirmation. Practical effect:
false positives whose `matchType` is `reverse`/`chapter-scan`/`chapter-history` (the short-alias/
stem-overmatch gaps above, e.g. "song"/"откр"/"повторить") can no longer put a wrong verse live
unattended during a real service — they're still worth fixing for recall/precision, but they're no
longer a live-service safety issue. An `explicit`/`continuation` false positive still goes live
instantly, so those two match types remain the highest-stakes category to get right.

The app's own training logs (`live-references-*.jsonl` / `suggestion-outcomes-*.jsonl`, produced by
`TrainingDataLogger` — same files `triage_report.py` reads) now also carry this `matchType` field, so
a future session can directly measure acceptance/dismissal rate per tier (e.g. "were staged
chapter-history suggestions mostly accepted via double-click, or mostly ignored?") instead of relying
on the engine's own detection-log alone.

**Validation-coverage gap: the engine is Russian-speech-validated, not English-speech-validated**
(noted 2026-07-12). Every gate/threshold above (`AMBIGUOUS_BOOK_FORMS`, `STEM_MAX_EXTENSION_UNCORROBORATED`,
`SHORT_ALIAS_MAX_LEN`, the corroboration digit-window, all of it) was tuned and tested exclusively
against Russian sermon speech plus its English *machine translation* — every real trigger transcript
quoted anywhere in this file or `AGENT.md` is Russian-source. `2026-07-12`'s evening service
(`2026-07-12_173830.db`) was the first session with substantial native English-source preaching
(`source_language='en'` on 235 of 930 final rows — the morning service that same day was 100%
Russian-source). Checked `source_language` at each bug/false-positive found that session: the
"verse keyword with no chapter keyword" shape (fixed 2026-07-12, see Resolved above) and two of
three short-alias false hits ("james"/"psalm" hijacking the sticky book) all trace to English-source
rows specifically — natural English citation grammar ("Psalm 10, verse 13", no "chapter" word) and
English narrative prose colliding with English book-abbreviation aliases are plausible reasons these
particular shapes never surfaced in two years of Russian-source data. The third short-alias hit that
same session ("осия"→Hosea) is a Russian-source row — same known bug class as "song"/"job", not new.
**Practical effect**: don't assume today's fixes "cover" English speech generally — this is a single
session's worth of data on an entire previously-untested dimension. The next English-heavy session
should get extra scrutiny rather than being waved through, and any future short-alias false positive
should be checked against `source_language` before assuming it's the same, already-well-tuned bug
class as the Russian-track gaps above.

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
  build.gradle.kts                ← `stickyAudit` JavaExec task (engine.tools.StickyAuditKt)
  tools/
    triage_report.py              ← quick plain-text report, no DB needed  ← START HERE
    extract_training_samples.py   ← compact JSONL per session (DB needed for FN anchor text)
    eval_metrics.py               ← precision / recall / premature / latency table
    match_training_data.py        ← artifact pairing helper
  src/main/kotlin/engine/tools/
    StickyAudit.kt                 ← sticky-log auto-triage, `./gradlew stickyAudit --args="<path>"`
  src/test/kotlin/engine/
    ReferenceWatcherTest.kt       ← explicit/sticky parsing regression guard; grows with each new FN/FP,
                                     plus mechanism-level invariant/fuzz tests and a growing negative corpus
    ContinuationEngineTest.kt     ← chapter-scan / chapter-history regression guard
    DetectionLoggerTest.kt        ← sticky-change log output guard

Downloads/bible-stt-logs-mac-arm/
  detection-log-<session>.jsonl
  live-references-<session>.jsonl
  candidate-log-<session>.jsonl   (when present)
  sticky-log-<session>.jsonl      (when present — run through `stickyAudit`, see Workflow step 2b)
  training-samples-<session>.jsonl  ← produced by extract_training_samples.py
```
