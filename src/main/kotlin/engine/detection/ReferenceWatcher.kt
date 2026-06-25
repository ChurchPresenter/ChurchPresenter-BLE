package engine.detection

import engine.Config

/**
 * Stateful explicit-reference detector. Replaces the single-string [ExplicitParser] for the live
 * feed: it scans an utterance into typed atoms (book / chapter-keyword / verse-keyword / number /
 * range / colon) and interprets them into references, while carrying a **sticky** book+chapter
 * across utterances so a later bare "N стих" (verse-by-verse reading) still resolves.
 *
 * Evidence tiers (consumed by the caller to set confidence / gate auto-follow):
 *   1 FULL    — book + chapter + verse all present in this utterance
 *   2 PARTIAL — book + chapter only
 *   3 STICKY  — verse (or chapter) resolved against the carried context, no book in this utterance
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
        val tier: Int,          // 1 FULL, 2 PARTIAL, 3 STICKY
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
    // Bare cardinal "one" (count) — distinct from the ordinal (первый/first) and the digit "1". As
    // a chapter/verse number a real reference uses the ordinal or the digit, so "один стих" / "one
    // verse" means "one verse" (a quantity), never "verse one". Treated as filler so it can't bind
    // to a sticky context. Both RU and EN forms are listed because the translation track is fed
    // through the same watcher (DetectionEngine combines transcript + translation).
    private val BARE_ONE = setOf("один", "одна", "одно", "одни", "одну", "one")

    // ── Epistle (ordinal) disambiguation ────────────────────────────────────────
    // "Иоанна" is the Gospel of John (43); "1-е/Первое Послание Иоанна" is the *epistle* 1 John (62).
    // The plain alias table resolves the spoken name to the gospel, and an intervening «Послание»
    // breaks the "1 иоанна" greedy multi-token alias — so the epistle was being dropped to the gospel.
    // When the epistle word «послание»/«письмо» (or an explicit ordinal) precedes one of these forms,
    // resolve to the epistle instead, with the ordinal (1/2/3) selecting which.
    private val EPISTLE_MARKER_STEMS = listOf("послани", "письм") // послание/послании/письмо/письме…
    private val EPISTLE_CONNECTORS = setOf("к", "ко")
    // Inflected spoken forms of John / Peter that, in an epistle context, mean the epistle.
    private val JOHN_FORMS = setOf("иоанна", "иоанн", "иоанну", "иоанне")
    private val PETER_FORMS = setOf("петра", "петру", "петре", "петр", "пётр")
    // base = canonical id of the 1st epistle, count = number of epistles (John 62/63/64, Peter 60/61).
    private data class EpistleSpec(val base: Int, val count: Int)

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

    // ── tokenization ──────────────────────────────────────────────────────────

    private fun tokenize(text: String): List<String> {
        var s = text.lowercase().replace('–', '-').replace('—', '-')
        // Gated STT spelling normalization: fold Cyrillic э→е so "эфесянам"→"ефесянам" resolves.
        if (Config.normalizeStt) s = s.replace('э', 'е')
        // strip digit-ordinal suffixes ("3-я" -> "3", "21-й" -> "21", "19-го" -> "19", "2nd" -> "2").
        // Use a Unicode-safe lookahead, not \b — Java's \b is ASCII-only and won't fire after Cyrillic.
        s = Regex("(\\d{1,3})-(?:я|й|е|го|му|м|ой|ом|ая|ое|ый|ий|ст|нд|рд|th|nd|st|rd)(?![\\p{L}])")
            .replace(s) { it.groupValues[1] }
        // make ":" and "-" (between alphanumerics) standalone separator tokens
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
            // Epistle disambiguation runs first: "1 Послание Иоанна" → 1 John (62), not the Gospel
            // (43). The look-back tokens (ordinal / «послание» / «к») were already classified as
            // Num/Filler, so drop those atoms and emit the resolved epistle book in their place.
            val epistle = resolveEpistleAt(tokens, i)
            if (epistle != null) {
                val (bookNum, back) = epistle
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
                BookResolver.resolveStem(tok)?.let {
                    atoms.add(Atom.Book(it)); i++; matched = true
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
     * If [tokens]`[i]` is an epistle-ambiguous book name (John/Peter) marked as an epistle by a
     * preceding «послание»/«письмо» word and/or an explicit ordinal (digit or word), returns the
     * epistle's canonical book number plus how many preceding tokens the pattern consumed; else null.
     *
     *   "1 Послание Иоанна"  → (62, 2)   // 1 John, consuming «послание» + «1»
     *   "Послание к Петру"   → (60, 2)   // 1 Peter, consuming «к» + «послание»
     *   "Первое Иоанна"      → (62, 1)   // 1 John, consuming the word ordinal
     *
     * A bare "Иоанна"/"Петра" with no marker and no ordinal returns null (left to the alias table /
     * gospel reading), so non-epistle prose can't be hijacked.
     */
    private fun resolveEpistleAt(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val spec = when (tokens[i]) {
            in JOHN_FORMS -> EpistleSpec(62, 3)
            in PETER_FORMS -> EpistleSpec(60, 2)
            else -> return null
        }
        var j = i - 1
        var back = 0
        // optional «к»/«ко» connector directly before the book ("послание к иоанну")
        if (j >= 0 && tokens[j] in EPISTLE_CONNECTORS) { j--; back++ }
        // optional epistle marker word
        var marker = false
        if (j >= 0 && isEpistleMarker(tokens[j])) { marker = true; j--; back++ }
        // optional ordinal (digit or word) selecting which epistle
        var ord: Int? = null
        if (j >= 0) {
            val n = NumberWords.parseToken(tokens[j])
            if (n != null && n in 1..spec.count) { ord = n; back++ }
        }
        // Require positive evidence of an epistle: the marker word or an explicit ordinal.
        if (!marker && ord == null) return null
        return (spec.base + (ord ?: 1) - 1) to back
    }

    // ── interpretation ──────────────────────────────────────────────────────────

    private fun interpret(atoms: List<Atom>, sticky: Sticky, now: Long): List<Ref> {
        val out = ArrayList<Ref>()

        var curBook: Int? = null
        var chapter: Int? = null
        var verseStart: Int? = null
        var verseEnd: Int? = null
        var keywordSeen = false
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
            chapter = null; verseStart = null; verseEnd = null; keywordSeen = false; rangeArmed = false
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
                is Atom.Colon -> { assignChapterFromRecent() }
                is Atom.Range -> { rangeArmed = true }
                is Atom.From -> { /* "с N по M": start of a verse range — no-op, recent handles it */ }
                is Atom.ListSep -> { /* keep recent; lists don't form ranges */ }
                is Atom.Num -> { recent.add(a.value to rangeArmed); rangeArmed = false }
                is Atom.Filler -> { recent.clear(); rangeArmed = false }
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
            // in a *later* utterance ("…к римлянам." → "Десятая глава…") still attaches to it. A new
            // book always resets the carried chapter so a stale one can't bind.
            sticky.watchBook = curBook
            sticky.watchChapter = chapter
            sticky.watchExpiresAt = now + Config.stickyTtlMs
            val ch = chapter ?: return
            val tier = if (verseStart != null) 1 else 2
            out.add(Ref(curBook, ch, verseStart, normEnd(verseStart, verseEnd), tier))
            return
        }
        // No book in this utterance → sticky resolution, but only when a keyword anchored it
        // (a bare number with no глава/стих is too risky to bind to stale context).
        if (!keywordSeen) return
        val book = sticky.watchBook ?: return
        val ch = chapter ?: sticky.watchChapter ?: return
        if (verseStart == null && chapter == null) return // nothing new
        out.add(Ref(book, ch, verseStart, normEnd(verseStart, verseEnd), 3))
        sticky.watchChapter = ch
        sticky.watchExpiresAt = now + Config.stickyTtlMs
    }

    private fun normEnd(start: Int?, end: Int?): Int? =
        if (start != null && end != null && end > start) end else null
}
