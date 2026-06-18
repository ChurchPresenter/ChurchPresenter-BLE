package engine.bible

import engine.Config
import java.io.File

object SpbLoader {

    private val LXX_LANGUAGES = setOf(
        "RUS", "UKR", "BEL", "SRP", "SCR", "BUL", "MKD", "ROM", "RUM", "MOL",
        "KAT", "GEO", "GRE", "GRC", "ELL", "AMH", "ETH", "COP", "SYR", "ARC",
    )

    fun numberingFor(language: String): String =
        if (language.uppercase() in LXX_LANGUAGES) "lxx" else "hebrew"

    fun loadAll(): List<EngineTranslation> {
        val root = File(Config.bibleRoot)
        if (!root.exists()) {
            System.err.println("Bible root not found: ${Config.bibleRoot}")
            return emptyList()
        }
        val spbFiles = root.walk()
            .filter { it.isFile && it.name.endsWith(".spb") }
            .toList()
            .sortedBy { it.name }

        val translations = mutableListOf<EngineTranslation>()
        val seenIds = mutableMapOf<String, Int>()

        for (file in spbFiles) {
            try {
                val t = parseFile(file, seenIds) ?: continue
                if (t.byBCV.size < 10) continue
                translations.add(t)
            } catch (e: Exception) {
                System.err.println("Warning: failed to parse ${file.name}: ${e.message}")
            }
        }
        return translations
    }

    fun loadDefaults(): List<EngineTranslation> {
        val root = File(Config.bibleRoot)
        if (!root.exists()) return emptyList()

        val spbFiles = root.walk()
            .filter { it.isFile && it.name.endsWith(".spb") }
            .toList()
            .sortedBy { it.name }

        val targets = Config.defaultTranslations.toSet()
        val seenIds = mutableMapOf<String, Int>()
        val results = mutableListOf<EngineTranslation>()

        for (file in spbFiles) {
            val lang = extractLanguage(file.name)
            val abbr = file.useLines(Charsets.UTF_8) { lines ->
                lines.take(20).firstOrNull { it.startsWith("##Abbreviation:") }
                    ?.removePrefix("##Abbreviation:")?.trim()
            }
            if (abbr.isNullOrBlank()) continue

            val sanitized = abbr.replace(Regex("[^A-Za-z0-9]"), "")
            val baseId = "${lang}_${sanitized}"
            val count = seenIds.getOrDefault(baseId, 0)
            seenIds[baseId] = count + 1
            val id = if (count == 0) baseId else "${baseId}_${count + 1}"

            if (id !in targets) continue

            // Pass a copy with baseId rolled back so parseFile computes the same id
            val parseSeenIds = seenIds.toMutableMap().apply { this[baseId] = count }
            try {
                val t = parseFile(file, parseSeenIds) ?: continue
                if (t.byBCV.size >= 10) results.add(t)
            } catch (e: Exception) {
                System.err.println("Warning: failed to parse ${file.name}: ${e.message}")
            }
        }
        return results
    }

    private fun parseFile(file: File, seenIds: MutableMap<String, Int>): EngineTranslation? {
        val lang = extractLanguage(file.name)
        var title = ""
        var abbreviation = ""
        val books = mutableListOf<EngineBook>()
        val verses = mutableListOf<EngineVerse>()

        val lines = file.readLines(Charsets.UTF_8)
        var pastSeparator = false

        for (line in lines) {
            if (!pastSeparator) {
                when {
                    line.startsWith("##Title:") ->
                        title = line.removePrefix("##Title:").trim()
                    line.startsWith("##Abbreviation:") ->
                        abbreviation = line.removePrefix("##Abbreviation:").trim()
                    line.startsWith("##") -> Unit
                    line.trimEnd() == "-----" -> pastSeparator = true
                    !line.startsWith(" ") && !line.startsWith("\t") && line.isNotBlank() -> {
                        val parts = line.split("\t")
                        if (parts.size >= 3) {
                            val num = parts[0].trim().toIntOrNull()
                            val chapCount = parts[2].trim().toIntOrNull()
                            if (num != null && chapCount != null && parts[1].isNotBlank()) {
                                books.add(EngineBook(num, parts[1].trim(), chapCount))
                            }
                        }
                    }
                }
            } else {
                if (!line.startsWith("B")) continue
                val parts = line.split("\t", limit = 5)
                if (parts.size < 5) continue
                val code = parts[0]
                val bookNum = parts[1].toIntOrNull() ?: continue
                val chapter = parts[2].toIntOrNull() ?: continue
                val verse = parts[3].toIntOrNull() ?: continue
                val text = parts[4]
                verses.add(EngineVerse(code, bookNum, chapter, verse, text, verse == 0))
            }
        }

        if (abbreviation.isBlank()) return null

        val sanitizedAbbr = abbreviation.replace(Regex("[^A-Za-z0-9]"), "")
        val baseId = "${lang}_${sanitizedAbbr}"
        val count = seenIds.getOrDefault(baseId, 0)
        seenIds[baseId] = count + 1
        val id = if (count == 0) baseId else "${baseId}_${count + 1}"

        val byBCV = HashMap<Triple<Int, Int, Int>, EngineVerse>(verses.size * 2)
        val byChapterMut = HashMap<Pair<Int, Int>, MutableList<EngineVerse>>()
        val byCode = HashMap<String, EngineVerse>(verses.size * 2)

        for (v in verses) {
            byBCV[Triple(v.bookNum, v.chapter, v.verse)] = v
            byChapterMut.getOrPut(Pair(v.bookNum, v.chapter)) { mutableListOf() }.add(v)
            byCode[v.code] = v
        }

        return EngineTranslation(
            id = id,
            title = title,
            abbreviation = abbreviation,
            language = lang,
            numbering = numberingFor(lang),
            books = books,
            byBCV = byBCV,
            byChapter = byChapterMut,
            byCode = byCode,
        )
    }

    private fun extractLanguage(filename: String): String =
        filename.substringBefore("_").uppercase()
}
