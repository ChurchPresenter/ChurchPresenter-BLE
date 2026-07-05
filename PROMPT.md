# Prompt: BLE training pass (paste this to start a fresh session)

We have a training guide, read that: `TRAINING_PLAN.md` (in this directory, `ChurchPresenter-BLE/`).
Here are the new session files:

- `<path-to>/detection-log-<session>.jsonl`
- `<path-to>/live-references-<session>.jsonl`
- `<path-to>/<session>.db`
- `<path-to>/sticky-log-<session>.jsonl` (if present — hand-diagnosis only, see below)
- `<path-to>/candidate-log-<session>.jsonl` (if present)

Follow the workflow in `TRAINING_PLAN.md` exactly: run `tools/triage_report.py` on the detection-log +
live-references first, then work each FN/FP/PREMATURE in priority order. Use the `.db` file only when a
pattern is ambiguous from the report text alone. If a stale/wrong sticky shows up with no matching
book/chapter anywhere in the preceding transcript (the Revelation/Proverbs pattern from the first
session), cross-reference `sticky-log-*.jsonl` for that timestamp before guessing.

A few things I want you to actually do differently from a plain "read the guide and go" pass, based on
what worked well and what didn't last time:

1. **Chase the real root cause, not the nearest patch.** When a test phrase and the actual trigger
   phrase differ (e.g. an abbreviated/digit form vs. the natural spoken form), don't write the test
   against whichever one happens to pass easily — dig until you're testing the phrasing that will
   actually recur. If you catch yourself writing a very precise, unlikely-to-repeat test string, stop
   and ask whether the *pattern* behind it is what needs fixing instead.

2. **Prefer generalizing an existing mechanism over hardcoding one more case.** The ordinal/numbered-book
   fix and the chapter-scoped verse resolution both came from asking "what's the general shape of this
   problem, and do we already have the data/tool to solve the general case?" before writing anything
   book/verse-specific. Check `BookResolver.kt`, `ReferenceWatcher.kt`, `ContinuationEngine.kt`,
   `AgreementScorer.kt`, and `EngineTranslation`'s `byChapter`/`byBCV` lookups for reusable primitives
   before inventing new ones.

3. **Never fabricate a value the STT never said.** If a code path defaults a missing verse/chapter/book
   to a guess (`?: 1` or similar), treat that as a bug, not a convenience — the engine should stay silent
   and prime whatever context it can (sticky, chapter history) rather than show a wrong reference. This
   bit us twice in the same session (book+chapter-together, then chapter-only-via-sticky) via two
   different code paths — check *all* the places a similar default could hide, not just the first one
   you find.

4. **Don't guess tuning constants.** If a threshold/ratio genuinely needs real data to set correctly
   (e.g. `Config.chapterScopeMinAgreement`/`chapterScopeMinRatio`), say so explicitly, pick a reasonable
   documented starting value, and leave it for the next real session's data — don't iterate on gut-feel
   numbers with no logs to check them against.

5. **For anything beyond a one-line/obvious fix, use Plan Mode.** Read the relevant source fully, trace
   the actual data flow by hand (don't assume), and write the plan file with concrete file/function
   references before touching code. Ask clarifying questions when a design choice is genuinely the
   user's to make (e.g. scope of a new feature, whether to defer something pending more data) — don't
   assume.

6. **Every fix needs a test using the real trigger text**, added to `ReferenceWatcherTest.kt` or
   `ContinuationEngineTest.kt` (synthetic in-memory fixtures are fine and preferred for `ContinuationEngine`
   — no real Bible files needed). Run `bash gradlew test` (full suite, not just the new tests) before
   calling anything done.

7. **Update `TRAINING_PLAN.md`** — the Known Engine Gaps table, the Resolved/Built notes below it, and
   the Test Strategy / File Locations sections if a new test file or artifact type gets added. Keep it
   scannable, not a chronological diary.

8. **Don't commit unless asked.** Implement and verify with tests; leave the commit decision to me.

Start by reading `TRAINING_PLAN.md`, then run the triage report.
