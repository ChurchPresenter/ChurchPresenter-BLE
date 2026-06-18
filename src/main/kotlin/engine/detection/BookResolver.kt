package engine.detection

object BookResolver {

    val CANONICAL_NAMES: Map<Int, String> = mapOf(
        1 to "Genesis", 2 to "Exodus", 3 to "Leviticus", 4 to "Numbers",
        5 to "Deuteronomy", 6 to "Joshua", 7 to "Judges", 8 to "Ruth",
        9 to "1 Samuel", 10 to "2 Samuel", 11 to "1 Kings", 12 to "2 Kings",
        13 to "1 Chronicles", 14 to "2 Chronicles", 15 to "Ezra", 16 to "Nehemiah",
        17 to "Esther", 18 to "Job", 19 to "Psalms", 20 to "Proverbs",
        21 to "Ecclesiastes", 22 to "Song of Solomon", 23 to "Isaiah", 24 to "Jeremiah",
        25 to "Lamentations", 26 to "Ezekiel", 27 to "Daniel", 28 to "Hosea",
        29 to "Joel", 30 to "Amos", 31 to "Obadiah", 32 to "Jonah",
        33 to "Micah", 34 to "Nahum", 35 to "Habakkuk", 36 to "Zephaniah",
        37 to "Haggai", 38 to "Zechariah", 39 to "Malachi",
        40 to "Matthew", 41 to "Mark", 42 to "Luke", 43 to "John",
        44 to "Acts", 45 to "Romans", 46 to "1 Corinthians", 47 to "2 Corinthians",
        48 to "Galatians", 49 to "Ephesians", 50 to "Philippians", 51 to "Colossians",
        52 to "1 Thessalonians", 53 to "2 Thessalonians",
        54 to "1 Timothy", 55 to "2 Timothy",
        56 to "Titus", 57 to "Philemon", 58 to "Hebrews", 59 to "James",
        60 to "1 Peter", 61 to "2 Peter",
        62 to "1 John", 63 to "2 John", 64 to "3 John",
        65 to "Jude", 66 to "Revelation",
    )

    // All aliases keyed by lowercase string → book number
    val ALIASES: Map<String, Int> = buildMap {
        fun add(num: Int, vararg names: String) = names.forEach { put(it, num) }

        // Genesis
        add(1, "genesis", "gen", "ge", "gn")
        // Exodus
        add(2, "exodus", "exod", "exo", "ex")
        // Leviticus
        add(3, "leviticus", "lev", "le", "lv")
        // Numbers
        add(4, "numbers", "num", "numb", "nu", "nm")
        // Deuteronomy
        add(5, "deuteronomy", "deut", "deu", "dt")
        // Joshua
        add(6, "joshua", "josh", "jos", "jsh")
        // Judges
        add(7, "judges", "judg", "jdg", "jg", "jgs")
        // Ruth
        add(8, "ruth", "rth", "ru")
        // 1 Samuel
        add(9, "1 samuel", "1samuel", "1sam", "1sa", "1s", "first samuel")
        // 2 Samuel
        add(10, "2 samuel", "2samuel", "2sam", "2sa", "2s", "second samuel")
        // 1 Kings
        add(11, "1 kings", "1kings", "1kgs", "1ki", "1k", "first kings")
        // 2 Kings
        add(12, "2 kings", "2kings", "2kgs", "2ki", "2k", "second kings")
        // 1 Chronicles
        add(13, "1 chronicles", "1chronicles", "1chr", "1ch", "first chronicles")
        // 2 Chronicles
        add(14, "2 chronicles", "2chronicles", "2chr", "2ch", "second chronicles")
        // Ezra
        add(15, "ezra", "ezr")
        // Nehemiah
        add(16, "nehemiah", "neh", "ne")
        // Esther
        add(17, "esther", "esth", "est")
        // Job
        add(18, "job", "jb")
        // Psalms
        add(19, "psalms", "psalm", "psa", "ps", "pss")
        // Proverbs
        add(20, "proverbs", "prov", "pro", "prv", "pr")
        // Ecclesiastes
        add(21, "ecclesiastes", "eccl", "eccles", "ecc", "qoh")
        // Song of Solomon
        add(22, "song of solomon", "song of songs", "song", "sos", "songs", "canticles", "cant")
        // Isaiah
        add(23, "isaiah", "isa")
        // Jeremiah
        add(24, "jeremiah", "jer", "je")
        // Lamentations
        add(25, "lamentations", "lam", "la")
        // Ezekiel
        add(26, "ezekiel", "ezek", "eze", "ezk")
        // Daniel
        add(27, "daniel", "dan", "da", "dn")
        // Hosea
        add(28, "hosea", "hos", "ho")
        // Joel
        add(29, "joel", "jl")
        // Amos
        add(30, "amos", "am")
        // Obadiah
        add(31, "obadiah", "obad", "ob")
        // Jonah
        add(32, "jonah", "jon", "jnh")
        // Micah
        add(33, "micah", "mic", "mi")
        // Nahum
        add(34, "nahum", "nah", "na")
        // Habakkuk
        add(35, "habakkuk", "hab", "hb")
        // Zephaniah
        add(36, "zephaniah", "zeph", "zep", "zp")
        // Haggai
        add(37, "haggai", "hag", "hg")
        // Zechariah
        add(38, "zechariah", "zech", "zec", "zc")
        // Malachi
        add(39, "malachi", "mal", "ml")
        // Matthew
        add(40, "matthew", "matt", "mat", "mt")
        // Mark
        add(41, "mark", "mrk", "mk", "mr")
        // Luke
        add(42, "luke", "luk", "lk")
        // John
        add(43, "john", "jn", "joh")
        // Acts
        add(44, "acts", "act", "ac")
        // Romans
        add(45, "romans", "rom", "ro", "rm")
        // 1 Corinthians
        add(46, "1 corinthians", "1corinthians", "1cor", "1co", "first corinthians")
        // 2 Corinthians
        add(47, "2 corinthians", "2corinthians", "2cor", "2co", "second corinthians")
        // Galatians
        add(48, "galatians", "gal", "ga")
        // Ephesians
        add(49, "ephesians", "eph", "ep")
        // Philippians
        add(50, "philippians", "phil", "php", "pp")
        // Colossians
        add(51, "colossians", "col")
        // 1 Thessalonians
        add(52, "1 thessalonians", "1thessalonians", "1thess", "1th", "first thessalonians")
        // 2 Thessalonians
        add(53, "2 thessalonians", "2thessalonians", "2thess", "2th", "second thessalonians")
        // 1 Timothy
        add(54, "1 timothy", "1timothy", "1tim", "1ti", "first timothy")
        // 2 Timothy
        add(55, "2 timothy", "2timothy", "2tim", "2ti", "second timothy")
        // Titus
        add(56, "titus", "tit", "ti")
        // Philemon
        add(57, "philemon", "philem", "phlm", "phm")
        // Hebrews
        add(58, "hebrews", "heb", "he")
        // James
        add(59, "james", "jas", "jm")
        // 1 Peter
        add(60, "1 peter", "1peter", "1pet", "1pe", "1pt", "first peter")
        // 2 Peter
        add(61, "2 peter", "2peter", "2pet", "2pe", "2pt", "second peter")
        // 1 John
        add(62, "1 john", "1john", "1jn", "1jo", "first john")
        // 2 John
        add(63, "2 john", "2john", "2jn", "2jo", "second john")
        // 3 John
        add(64, "3 john", "3john", "3jn", "3jo", "third john")
        // Jude
        add(65, "jude", "jud")
        // Revelation
        add(66, "revelation", "rev", "re", "rv", "apocalypse", "apoc")

        // Russian / Church Slavonic names
        add(1, "бытие", "быт")
        add(2, "исход", "исх")
        add(3, "левит", "лев")
        add(4, "числа", "чис")
        add(5, "второзаконие", "втор")
        add(6, "иисуса навина", "иис.нав.", "нав")
        add(7, "судей", "суд")
        add(8, "руфь")
        add(9, "1 царств", "1-я царств", "1цар")
        add(10, "2 царств", "2-я царств", "2цар")
        add(11, "3 царств", "3-я царств", "3цар")
        add(12, "4 царств", "4-я царств", "4цар")
        add(13, "1 паралипоменон", "1пар")
        add(14, "2 паралипоменон", "2пар")
        add(15, "ездра", "езд")
        add(16, "неемия", "неем")
        add(17, "есфирь", "есф")
        add(18, "иов")
        add(19, "псалтирь", "псалтырь", "псалом", "пс")
        add(20, "притчи", "прит", "при")
        add(21, "екклесиаст", "екк")
        add(22, "песня песней", "песни песней", "пес")
        add(23, "исаия", "ис")
        add(24, "иеремия", "иер")
        add(25, "плач иеремии", "плач")
        add(26, "иезекиль", "иез")
        add(27, "даниил", "дан")
        add(28, "осия", "ос")
        add(29, "иоиль")
        add(30, "амос")
        add(31, "авдий", "авд")
        add(32, "иона")
        add(33, "михей", "мих")
        add(34, "наум")
        add(35, "аввакум", "авв")
        add(36, "софония", "соф")
        add(37, "аггей", "агг")
        add(38, "захария", "зах")
        add(39, "малахия", "мал")
        add(40, "матфей", "мф", "от матфея")
        add(41, "марк", "мк", "от марка")
        add(42, "лука", "лк", "от луки")
        add(43, "иоанна", "иоанн", "ин", "от иоанна")
        add(44, "деяния", "деян")
        add(45, "римлянам", "рим")
        add(46, "1 коринфянам", "1кор")
        add(47, "2 коринфянам", "2кор")
        add(48, "галатам", "гал")
        add(49, "ефесянам", "еф")
        add(50, "филиппийцам", "флп")
        add(51, "колоссянам", "кол")
        add(52, "1 фессалоникийцам", "1фес")
        add(53, "2 фессалоникийцам", "2фес")
        add(54, "1 тимофею", "1тим")
        add(55, "2 тимофею", "2тим")
        add(56, "титу", "тит")
        add(57, "филимону", "флм")
        add(58, "евреям", "евр")
        add(59, "иакова", "иак")
        add(60, "1 петра", "1пет")
        add(61, "2 петра", "2пет")
        add(62, "1 иоанна", "1ин")
        add(63, "2 иоанна", "2ин")
        add(64, "3 иоанна", "3ин")
        add(65, "иуды", "иуд")
        add(66, "откровение", "откр", "апокалипсис")
    }

    // Sorted longest-first for greedy matching
    val ALIASES_BY_LENGTH: List<Pair<String, Int>> =
        ALIASES.entries.sortedByDescending { it.key.length }.map { it.key to it.value }

    fun canonicalName(bookNum: Int): String = CANONICAL_NAMES[bookNum] ?: "Book $bookNum"
}
