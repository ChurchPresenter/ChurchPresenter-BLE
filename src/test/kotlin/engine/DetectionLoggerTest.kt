package engine

import engine.engine.DetectionLogger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DetectionLoggerTest {

    @Test fun `logStickyChange writes a sticky-log file with the expected fields`() {
        val dir = Files.createTempDirectory("sticky-log-test").toFile()
        val prevPath = DetectionLogger.path
        val prevSessionId = DetectionLogger.sessionId
        try {
            DetectionLogger.path = "${dir.absolutePath}/detection-log.jsonl"
            DetectionLogger.sessionId = "test-session"
            Config.logStickyChanges = true

            DetectionLogger.logStickyChange(
                transcript = "и мы читаем", translation = "and we read",
                prevBook = 46, prevChapter = 11, newBook = 9, newChapter = 15,
            )

            val stickyLog = dir.listFiles()?.singleOrNull { it.name.startsWith("sticky-log-") }
            assertTrue(stickyLog != null, "expected a sticky-log-*.jsonl file, got ${dir.listFiles()?.map { it.name }}")
            val line = stickyLog.readText()
            assertContains(line, "\"prevBook\":46")
            assertContains(line, "\"prevChapter\":11")
            assertContains(line, "\"newBook\":9")
            assertContains(line, "\"newChapter\":15")
            assertContains(line, "\"sessionId\":\"test-session\"")
            assertContains(line, "\"transcript\":\"и мы читаем\"")
        } finally {
            DetectionLogger.path = prevPath
            DetectionLogger.sessionId = prevSessionId
            dir.deleteRecursively()
        }
    }

    @Test fun `logStickyChange is a no-op when Config logStickyChanges is disabled`() {
        val dir = Files.createTempDirectory("sticky-log-test-disabled").toFile()
        val prevPath = DetectionLogger.path
        try {
            DetectionLogger.path = "${dir.absolutePath}/detection-log.jsonl"
            Config.logStickyChanges = false

            DetectionLogger.logStickyChange("a", "b", null, null, 9, 15)

            assertTrue(dir.listFiles().isNullOrEmpty(), "expected no file written when disabled")
        } finally {
            DetectionLogger.path = prevPath
            Config.logStickyChanges = true
            dir.deleteRecursively()
        }
    }
}
