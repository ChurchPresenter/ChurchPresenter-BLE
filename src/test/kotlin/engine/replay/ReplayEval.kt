package engine.replay

import engine.AppConfig
import java.io.File

/**
 * Scores a replayed session against operator ground truth — run via `./gradlew replayEval`:
 *
 *   ./gradlew replayEval --args="--db /path/service.db --lref /path/live-references-<id>.jsonl \
 *       [--outcomes /path/suggestion-outcomes-<id>.jsonl] [--out /tmp/replay.jsonl] \
 *       [--bibles 'a.spb,b.spb'] [--level balanced]"
 *
 * Matching rule (TRAINING_PLAN's evaluation window): a detection is a TP for a live reference
 * when book+chapter+verseStart match and |detection ts − live ts| <= 90 s. Live references are
 * the operator's ground truth (what was actually shown); `action:"corrected"` rows from
 * suggestion-outcomes are counted as labeled false positives for the suggested ref.
 *
 * Output: per-matchType table (emitted / TP / labeled-FP) + the FN list (live refs no detection
 * matched). Detections outside the sermon bracket (first..last live ref ± window) are reported
 * separately as "unbracketed" — not judged, since there is no ground truth there.
 */
object ReplayEval {

    private const val WINDOW_MS = 90_000L

    data class GroundTruth(val tsMs: Long, val book: Int, val chapter: Int, val verse: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parseArgs(args)
        val dbPath = opts["db"] ?: error("--db required")
        val lrefPath = opts["lref"] ?: error("--lref required")
        val level = opts["level"] ?: "balanced"
        val bibles = opts["bibles"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val bibleRoot = AppConfig.discoverBibleRoot() ?: error("no bible root discoverable")
        val translations = DbReplay.loadTranslations(bibleRoot, bibles)
        check(translations.isNotEmpty()) { "no bibles loadable from $bibleRoot" }
        println("bibles: ${translations.map { it.id }}, level: $level")

        val rows = DbReplay.readRows(dbPath)
        println("db rows: ${rows.size}")
        val result = DbReplay.replay(rows, translations, level)
        println("emitted events: ${result.events.size}")
        opts["out"]?.let { out ->
            File(out).writeText(result.lines.joinToString("\n") + "\n")
            println("replay written: $out")
        }

        val truth = readLiveReferences(File(lrefPath))
        check(truth.isNotEmpty()) { "no live references in $lrefPath" }
        val corrected = opts["outcomes"]?.let { readCorrectedOutcomes(File(it)) } ?: emptyList()

        val bracketStart = truth.minOf { it.tsMs } - WINDOW_MS
        val bracketEnd = truth.maxOf { it.tsMs } + WINDOW_MS

        // Per-detection judgement
        val matchTypes = result.events.map { it.matchType }.distinct().sorted()
        data class Bucket(var emitted: Int = 0, var tp: Int = 0, var labeledFp: Int = 0, var unbracketed: Int = 0)
        val buckets = LinkedHashMap<String, Bucket>()
        matchTypes.forEach { buckets[it] = Bucket() }
        val matchedTruth = HashSet<Int>()

        result.events.forEachIndexed { i, e ->
            val ts = result.eventTsMs[i]
            val bucket = buckets.getValue(e.matchType)
            bucket.emitted++
            if (ts < bracketStart || ts > bracketEnd) {
                bucket.unbracketed++
                return@forEachIndexed
            }
            val hit = truth.indexOfFirst { t ->
                t.book == e.reference.bookId && t.chapter == e.reference.chapter &&
                    t.verse == e.reference.verseStart && kotlin.math.abs(t.tsMs - ts) <= WINDOW_MS
            }
            if (hit >= 0) {
                bucket.tp++
                matchedTruth.add(hit)
            }
            if (corrected.any { c ->
                    c.book == e.reference.bookId && c.chapter == e.reference.chapter &&
                        c.verse == e.reference.verseStart && kotlin.math.abs(c.tsMs - ts) <= WINDOW_MS
                }
            ) {
                bucket.labeledFp++
            }
        }

        println()
        println("%-16s %8s %6s %10s %12s".format("matchType", "emitted", "TP", "labeledFP", "unbracketed"))
        buckets.forEach { (type, b) ->
            println("%-16s %8d %6d %10d %12d".format(type, b.emitted, b.tp, b.labeledFp, b.unbracketed))
        }
        println()
        val fns = truth.filterIndexed { i, _ -> i !in matchedTruth }
        println("ground-truth live references: ${truth.size}, matched: ${matchedTruth.size}, FN: ${fns.size}")
        fns.forEach { println("  FN: book=${it.book} ${it.chapter}:${it.verse} at tsMs=${it.tsMs}") }
    }

    private fun readLiveReferences(file: File): List<GroundTruth> =
        file.readLines().mapNotNull { line ->
            if (line.isBlank() || line.contains("\"type\":\"session\"")) return@mapNotNull null
            val book = intField(line, "book") ?: return@mapNotNull null
            val chapter = intField(line, "chapter") ?: return@mapNotNull null
            val verse = intField(line, "verseStart") ?: return@mapNotNull null
            val ts = longField(line, "ts_ms") ?: return@mapNotNull null
            GroundTruth(ts, book, chapter, verse)
        }

    private fun readCorrectedOutcomes(file: File): List<GroundTruth> =
        file.readLines().mapNotNull { line ->
            if (!line.contains("\"action\":\"corrected\"")) return@mapNotNull null
            val book = intField(line, "suggestedBook") ?: return@mapNotNull null
            val chapter = intField(line, "suggestedChapter") ?: return@mapNotNull null
            val verse = intField(line, "suggestedVerse") ?: return@mapNotNull null
            val ts = longField(line, "ts_ms") ?: return@mapNotNull null
            GroundTruth(ts, book, chapter, verse)
        }

    // The logs are flat single-line JSON written by TrainingDataLogger — regex extraction keeps
    // this tool dependency-free (org.json would work too; not worth the coupling for two files).
    private fun intField(line: String, name: String): Int? =
        Regex("\"$name\":(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()

    private fun longField(line: String, name: String): Long? =
        Regex("\"$name\":(-?\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size) {
                map[a.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        return map
    }
}
