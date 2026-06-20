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

    private val CHAP_KW = listOf(
        "глав", "kapitel", "kapit", "chapter", "chapitre", "capitul", "capitol", "розділ", "rozdzia",
    )
    private val VERSE_KW = listOf(
        "стих", "вірш", "versicul", "versiculo", "verset", "verse", "vers", "wiersz",
    )
    private val RANGE_WORDS = setOf("по", "до", "через", "bis", "to", "through", "hasta", "até", "ate")
    private val LIST_WORDS = setOf("и", "или", "потом", "затем", "then", "and")
    private val FROM_WORDS = setOf("с", "со", "from", "desde")

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

    /** Parse [text]; emit references found and update the carried sticky context. */
    fun process(text: String, sticky: Sticky, now: Long = System.currentTimeMillis()): List<Ref> {
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
            // Greedy book match: try 3-, then 2-, then 1-token joins against the alias table.
            var matched = false
            val maxJoin = minOf(3, tokens.size - i)
            for (len in maxJoin downTo 1) {
                val phrase = tokens.subList(i, i + len).joinToString(" ")
                val bookNum = BookResolver.ALIASES[phrase]
                if (bookNum != null) {
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
                    else -> NumberWords.parseToken(tok)?.let { Atom.Num(it) } ?: Atom.Filler
                }
            )
            i++
        }
        return atoms
    }

    private fun isChapKw(t: String) = CHAP_KW.any { t.startsWith(it) }
    private fun isVerseKw(t: String) = VERSE_KW.any { t.startsWith(it) }

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
                is Atom.Book -> { flush(); curBook = a.num }
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
