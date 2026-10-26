package id.gerry.gbook

/**
 * One chapter map shared by the reader, stats, and social sharing surfaces.
 * Real imported chapter boundaries are preferred; books without them use the same stable
 * synthetic 24-part map everywhere.
 */
internal data class ChapterProgress(
    val starts: IntArray,
    val labels: List<String>,
    val depths: IntArray,
    val navigationIds: List<String>
) {
    val count: Int get() = starts.size

    fun indexAt(paragraph: Int): Int {
        val target = paragraph.coerceAtLeast(0)
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= target) low = middle + 1 else high = middle - 1
        }
        return high.coerceIn(0, starts.lastIndex.coerceAtLeast(0))
    }

    fun numberAt(paragraph: Int): Int = indexAt(paragraph) + 1
    fun start(index: Int): Int = starts.getOrNull(index) ?: -1
    fun title(index: Int): String = labels.getOrElse(index) { "Chapter ${index + 1}" }
    fun depth(index: Int): Int = depths.getOrElse(index) { 0 }
    fun navigationId(index: Int): String = navigationIds.getOrElse(index) { "" }

    /** First later authored entry that advances the text position, or the end of the book. */
    fun endExclusive(index: Int, paragraphCount: Int): Int {
        val start = start(index).coerceAtLeast(0)
        return ((index + 1) until count)
            .firstNotNullOfOrNull { later -> starts[later].takeIf { it > start } }
            ?: paragraphCount.coerceAtLeast(start + 1)
    }
}

/**
 * Complete reading position consumed by every progress/stat surface.
 * Percentage uses the full authored navigation hierarchy. Every selectable main chapter and
 * nested subsection contributes one equal step; movement inside the active entry is interpolated
 * to the next distinct TOC target. This keeps chapter totals, chapter index and overall percentage
 * synchronized everywhere, even when a parent and its first child point at the same paragraph.
 */
internal data class ReadingProgress(
    val paragraph: Int,
    val paragraphCount: Int,
    val fraction: Float,
    val percent: Int,
    val chapterIndex: Int,
    val chapterNumber: Int,
    val chapterCount: Int,
    val chapterTitle: String
)

internal fun readingProgress(
    book: Book,
    currentParagraph: Int = book.currentParagraph,
    paragraphCount: Int = book.paragraphCount,
    finished: Boolean = book.finished
): ReadingProgress {
    val safeCount = paragraphCount.coerceAtLeast(1)
    val paragraph = currentParagraph.coerceIn(0, safeCount - 1)
    val chapters = chapterProgress(book, safeCount)
    val preferredChapter = book.currentChapterId
        ?.let { id -> chapters.navigationIds.indexOf(id).takeIf { it >= 0 } }
        ?.takeIf { chapters.start(it) == paragraph }
    val chapterIndex = preferredChapter ?: chapters.indexAt(paragraph)
    val chapterStart = chapters.start(chapterIndex).coerceAtLeast(0)
    val chapterEndExclusive = chapters.endExclusive(chapterIndex, safeCount)
    val localDenominator = (chapterEndExclusive - chapterStart).coerceAtLeast(1)
    val localFraction = ((paragraph - chapterStart).coerceAtLeast(0).toFloat() / localDenominator)
        .coerceIn(0f, 1f)
    val fraction = if (finished) 1f else {
        ((chapterIndex + localFraction) / chapters.count.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    }
    val percent = if (finished) 100 else (fraction * 100f).toInt().coerceIn(0, 99)
    return ReadingProgress(
        paragraph = paragraph,
        paragraphCount = safeCount,
        fraction = fraction,
        percent = percent,
        chapterIndex = chapterIndex,
        chapterNumber = chapterIndex + 1,
        chapterCount = chapters.count,
        chapterTitle = chapters.title(chapterIndex)
    )
}

internal fun chapterProgress(book: Book, paragraphCount: Int = book.paragraphCount): ChapterProgress {
    val safeParagraphCount = paragraphCount.coerceAtLeast(1)
    val realChapters = book.chapters
        .asSequence()
        .filter { it.startParagraph in 0 until safeParagraphCount }
        .sortedBy { it.startParagraph }
        .toList()

    if (realChapters.isNotEmpty()) {
        return ChapterProgress(
            starts = realChapters.map { it.startParagraph }.toIntArray(),
            labels = realChapters.mapIndexed { index, chapter ->
                chapter.title.trim().takeIf { it.isNotEmpty() } ?: "Chapter ${index + 1}"
            },
            depths = realChapters.map { it.depth.coerceAtLeast(0) }.toIntArray(),
            navigationIds = realChapters.mapIndexed { index, chapter ->
                chapter.navigationId.ifBlank { "legacy:$index" }
            }
        )
    }

    val count = minOf(24, safeParagraphCount)
    val starts = IntArray(count) { index -> index * safeParagraphCount / count }
    val labels = List(count) { index -> if (index == 0) "Beginning" else "Chapter ${index + 1}" }
    return ChapterProgress(starts, labels, IntArray(count), List(count) { "synthetic:$it" })
}

