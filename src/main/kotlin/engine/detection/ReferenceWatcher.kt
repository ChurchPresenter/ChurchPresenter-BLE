package engine.detection

import engine.Config

/**
 * Stateful explicit-reference detector. Replaced the older single-string ExplicitParser for the live
 * feed: it scans an utterance into typed atoms (book / chapter-keyword / verse-keyword / number /
 * range / colon) and interprets them into references, while carrying a **sticky** book+chapter
 * across utterances so a later bare "N стих" (verse-by-verse reading) still resolves.
 *
 * Evidence tiers (consumed by the caller to set confidence / gate auto-follow):
 *   1 FULL    — book + chapter + verse all present in this utterance
 *   2 STICKY  — verse resolved against the carried context, no book in this utterance
 *
 * A book (and/or chapter) named with no verse yet — either together in one utterance ("1 Corinthians
 * 11", then a tangent before reading) or split across utterances (a book named now, a bare chapter
 * named later via the sticky) — primes the sticky context (see [emit]) but never emits a [Ref]: there
 * is no evidence of *which* verse to show yet, and guessing verse 1 would put a wrong reference on
 * screen until the real one is read, possibly much later. This is what lets book, chapter, and verse
 * each resolve independently as they're spoken, rather than requiring one fully-formed phrase.
 *
 * Pure w.r.t. the Bible data — it only resolves book *numbers* via [BookResolver]; the caller looks
 * up verse text. [process] mutates only the sticky fields on the passed sticky holder.
 */
object ReferenceWatcher {

    /** Mutable sticky context — backed by fields on UtteranceState (kept out of this module). */
    interface Sticky {
        var watchBook: Int?
        var watchChapter: Int?
        var watchExpiresAt: Long
    }

    data class Ref(
        val bookNum: Int,
        val chapter: Int,
        val verseStart: Int?,
        val verseEnd: Int?,
        val tier: Int,          // 1 FULL, 2 STICKY
    )

    // Non-Russian chapter/verse keyword stems use plain prefix matching (low FP risk, no data to
    // refine). The Russian глав-/стих- stems are handled separately below with inflection-aware
    // endings so look-alikes (главное, стихотворение) don't fire as keywords.
    private val CHAP_KW = listOf(
        "kapitel", "kapit", "chapter", "chapitre", "capitul", "capitol", "розділ", "rozdzia",
    )
    private val VERSE_KW = listOf(
        "вірш", "versicul", "versiculo", "verset", "verse", "vers", "wiersz",
    )
    // Valid grammatical endings after the Russian stems "глав"/"стих". The whole suffix must match
    // (not just its first char) — that is what separates глава/главы/глав from главное/главный and
    // стих/стиха/стихи/стихом from стихотворение.
    private val CHAP_RU_ENDINGS = setOf("", "а", "ы", "е", "у", "ой", "ою", "ам", "ах", "ами")
    private val VERSE_RU_ENDINGS = setOf("", "а", "е", "у", "и", "ов", "ом", "ах", "ами")
    private val RANGE_WORDS = setOf("по", "до", "через", "bis", "to", "through", "hasta", "até", "ate")
    private val LIST_WORDS = setOf("и", "или", "потом", "затем", "then", "and")
    private val FROM_WORDS = setOf("с", "со", "from", "desde")

    // Gate thresholds for prose-vs-citation disambiguation (see classify). SHORT_ALIAS_MAX_LEN:
    // single-token exact aliases at or under this length ("job", "при") are common words in
    // prose. STEM_MAX_EXTENSION_UNCORROBORATED: a token this many chars longer than its matched
    // stem is treated as vocabulary unless corroborated (StickyAudit's risky band).
    private const val SHORT_ALIAS_MAX_LEN = 4
    private const val STEM_MAX_EXTENSION_UNCORROBORATED = 3
    // Bare cardinal "one" (count) — distinct from the ordinal (первый/first) and the digit "1". As
    // a chapter/verse number a real reference uses the ordinal or the digit, so "один стих" / "one
    // verse" means "one verse" (a quantity), never "verse one". Treated as filler so it can't bind
    // to a sticky context. Both RU and EN forms are listed because the translation track is fed
    // through the same watcher (DetectionEngine combines transcript + translation).
    private val BARE_ONE = setOf("один", "одна", "одно", "одни", "одну", "one")

    // ── Numbered-book (ordinal) disambiguation ──────────────────────────────────
    // Two related problems share one mechanism:
    //  - "Иоанна" is the Gospel of John (43); "1-е/Первое Послание Иоанна" is the *epistle* 1 John
    //    (62). The plain alias table resolves the spoken name to the gospel, and an intervening
    //    «Послание» breaks the "1 иоанна" greedy multi-token alias — so the epistle was being
    //    dropped to the gospel. A marker word alone ("Послание Иоанна", no ordinal) already
    //    conventionally means the 1st (1 John) — there's no unnumbered alternative to guard against
    //    once a marker is present.
    //  - Every OTHER numbered book family (Царств, Паралипоменон, Коринфянам, Фессалоникийцам,
    //    Тимофею) only has digit-adjacent aliases registered in BookResolver ("1 царств", "2
    //    коринфянам"…) — a spelled Russian ordinal ("Первая книга царств") doesn't resolve at all.
    //    Unlike John/Peter these have no unnumbered meaning, so a marker/filler word alone must NOT
    //    resolve (that stays the existing, accepted "bare ambiguous numbered book" gap, e.g. bare
    //    "Коринфянам" with no ordinal) — only an explicit ordinal (digit or word) may fire.
    // When the marker/filler word («послание»/«письмо»/«книга») or an explicit ordinal precedes one
    // of these forms, resolve to the numbered book, with the ordinal selecting which.
    private val EPISTLE_MARKER_STEMS = listOf("послани", "письм", "книг") // послание/письмо/книга…
    private val EPISTLE_CONNECTORS = setOf("к", "ко")
    // Inflected spoken forms of John / Peter that, in an epistle context, mean the epistle.
    private val JOHN_FORMS = setOf("иоанна", "иоанн", "иоанну", "иоанне")
    private val PETER_FORMS = setOf("петра", "петру", "петре", "петр", "пётр")
    // base = canonical id of the 1st book in the family, count = how many numbered variants exist.
    // markerAloneDefaultsToFirst: true only for John/Peter (see comment above); the bare stems below
    // are taken from the digit-stripped forms already registered in BookResolver.kt, so they're
    // forms already vetted from real transcripts — further inflections can be added the same way as
    // future training data surfaces them.
    private data class NumberedBookSpec(val base: Int, val count: Int, val markerAloneDefaultsToFirst: Boolean)
    private val NUMBERED_BOOK_FORMS: Map<String, NumberedBookSpec> = buildMap {
        for (f in JOHN_FORMS) put(f, NumberedBookSpec(62, 3, markerAloneDefaultsToFirst = true))
        for (f in PETER_FORMS) put(f, NumberedBookSpec(60, 2, markerAloneDefaultsToFirst = true))
        put("царств", NumberedBookSpec(9, 4, markerAloneDefaultsToFirst = false))
        put("царство", NumberedBookSpec(9, 4, markerAloneDefaultsToFirst = false))
        put("паралипоменон", NumberedBookSpec(13, 2, markerAloneDefaultsToFirst = false))
        put("коринфянам", NumberedBookSpec(46, 2, markerAloneDefaultsToFirst = false))
        put("фессалоникийцам", NumberedBookSpec(52, 2, markerAloneDefaultsToFirst = false))
        put("солунян", NumberedBookSpec(52, 2, markerAloneDefaultsToFirst = false))
        put("тимофею", NumberedBookSpec(54, 2, markerAloneDefaultsToFirst = false))
    }

    // ── Ambiguous common-word RU book aliases ───────────────────────────────────
    // Bare forms that are also ordinary vocabulary or narration naming a person, not citing a book:
    // dative/locative "Иоанну"/"Иоанне" narrate the apostle by name (a real citation is genitive/
    // nominative "Иоанна"/"Иоанн" — already unconditional aliases, untouched here); "бытие"/"быт" is
    // ordinary vocabulary ("being/existence") at least as often as it names Genesis. These must NOT
    // resolve to Atom.Book on the bare word alone; they need a chapter-number digit within 2 tokens
    // either side, or an explicit book/epistle/gospel marker noun in the preceding 2 tokens (bare
    // prepositions like «в»/«от» deliberately do NOT count — too common in ordinary prose; the real
    // "бытие" false positive was itself "...в то бытие"). Add further forms here the same way as
    // future training data surfaces them (mirrors NUMBERED_BOOK_FORMS above).
    // internal (not private): src/test's fuzz test iterates this table directly so any future
    // addition is automatically covered without touching the test.
    internal val AMBIGUOUS_BOOK_FORMS: Map<String, Int> = mapOf(
        "иоанну" to 43, "иоанне" to 43,
        "бытие" to 1, "быт" to 1,
    )

    private sealed interface Atom {
        data class Book(val num: Int) : Atom
        object ChapKw : Atom
        object VerseKw : Atom
        data class Num(val value: Int) : Atom
        object Range : Atom
        object Colon : Atom
        object ListSep : Atom
        object From : Atom
        object Filler : Atom
    }

    /**
     * Parse [text]; emit references found and update the carried sticky context.
     *
     * [isMusic] flags a segment the STT engine labelled as music. Sung lyrics quote scripture as
     * lyrics, not as references being looked up — so when [isMusic] (and [Config.suppressDuringMusic])
     * we skip detection entirely and leave the sticky context untouched, so a song can't seed or
     * hijack it. Every false positive in the real corpus came from sung/recited non-reference text.
     */
    fun process(
        text: String,
        sticky: Sticky,
        now: Long = System.currentTimeMillis(),
        isMusic: Boolean = false,
    ): List<Ref> {
        if (isMusic && Config.suppressDuringMusic) return emptyList()
        if (sticky.watchExpiresAt != 0L && now > sticky.watchExpiresAt) {
            sticky.watchBook = null
            sticky.watchChapter = null
        }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val atoms = classify(tokens)
        return interpret(atoms, sticky, now)
    }

    /**
     * Dual-track entry point for the live pipeline: [transcript] (original language) and
     * [translation] (its live machine translation) describe the same utterance. Normally they are
     * concatenated so a citation spoken in either language is caught — but when the translation
     * *mistranslates* a book name into a different book than the transcript names (seen in
     * production: Russian "Первая книга царств" — 1 Samuel — machine-translated as "1 Kings"), blind
     * concatenation lets the translation's book win, since [interpret] treats the last book atom
     * seen as authoritative and translation tokens are scanned after transcript tokens. That
     * silently corrupts the sticky book for every later utterance that doesn't restate it.
     *
     * When the two tracks name different books, the translation is dropped entirely for this call
     * (not just its book atom — an MT that garbled the book name can't be trusted for numbers
     * either) and only the transcript is processed. When they agree, or only one names a book, the
     * tracks are concatenated as before — this is what lets a translation-only citation (transcript
     * names no book this utterance) still get picked up.
     */
    fun process(
        transcript: String,
        translation: String,
        sticky: Sticky,
        now: Long = System.currentTimeMillis(),
        isMusic: Boolean = false,
    ): List<Ref> {
        val transcriptBook = lastBookMentioned(transcript)
        val translationBook = lastBookMentioned(translation)
        val disagree = transcriptBook != null && translationBook != null && transcriptBook != translationBook
        val combined = if (disagree) transcript.trim() else "$transcript $translation".trim()
        return process(combined, sticky, now, isMusic)
    }

    /** The last book [text] names, or null if it names none. Read-only — does not touch sticky. */
    private fun lastBookMentioned(text: String): Int? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        return classify(tokens).filterIsInstance<Atom.Book>().lastOrNull()?.num
    }

    // ── tokenization ──────────────────────────────────────────────────────────

    private fun tokenize(text: String): List<String> {
        var s = text.lowercase().replace('–', '-').replace('—', '-')
        // Gated STT spelling normalization: fold Cyrillic э→е so "эфесянам"→"ефесянам" resolves.
        if (Config.normalizeStt) s = s.replace('э', 'е')
        // strip digit-ordinal suffixes ("3-я" -> "3", "21-й" -> "21", "19-го" -> "19", "2nd" -> "2").
        // Use a Unicode-safe lookahead, not \b — Java's \b is ASCII-only and won't fire after Cyrillic.
        s = Regex("(\\d{1,3})-(?:я|й|е|го|му|м|ой|ом|ая|ое|ый|ий|ст|нд|рд|th|nd|st|rd)(?![\\p{L}])")
            .replace(s) { it.groupValues[1] }
        // make ":" and "-" (between alphanumerics) standalone separator tokens. A dot BETWEEN
        // digits ("Исайя 26.3", "Псалом 118.105") is the same citation notation as a colon —
        // normalize it to one BEFORE the general punctuation fold below turns it into a space
        // (which split chapter and verse into two bare numbers the prose guard then discarded).
        s = Regex("(?<=\\d)\\.(?=\\d)").replace(s, " : ")
        s = s.replace(":", " : ")
        s = Regex("(?<=[\\p{L}0-9])-(?=[\\p{L}0-9])").replace(s, " - ")
        // drop other punctuation (commas, dots, parens, quotes …) to whitespace
        s = s.replace(Regex("[^\\p{L}0-9:\\- ]"), " ")
        return s.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    // ── classification (greedy multi-token book lookup) ─────────────────────────

    private fun classify(tokens: List<String>): List<Atom> {
        val atoms = ArrayList<Atom>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            // Numbered-book disambiguation runs first: "1 Послание Иоанна" → 1 John (62), not the
            // Gospel (43); "Первая книга царств" → 1 Samuel (9), not left unresolved. The look-back
            // tokens (ordinal / «послание»/«книга» / «к») were already classified as Num/Filler, so
            // drop those atoms and emit the resolved book in their place.
            val numberedBook = resolveNumberedBookAt(tokens, i)
            if (numberedBook != null) {
                val (bookNum, back) = numberedBook
                repeat(back) { if (atoms.isNotEmpty()) atoms.removeAt(atoms.lastIndex) }
                atoms.add(Atom.Book(bookNum))
                i++
                continue
            }

            // Greedy book match: try 3-, then 2-, then 1-token joins against the alias table.
            var matched = false
            val maxJoin = minOf(3, tokens.size - i)
            for (len in maxJoin downTo 1) {
                val phrase = tokens.subList(i, i + len).joinToString(" ")
                val bookNum = BookResolver.ALIASES[phrase]
                // Skip ultra-short single-token aliases (≤2 chars: "so"→Zeph, "re"/"ap"→Rev, "ge",
                // "ex"…). They are typed-input abbreviations and fire constantly on spoken/translated
                // prose (the translation track flows through here too), hijacking the sticky book.
                // Multi-word aliases always carry a space (length ≥ 3) and are kept.
                if (bookNum != null && (len > 1 || phrase.length >= 3)) {
                    // Ambiguous common-word aliases (bare "иоанну"/"бытие"…) AND short real-word
                    // aliases ("job", "song", "при", "откр" — ≤ SHORT_ALIAS_MAX_LEN chars) need
                    // corroborating context — a chapter/verse digit nearby or an explicit citation
                    // marker — or they're just ordinary vocabulary/narration, not a book being
                    // named. A genuine spoken citation via a short alias essentially always has
                    // the number within reach ("Job chapter 3"), so recall survives the gate.
                    if (len == 1 &&
                        (AMBIGUOUS_BOOK_FORMS[phrase] != null || phrase.length <= SHORT_ALIAS_MAX_LEN) &&
                        !hasAmbiguousBookCorroboration(tokens, i)
                    ) {
                        break
                    }
                    atoms.add(Atom.Book(bookNum))
                    i += len
                    matched = true
                    break
                }
            }
            if (matched) continue

            val tok = tokens[i]
            // Inflection-tolerant single-token book match (Матфея, Даниила, Римлянам…), but not for
            // tokens that are numbers/keywords/separators.
            if (tok != ":" && tok != "-" && !isChapKw(tok) && !isVerseKw(tok) &&
                tok !in RANGE_WORDS && tok !in LIST_WORDS && NumberWords.parseToken(tok) == null
            ) {
                BookResolver.resolveStem(tok)?.let { match ->
                    // Over-extension gate: a token much longer than the alias stem it matched is
                    // usually ordinary vocabulary sharing a prefix ("открылся"→"откр",
                    // "повторить"→a Deuteronomy stem), not an inflected book name — real
                    // grammatical endings extend a stem by 1-2 chars (Матфея, Даниила). Beyond
                    // that, require the same corroboration ambiguous aliases need. The threshold
                    // matches StickyAudit's empirically-validated risky band (extension >= 3).
                    val overExtended = tok.length - match.stem.length >= STEM_MAX_EXTENSION_UNCORROBORATED
                    val needsCorroboration = overExtended || AMBIGUOUS_BOOK_FORMS[tok] != null
                    val gated = needsCorroboration && !hasAmbiguousBookCorroboration(tokens, i)
                    if (!gated) { atoms.add(Atom.Book(match.bookNum)); i++; matched = true }
                }
            }
            if (matched) continue

            atoms.add(
                when {
                    tok == ":" -> Atom.Colon
                    tok == "-" -> Atom.Range
                    isChapKw(tok) -> Atom.ChapKw
                    isVerseKw(tok) -> Atom.VerseKw
                    tok in RANGE_WORDS -> Atom.Range
                    tok in LIST_WORDS -> Atom.ListSep
                    tok in FROM_WORDS -> Atom.From
                    tok in BARE_ONE -> Atom.Filler
                    else -> NumberWords.parseToken(tok)?.let { Atom.Num(it) } ?: Atom.Filler
                }
            )
            i++
        }
        return atoms
    }

    private fun isChapKw(t: String): Boolean =
        (t.startsWith("глав") && t.substring(4) in CHAP_RU_ENDINGS) || CHAP_KW.any { t.startsWith(it) }
    private fun isVerseKw(t: String): Boolean =
        (t.startsWith("стих") && t.substring(4) in VERSE_RU_ENDINGS) || VERSE_KW.any { t.startsWith(it) }

    private fun isEpistleMarker(t: String): Boolean = EPISTLE_MARKER_STEMS.any { t.startsWith(it) }

    /**
     * If [tokens]`[i]` is a numbered-book name (John/Peter, or a Царств/Паралипоменон/Коринфянам/
     * Фессалоникийцам/Тимофею family member) preceded by a marker word («послание»/«письмо»/«книга»)
     * and/or an explicit ordinal (digit or word), returns the resolved canonical book number plus how
     * many preceding tokens the pattern consumed; else null.
     *
     *   "1 Послание Иоанна"       → (62, 2)  // 1 John, consuming «послание» + «1»
     *   "Послание к Петру"        → (60, 2)  // 1 Peter, consuming «к» + «послание»
     *   "Первое Иоанна"           → (62, 1)  // 1 John, consuming the word ordinal
     *   "Первая книга царств"     → (9, 2)   // 1 Samuel, consuming «книга» + the word ordinal
     *   "Третья царств"           → (11, 1)  // 1 Kings, consuming the word ordinal alone
     *
     * A bare "Иоанна"/"Петра" with no marker and no ordinal returns null (left to the alias table /
     * gospel reading). A bare "Коринфянам"/"Царств" with a marker but no ordinal ALSO returns null —
     * unlike John/Peter there's no unnumbered meaning to fall back to, but there's also no convention
     * that a bare mention means the 1st, so guessing would be wrong more often than right; this stays
     * the accepted "bare ambiguous numbered book" gap, not a case this function resolves.
     */
    private fun resolveNumberedBookAt(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val spec = NUMBERED_BOOK_FORMS[tokens[i]] ?: return null
        var j = i - 1
        var back = 0
        // optional «к»/«ко» connector directly before the book ("послание к иоанну")
        if (j >= 0 && tokens[j] in EPISTLE_CONNECTORS) { j--; back++ }
        // optional marker/filler word («послание»/«письмо»/«книга»)
        var marker = false
        if (j >= 0 && isEpistleMarker(tokens[j])) { marker = true; j--; back++ }
        // optional ordinal (digit or word) selecting which numbered variant
        var ord: Int? = null
        if (j >= 0) {
            val n = NumberWords.parseToken(tokens[j])
            if (n != null && n in 1..spec.count) { ord = n; back++ }
        }
        // Require an explicit ordinal, unless the family conventionally defaults a bare marker to
        // the 1st (John/Peter only).
        if (ord == null && !(marker && spec.markerAloneDefaultsToFirst)) return null
        return (spec.base + (ord ?: 1) - 1) to back
    }

    /**
     * True when `tokens[i]` (one of [AMBIGUOUS_BOOK_FORMS]) has corroborating context: a
     * chapter/verse digit within 2 tokens either side, or an explicit book/epistle/gospel marker
     * noun within the preceding 2 tokens.
     */
    private fun hasAmbiguousBookCorroboration(tokens: List<String>, i: Int): Boolean {
        for (d in 1..2) {
            if (NumberWords.parseToken(tokens.getOrNull(i + d) ?: "") != null) return true
            if (NumberWords.parseToken(tokens.getOrNull(i - d) ?: "") != null) return true
        }
        for (d in 1..2) {
            val back = tokens.getOrNull(i - d) ?: continue
            if (isEpistleMarker(back) || back.startsWith("евангели")) return true
        }
        return false
    }

    // ── interpretation ──────────────────────────────────────────────────────────

    private fun interpret(atoms: List<Atom>, sticky: Sticky, now: Long): List<Ref> {
        val out = ArrayList<Ref>()

        var curBook: Int? = null
        var chapter: Int? = null
        var verseStart: Int? = null
        var verseEnd: Int? = null
        var keywordSeen = false
        var colonSeen = false
        var rangeArmed = false
        // numbers buffered since the last keyword/colon, each tagged whether a range preceded it
        val recent = ArrayList<Pair<Int, Boolean>>()

        fun assignChapterFromRecent() {
            if (chapter == null && recent.isNotEmpty()) chapter = recent.first().first
            recent.clear()
        }
        fun assignVersesFromRecent() {
            for ((v, ranged) in recent) {
                if (verseStart == null) verseStart = v
                else if (ranged && verseEnd == null) verseEnd = v
            }
            recent.clear()
        }
        fun flush() {
            // leftover buffered numbers with no trailing keyword
            if (recent.isNotEmpty()) {
                if (chapter == null) {
                    chapter = recent.first().first
                    if (recent.size >= 2) {
                        verseStart = recent[1].first
                        if (recent.size >= 3 && recent[2].second) verseEnd = recent[2].first
                    }
                } else {
                    assignVersesFromRecent()
                }
                recent.clear()
            }
            emit(curBook, chapter, verseStart, verseEnd, keywordSeen, sticky, now, out)
            chapter = null; verseStart = null; verseEnd = null; keywordSeen = false; colonSeen = false; rangeArmed = false
        }

        for (a in atoms) {
            when (a) {
                is Atom.Book -> {
                    // Normally a book starts a fresh reference (flush whatever preceded it). When
                    // inferBookAtEnd is on and no book has been named yet in this segment but
                    // chapter/verse numbers already have, the book was spoken *after* its numbers
                    // ("14 стих 3 главы … Матфея") — attach it to them instead of flushing.
                    val attachToPending = Config.inferBookAtEnd && curBook == null &&
                        (chapter != null || verseStart != null || recent.isNotEmpty())
                    if (attachToPending) curBook = a.num
                    else { flush(); curBook = a.num }
                }
                is Atom.ChapKw -> { assignChapterFromRecent(); keywordSeen = true }
                is Atom.VerseKw -> { assignVersesFromRecent(); keywordSeen = true }
                is Atom.Colon -> { assignChapterFromRecent(); colonSeen = true }
                is Atom.Range -> { rangeArmed = true }
                is Atom.From -> { /* "с N по M": start of a verse range — no-op, recent handles it */ }
                is Atom.ListSep -> { /* keep recent; lists don't form ranges */ }
                is Atom.Num -> { recent.add(a.value to rangeArmed); rangeArmed = false }
                is Atom.Filler -> {
                    // A colon already bound these numbers to the reference ("Исайя 26:3 написано
                    // «…»", "Isaiah 26:3 says …") — trailing prose must not wipe the buffered
                    // verse. Without a colon, numbers followed by prose are likely counts
                    // ("Марк 5 человек") — drop as before. Deliberately colon-only, NOT
                    // keywordSeen: "Матфея 3 глава … 5 причин" must not turn 5 into a verse.
                    if (colonSeen && recent.isNotEmpty()) assignVersesFromRecent() else recent.clear()
                    rangeArmed = false
                }
            }
        }
        flush()
        return out
    }

    private fun emit(
        curBook: Int?,
        chapter: Int?,
        verseStart: Int?,
        verseEnd: Int?,
        keywordSeen: Boolean,
        sticky: Sticky,
        now: Long,
        out: MutableList<Ref>,
    ) {
        if (curBook != null) {
            // A book was named — make it sticky even if no chapter followed yet, so a chapter/verse
            // in a *later* utterance ("…к римлянам." → "Десятая глава…") still attaches to it. A
            // genuinely NEW book (different from what was already sticky) always resets the carried
            // chapter so a stale one can't bind to it — but the SAME book merely mentioned again
            // within this call (trailing after its own chapter number in RU's "N глава [Книги]" word
            // order, or re-named on the other bilingual track after the combined string already
            // resolved a chapter) must not wipe a chapter this call already established: an absent
            // chapter on THIS mention just means "nothing new was said," not "the chapter is unknown
            // again." Snapshot both values before mutating — sticky.watchChapter is read and written
            // in this same branch.
            val sameBook = curBook == sticky.watchBook
            val resolvedChapter = chapter ?: sticky.watchChapter.takeIf { sameBook }
            sticky.watchBook = curBook
            sticky.watchChapter = resolvedChapter
            sticky.watchExpiresAt = now + Config.stickyTtlMs
            val ch = resolvedChapter ?: return
            // No verse named yet (book + chapter only) — the sticky context above is primed for a
            // later bare "N стих", but there is no evidence of which verse to show, so emit nothing
            // rather than guessing verse 1.
            if (verseStart == null) return
            out.add(Ref(curBook, ch, verseStart, normEnd(verseStart, verseEnd), 1))
            return
        }
        // No book in this utterance → sticky resolution, but only when a keyword anchored it
        // (a bare number with no глава/стих is too risky to bind to stale context).
        if (!keywordSeen) return
        val book = sticky.watchBook ?: return
        val ch = chapter ?: sticky.watchChapter ?: return
        if (chapter == null && verseStart == null) return // nothing new
        sticky.watchChapter = ch
        sticky.watchExpiresAt = now + Config.stickyTtlMs
        // A new bare chapter arrived via the sticky book, but no verse yet — same situation as the
        // book+chapter branch above: prime silently and wait for a real verse rather than guessing 1.
        if (verseStart == null) return
        out.add(Ref(book, ch, verseStart, normEnd(verseStart, verseEnd), 2))
    }

    private fun normEnd(start: Int?, end: Int?): Int? =
        if (start != null && end != null && end > start) end else null
}
