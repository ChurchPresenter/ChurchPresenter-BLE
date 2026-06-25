package engine

import engine.detection.ReferenceWatcher
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression replay over an archived service `.db` backup. The `.db` files are never committed (they
 * contain full service transcripts); pass one via `-Dreplay.db=…` plus a curated fixture id via
 * `-Dreplay.fixture=service1|service2|service3`, otherwise the test skips gracefully so CI without
 * the local files still passes.
 *
 * Streams each row in `id` order through a single sticky context — mirroring the live feed, which
 * concatenates transcript + translation (`DetectionEngine.runDetection` builds `"$transcript
 * $translation"`). Feeding the combined string lets the translation track resolve a book whose source
 * was STT-garbled, giving cross-language corroboration. Each row is processed with its real
 * wall-clock `timestamp`, so the sticky TTL expires/holds books exactly as it does live. All
 * assertions are programmatic (no Cyrillic to stdout — Windows cp1252 caveat).
 *
 * Detection is not held to 100% on every row — the translation track can drag in number/book noise;
 * rows it genuinely garbles are left unasserted (their clean form is covered by ReferenceWatcherTest).
 *
 *     ./gradlew test -Dreplay.db="/path/to/your/service1.db" -Dreplay.fixture=service1
 *     ./gradlew test -Dreplay.db="/path/to/your/service2.db" -Dreplay.fixture=service2
 *     ./gradlew test -Dreplay.db="/path/to/your/service3.db" -Dreplay.fixture=service3
 */
class DbReplayTest {

    private class TestSticky : ReferenceWatcher.Sticky {
        override var watchBook: Int? = null
        override var watchChapter: Int? = null
        override var watchExpiresAt: Long = 0L
    }

    /** One curated expected reference. [vEnd] null = don't care about the range end. */
    private data class Expect(val book: Int, val ch: Int, val v: Int?, val vEnd: Int? = null)

    // Curated rows per fixture id (not file name, to avoid embedding real backup filenames) — exact
    // book + chapter + verse(+range). Asserted with `any { … }` so extra (e.g. duplicate
    // cross-language) emissions on the same row are fine. With the real per-row timestamp driving
    // sticky TTL plus the short-alias guard, sticky-book reads resolve via the translation track —
    // see ReferenceWatcherTest for the isolated proofs.
    private val exactByFixture: Map<String, Map<Int, Expect>> = mapOf(
        "service1" to mapOf(           // ~751 rows
            27 to Expect(49, 4, 6),    // explicit book + chapter + verse
            28 to Expect(49, 4, 6),    // sticky verse against the previous row's book/chapter
            377 to Expect(5, 6, 4, 9), // word-ordinal chapter + "from N to M" verse range
            378 to Expect(5, 6, 4),    // explicit book + chapter + verse
            410 to Expect(40, 7, 21),  // split across rows: book+chapter, then verse (instrumental)
        ),
        "service2" to mapOf(           // ~802 rows
            3 to Expect(51, 3, 21),    // explicit book + chapter + verse
            633 to Expect(6, 3, 14),   // verse-before-chapter; book resolved via translation track
            661 to Expect(6, 4, 5),    // sticky book+chapter, then "from verse N"
        ),
        // service3 (~715 rows, new STT schema). Curate true-positive rows from the local backup and
        // list them here (id → Expect), mirroring the recipe above. Left empty until the rows are
        // curated against the local `.db` — the test then skips this fixture rather than asserting
        // fabricated ids. Add matching clean-form cases in ReferenceWatcherTest as they are curated.
        "service3" to emptyMap(),
    )

    // One service2 row is intentionally not asserted: the translation's trailing number is misread as
    // a verse (chapter-only reference gains a spurious verse). The clean source-language path for that
    // pattern is covered in ReferenceWatcherTest.

    // Rows that must emit NOTHING (verse/chapter keyword look-alikes, bare verse/chapter words, no number).
    private val negativeByFixture: Map<String, Set<Int>> = mapOf(
        "service1" to setOf(332, 356, 401, 662, 665, 701),
        "service2" to setOf(12, 623, 624, 712),
        // service3 precision negatives — curate must-emit-nothing rows from the local backup here.
        "service3" to emptySet(),
    )

    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Parses the `'yyyy-MM-dd HH:mm:ss'` timestamp to epoch millis (UTC — only relative gaps
     *  matter), or null when blank/unparseable so the caller can fall back to the last value. */
    private fun parseTimestampMs(ts: String?): Long? =
        ts?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDateTime.parse(it, tsFormat).toInstant(ZoneOffset.UTC).toEpochMilli() }
                .getOrNull()
        }

    @Test fun `replay archived service db`() {
        val path = System.getProperty("replay.db").orEmpty()
        assumeTrue(path.isNotBlank(), "replay.db not set — skipping db replay")
        val file = File(path)
        assumeTrue(file.exists(), "replay.db file does not exist: $path")

        val fixture = System.getProperty("replay.fixture").orEmpty()
        val exact = exactByFixture[fixture] ?: emptyMap()
        val negatives = negativeByFixture[fixture] ?: emptySet()
        assumeTrue(exact.isNotEmpty() || negatives.isNotEmpty(),
            "set -Dreplay.fixture=service1|service2|service3 to assert curated rows — skipping")

        Config.applyLevel("balanced")
        try {
            val byId = HashMap<Int, List<ReferenceWatcher.Ref>>()
            val sticky = TestSticky()
            var lastMs = 0L  // monotonic fallback for blank/unparseable timestamps (e.g. row #1)
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery(
                        "SELECT id, timestamp, text, translated_text, speech_type " +
                            "FROM transcriptions ORDER BY id")
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        // Feed the real wall-clock from the row so sticky TTL/expiry behaves as it
                        // does live (a book read minutes ago expires; one named seconds ago holds).
                        val now = parseTimestampMs(rs.getString("timestamp")) ?: lastMs
                        lastMs = now
                        // Mirror DetectionEngine.runDetection: feed transcript + translation together,
                        // and apply the music precision gate from speech_type.
                        val text = rs.getString("text") ?: ""
                        val translated = rs.getString("translated_text") ?: ""
                        val combined = "$text $translated".trim()
                        val isMusic = rs.getString("speech_type").equals("Music", ignoreCase = true)
                        byId[id] = ReferenceWatcher.process(combined, sticky, now, isMusic = isMusic)
                    }
                }
            }

            val failures = StringBuilder()
            for ((id, e) in exact) {
                val refs = byId[id].orEmpty()
                val ok = refs.any {
                    it.bookNum == e.book && it.chapter == e.ch && it.verseStart == e.v &&
                        (e.vEnd == null || it.verseEnd == e.vEnd)
                }
                if (!ok) failures.append("row $id expected $e but got $refs\n")
            }
            for (id in negatives) {
                val refs = byId[id].orEmpty()
                if (refs.isNotEmpty()) failures.append("row $id expected NO ref but got $refs\n")
            }
            assertTrue(failures.isEmpty(), "DB replay mismatches in fixture '$fixture':\n$failures")
        } finally {
            Config.applyLevel("balanced")
        }
    }
}
