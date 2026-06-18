package engine.detection

object ExplicitParser {

    data class ParsedReference(
        val bookNum: Int,
        val chapter: Int,
        val verseStart: Int?,
        val verseEnd: Int?,
    )

    private data class ChapterVerse(
        val chapter: Int,
        val verseStart: Int?,
        val verseEnd: Int?,
    )

    fun parse(text: String): ParsedReference? {
        val norm = normalize(text)
        for ((alias, bookNum) in BookResolver.ALIASES_BY_LENGTH) {
            val matchEnd = findAlias(norm, alias) ?: continue
            val suffix = norm.substring(matchEnd)
            val cv = parseSuffix(suffix) ?: continue
            if (cv.verseStart != null && cv.verseStart <= 0) continue
            if (cv.verseEnd != null && cv.verseEnd <= cv.verseStart!!) continue
            return ParsedReference(bookNum, cv.chapter, cv.verseStart, cv.verseEnd)
        }
        return null
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

    // Returns the end position of the matched alias in text, or null if no word-boundary match.
    private fun findAlias(text: String, alias: String): Int? {
        var start = 0
        while (true) {
            val idx = text.indexOf(alias, start)
            if (idx == -1) return null
            val endIdx = idx + alias.length
            val charBefore = if (idx == 0) ' ' else text[idx - 1]
            val charAfter = if (endIdx >= text.length) ' ' else text[endIdx]
            // Word boundary before: not a letter or digit
            val beforeOk = !charBefore.isLetterOrDigit()
            // Word boundary after: space, digit (chapter follows), colon, or end of string
            val afterOk = charAfter == ' ' || charAfter.isDigit() || charAfter == ':' || endIdx >= text.length
            if (beforeOk && afterOk) return endIdx
            start = idx + 1
        }
    }

    private fun parseSuffix(suffix: String): ChapterVerse? {
        var s = suffix.trimStart()

        // Optional "chapter" / "глав*" keyword
        s = s.replace(Regex("^(?:chapter|глав[ауые]?)\\s+", RegexOption.IGNORE_CASE), "").trimStart()

        // Chapter number (required)
        val chMatch = Regex("^(\\d+)").find(s) ?: return null
        val chapter = chMatch.value.toInt()
        if (chapter <= 0) return null
        s = s.removePrefix(chMatch.value).trimStart()

        if (s.isEmpty()) return ChapterVerse(chapter, null, null)

        // Skip separator: ":", ".", ","
        s = s.replace(Regex("^[:\\.,]\\s*"), "")

        // Optional "verse(s)" / "стих*" / "v" keyword
        s = s.replace(Regex("^(?:verses?|стих[аеиов]*|v)\\s+", RegexOption.IGNORE_CASE), "")
        s = s.trimStart()

        if (s.isEmpty()) return ChapterVerse(chapter, null, null)

        // Verse start (optional)
        val vMatch = Regex("^(\\d+)").find(s) ?: return ChapterVerse(chapter, null, null)
        val verseStart = vMatch.value.toInt()
        s = s.removePrefix(vMatch.value).trimStart()

        if (s.isEmpty()) return ChapterVerse(chapter, verseStart, null)

        // Optional range: "-N", "to N", "through N", "по N"
        val rangeMatch = Regex(
            "^(?:[-–—]|\\s*(?:to|through|по)\\s+)(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(s)
        if (rangeMatch != null) {
            val verseEnd = rangeMatch.groupValues[1].toIntOrNull()
            if (verseEnd != null && verseEnd > verseStart) {
                return ChapterVerse(chapter, verseStart, verseEnd)
            }
        }

        return ChapterVerse(chapter, verseStart, null)
    }
}
