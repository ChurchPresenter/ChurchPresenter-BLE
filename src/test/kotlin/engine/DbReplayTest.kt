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
 * `-Dreplay.fixture=<id>`, otherwise the test skips gracefully so CI without the local files still passes.
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
 * To add a new service session, curate its rows per TRAINING_PLAN.md §Test Strategy, then add a
 * fixture entry here. Only include rows where the full reference is self-contained in the row text
 * (no rolling-window dependency on adjacent rows).
 *
 *     ./gradlew test "-Dreplay.db=/path/to/service.db" "-Dreplay.fixture=<id>"
 */
class DbReplayTest {

    private class TestSticky : ReferenceWatcher.Sticky {
        override var watchBook: Int? = null
        override var watchChapter: Int? = null
        override var watchExpiresAt: Long = 0L
    }

    /** One curated expected reference. [vEnd] null = don't care about the range end. */
    private data class Expect(val book: Int, val ch: Int, val v: Int?, val vEnd: Int? = null)

    // Curated rows per fixture id — exact book + chapter + verse(+range). Add entries here when
    // folding in a new service session (see TRAINING_PLAN.md §Test Strategy for curation rules).
    // Asserted with `any { … }` so extra cross-language emissions on the same row are fine.
    private val exactByFixture: Map<String, Map<Int, Expect>> = emptyMap()

    // Rows that must emit NOTHING (verse/chapter keyword look-alikes, bare verse/chapter words, etc).
    private val negativeByFixture: Map<String, Set<Int>> = emptyMap()

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
            "set -Dreplay.fixture=<id> to assert curated rows — skipping")

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
