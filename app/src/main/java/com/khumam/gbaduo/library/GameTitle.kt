package com.khumam.gbaduo.library

/**
 * A readable game name for the library. ROM headers only hold a 12-15 character code
 * ("GBAZELDA MC"), while most dumps are named the No-Intro way:
 * "Legend of Zelda, The - The Minish Cap (Europe) (En,Fr,De,Es,It).gba".
 * That becomes "The Legend of Zelda: The Minish Cap". Pure, no Android deps.
 */
object GameTitle {
    private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val ARTICLE = Regex("""^(.+), (The|A|An)$""", RegexOption.IGNORE_CASE)

    /** The cleaned file name, or [headerTitle] when the file name gives nothing usable. */
    fun display(fileName: String, headerTitle: String): String {
        val fromFile = fromFileName(fileName)
        return fromFile.ifBlank { headerTitle.trim() }.ifBlank { fileName }
    }

    fun fromFileName(fileName: String): String {
        var name = fileName.substringAfterLast('/').let { n ->
            val dot = n.lastIndexOf('.')
            if (dot > 0) n.substring(0, dot) else n
        }
        // Region, language, revision and dump tags: "(USA)", "(En,Fr)", "[!]".
        name = name.replace(TAGS, "").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        // "Legend of Zelda, The - The Minish Cap" -> "The Legend of Zelda: The Minish Cap".
        val parts = name.split(" - ").map { part ->
            ARTICLE.matchEntire(part.trim())?.let { "${it.groupValues[2]} ${it.groupValues[1]}" } ?: part.trim()
        }.filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts[0] + ": " + parts.drop(1).joinToString(" - ")
        }
    }
}
