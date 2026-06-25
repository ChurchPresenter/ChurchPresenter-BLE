package engine.engine

object AgreementScorer {

    fun score(candidateText: String, transcript: String, translation: String): Double {
        val s1 = wordOverlap(candidateText, transcript)
        val s2 = wordOverlap(candidateText, translation)
        return s1 + s2
    }

    /**
     * Fraction (0..1) of [verseText]'s distinct words that also appear in [trackText] — i.e. "how
     * much of this verse is being read in this track". Used to attribute a detection to the
     * transcription and/or translation track for the corroboration markers.
     */
    fun coverage(verseText: String, trackText: String): Double {
        val verseWords = tokenize(verseText)
        if (verseWords.isEmpty()) return 0.0
        val trackWords = tokenize(trackText)
        return verseWords.intersect(trackWords).size.toDouble() / verseWords.size
    }

    private fun wordOverlap(doc: String, query: String): Double {
        if (query.isBlank()) return 0.0
        val docWords = tokenize(doc)
        val queryWords = tokenize(query)
        if (queryWords.isEmpty()) return 0.0
        val common = docWords.intersect(queryWords).size
        return common.toDouble() / queryWords.size
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z\\u0400-\\u04FF]+"))
            .filter { it.length >= 3 }
            .toSet()
}
