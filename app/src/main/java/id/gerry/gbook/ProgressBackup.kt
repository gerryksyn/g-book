package id.gerry.gbook

/** Result of applying a progress-only CSV backup to the books currently installed on-device. */
data class ProgressRestoreResult(
    val books: List<Book>,
    val restoredCount: Int,
    val missingCount: Int
)

private val PROGRESS_COLUMNS = listOf(
    "book_id",
    "title",
    "author",
    "current_paragraph",
    "current_sentence",
    "paragraph_scroll_offset",
    "paragraph_count",
    "active_chapter_id",
    "active_chapter_depth",
    "active_chapter_title",
    "finished",
    "listening_ms",
    "last_read_at"
)

// Only the original core progress fields are mandatory. This keeps paragraph-only and old flat
// chapter-number backups importable while v0.5.6 adds exact sentence, viewport and hierarchy data.
private val REQUIRED_PROGRESS_COLUMNS = setOf(
    "book_id",
    "title",
    "author",
    "current_paragraph",
    "paragraph_count",
    "finished",
    "listening_ms",
    "last_read_at"
)

/** Creates a portable progress-only backup; book content and cover files are intentionally omitted. */
fun exportProgressCsv(books: List<Book>): String = buildString {
    appendLine(PROGRESS_COLUMNS.joinToString(","))
    books.forEach { book ->
        val progress = readingProgress(book)
        val chapters = chapterProgress(book)
        appendLine(
            listOf(
                book.id,
                book.title,
                book.author,
                book.currentParagraph,
                book.currentSentence,
                book.paragraphScrollOffset,
                book.paragraphCount,
                chapters.navigationId(progress.chapterIndex),
                chapters.depth(progress.chapterIndex),
                progress.chapterTitle,
                book.finished,
                book.listeningMs,
                book.lastReadAt
            ).joinToString(",") { csvCell(it.toString()) }
        )
    }
}

/**
 * Restores progress onto matching locally installed books. The stable book id is authoritative;
 * unmatched rows are reported but never create broken books without their source text files.
 */
fun restoreProgressCsv(csv: String, localBooks: List<Book>): ProgressRestoreResult {
    val rows = parseCsv(csv)
    require(rows.isNotEmpty()) { "The backup file is empty." }
    val header = rows.first().map { it.trim().removePrefix("\uFEFF") }
    require(REQUIRED_PROGRESS_COLUMNS.all { it in header }) { "This is not a G-Book progress backup." }
    val positions = header.withIndex().associate { it.value to it.index }
    val localById = localBooks.associateBy { it.id }
    val restored = mutableMapOf<String, Book>()
    var missing = 0

    rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.forEach { row ->
        fun value(column: String): String = positions[column]?.let { row.getOrNull(it) }.orEmpty()
        val id = value("book_id")
        val local = localById[id]
        if (local == null) {
            missing++
            return@forEach
        }
        val paragraphCount = value("paragraph_count").toIntOrNull()
            ?.coerceAtLeast(1)
            ?: local.paragraphCount.coerceAtLeast(1)
        val savedParagraph = value("current_paragraph").toIntOrNull()
            ?.coerceIn(0, paragraphCount - 1)
            ?: local.currentParagraph.coerceIn(0, paragraphCount - 1)
        val chapterId = resolveRestoredChapterId(
            local = local,
            savedId = value("active_chapter_id"),
            savedTitle = value("active_chapter_title"),
            savedDepth = value("active_chapter_depth").toIntOrNull(),
            legacyNumber = value("active_chapter").toIntOrNull()
        )
        val localChapterMap = chapterProgress(local, paragraphCount)
        val matchedChapterIndex = chapterId
            ?.let { localChapterMap.navigationIds.indexOf(it) }
            ?.takeIf { it >= 0 }
        // Prefer the precise saved paragraph. If reparsing moved it outside the identified TOC
        // entry, restore to that entry's exact anchor instead of landing in another subsection.
        val paragraph = matchedChapterIndex?.let { index ->
            val start = localChapterMap.start(index).coerceIn(0, paragraphCount - 1)
            val endExclusive = localChapterMap.endExclusive(index, paragraphCount)
            savedParagraph.takeIf { it in start until endExclusive } ?: start
        } ?: savedParagraph
        restored[id] = local.copy(
            currentParagraph = paragraph,
            currentSentence = positions["current_sentence"]
                ?.let { row.getOrNull(it) }
                ?.toIntOrNull()
                ?.coerceAtLeast(-1)
                ?: local.currentSentence,
            paragraphScrollOffset = positions["paragraph_scroll_offset"]
                ?.let { row.getOrNull(it) }
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: local.paragraphScrollOffset,
            paragraphCount = paragraphCount,
            finished = parseCsvBoolean(value("finished")) ?: local.finished,
            listeningMs = value("listening_ms").toLongOrNull()?.coerceAtLeast(0L) ?: local.listeningMs,
            lastReadAt = value("last_read_at").toLongOrNull()?.coerceAtLeast(0L) ?: local.lastReadAt,
            currentChapterId = chapterId
        )
    }

    return ProgressRestoreResult(
        books = localBooks.map { restored[it.id] ?: it },
        restoredCount = restored.size,
        missingCount = missing
    )
}

/**
 * Resolve the v0.5.6 hierarchy id, with title/depth and old flat-number fallbacks so backups from
 * earlier G-Book versions remain useful after the EPUB navigation structure is reparsed.
 */
private fun resolveRestoredChapterId(
    local: Book,
    savedId: String,
    savedTitle: String,
    savedDepth: Int?,
    legacyNumber: Int?
): String? {
    val chapters = chapterProgress(local)
    chapters.navigationIds.firstOrNull { savedId.isNotBlank() && it == savedId }
        ?.let { return it }

    if (savedTitle.isNotBlank() && savedDepth != null) {
        chapters.labels.indices.firstOrNull { index ->
            chapters.title(index).equals(savedTitle, ignoreCase = true) &&
                chapters.depth(index) == savedDepth
        }?.let { return chapters.navigationId(it) }
    }

    legacyNumber
        ?.minus(1)
        ?.let(chapters::navigationId)
        ?.takeIf(String::isNotBlank)
        ?.let { return it }

    return chapters.labels.indices.firstOrNull { index ->
        savedTitle.isNotBlank() && chapters.title(index).equals(savedTitle, ignoreCase = true)
    }?.let(chapters::navigationId)
}

private fun parseCsvBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}

private fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value

/** RFC-4180 style parser supporting commas, escaped quotes, and line breaks inside quoted cells. */
private fun parseCsv(csv: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < csv.length) {
        val c = csv[i]
        when {
            quoted && c == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> {
                cell.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            !quoted && c == ',' -> {
                row.add(cell.toString())
                cell.clear()
            }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                row.add(cell.toString())
                cell.clear()
                rows.add(row)
                row = mutableListOf()
            }
            else -> cell.append(c)
        }
        i++
    }
    require(!quoted) { "The backup CSV contains an unfinished quoted value." }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row)
    }
    return rows
}
