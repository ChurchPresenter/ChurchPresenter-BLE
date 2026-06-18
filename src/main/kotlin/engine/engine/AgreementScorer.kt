package engine.engine

object AgreementScorer {

    fun score(candidateText: String, transcript: String, translation: String): Double {
        val s1 = wordOverlap(candidateText, transcript)
        val s2 = wordOverlap(candidateText, translation)
        return s1 + s2
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
