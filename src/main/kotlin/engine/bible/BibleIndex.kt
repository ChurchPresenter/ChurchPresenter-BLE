package engine.bible

import engine.Config
import kotlin.math.ln

class BibleIndex(private val translations: List<EngineTranslation>) {

    data class SearchResult(
        val translationId: String,
        val verse: EngineVerse,
        val score: Double,
    )

    private data class DocEntry(val docId: Int, val tf: Float)

    private data class Document(
        val translationId: String,
        val verse: EngineVerse,
        val termCount: Int,
    )

    // Sized from the actual load (typically 1-2 bibles ≈ 31k verses each) instead of a fixed
    // 800k/500k pre-allocation that assumed every bible on disk would be indexed.
    private val documents = ArrayList<Document>(translations.sumOf { it.byBCV.size })
    private val invertedIndex = HashMap<String, MutableList<DocEntry>>(translations.sumOf { it.byBCV.size } / 2 + 16)
    private var avgDocLen = 0.0

    init {
        buildIndex()
    }

    private fun buildIndex() {
        var totalLen = 0L
        for (t in translations) {
            for (v in t.byBCV.values) {
                if (v.isHeader || v.text.isBlank()) continue
                val docId = documents.size
                val tokens = tokenize(v.text)
                if (tokens.isEmpty()) continue
                totalLen += tokens.size
                documents.add(Document(t.id, v, tokens.size))
                val tfMap = HashMap<String, Int>(tokens.size)
                for (tok in tokens) tfMap[tok] = (tfMap[tok] ?: 0) + 1
                for ((term, count) in tfMap) {
                    invertedIndex.getOrPut(term) { mutableListOf() }
                        .add(DocEntry(docId, count.toFloat()))
                }
            }
        }
        avgDocLen = if (documents.isEmpty()) 1.0 else totalLen.toDouble() / documents.size
    }

    fun search(query: String, topK: Int = Config.reverseTopK): List<SearchResult> {
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty()) return emptyList()

        val scores = HashMap<Int, Double>(1024)
        val n = documents.size.toDouble()
        val k1 = Config.bm25K1
        val b = Config.bm25B

        for (term in queryTerms) {
            val postings = invertedIndex[term] ?: continue
            val df = postings.size.toDouble()
            val idf = ln((n - df + 0.5) / (df + 0.5) + 1)
            for ((docId, tf) in postings) {
                val docLen = documents[docId].termCount.toDouble()
                val tfNorm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * docLen / avgDocLen))
                scores[docId] = (scores[docId] ?: 0.0) + idf * tfNorm
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { (docId, score) ->
                val doc = documents[docId]
                SearchResult(doc.translationId, doc.verse, score)
            }
    }

    fun searchAllTerms(query: String, topK: Int = Config.reverseTopK): List<SearchResult> {
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty()) return emptyList()

        // Find doc IDs that contain ALL query terms (intersection of posting lists)
        val postingLists = queryTerms.mapNotNull { invertedIndex[it] }
        if (postingLists.size < queryTerms.size) return emptyList()

        val docIdSets = postingLists.sortedBy { it.size }
            .map { postings -> postings.map { it.docId }.toHashSet() }

        val candidates = docIdSets[0].toMutableSet()
        for (i in 1 until docIdSets.size) candidates.retainAll(docIdSets[i])
        if (candidates.isEmpty()) return emptyList()

        val scores = HashMap<Int, Double>(candidates.size * 2)
        val n = documents.size.toDouble()
        val k1 = Config.bm25K1
        val b = Config.bm25B

        for (term in queryTerms) {
            val postings = invertedIndex[term] ?: continue
            val df = postings.size.toDouble()
            val idf = ln((n - df + 0.5) / (df + 0.5) + 1)
            for ((docId, tf) in postings) {
                if (docId !in candidates) continue
                val docLen = documents[docId].termCount.toDouble()
                val tfNorm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * docLen / avgDocLen))
                scores[docId] = (scores[docId] ?: 0.0) + idf * tfNorm
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { (docId, score) ->
                val doc = documents[docId]
                SearchResult(doc.translationId, doc.verse, score)
            }
    }

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\u0400-\\u04FF]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOPWORDS }

    companion object {
        // Very common function words removed at BOTH index and query time (symmetric). Kept
        // conservative — only grammatical words, no content words — so the all-terms reverse path
        // isn't forced to match "и"/"the"/"что" inside a verse, and BM25 length-norm isn't skewed.
        private val STOPWORDS: Set<String> = (
            // English
            "the a an and or of to in on at for with that this is are was were be by it as he she " +
            "we they you his her their our my me him them us so but not from which who whom unto " +
            "thy thee ye shall will have has had do did " +
            // Russian
            "не но на во об со ко из по за от же бы ли что как это этот так там тут вот для то он " +
            "она они оно мы вы ты его её их наш ваш мой твой быть был была было были чтобы если"
        ).split(" ").filter { it.isNotBlank() }.toHashSet()
    }
}
