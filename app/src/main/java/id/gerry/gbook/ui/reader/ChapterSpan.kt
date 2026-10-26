package id.gerry.gbook.ui.reader

import id.gerry.gbook.Book
import id.gerry.gbook.chapterProgress

/**
 * Flattens a book's chapters into paragraph boundaries so the header can render
 * "Chapter X of Y" and jump around even when an import carried no real chapter map.
 *
 * If [Book.chapters] is non-empty those real titles are used (falling back to paragraph
 * ranges). Otherwise a synthetic grid splits the book into roughly N equal chunks so the
 * header always has a meaningful chapter count.
 */
internal data class ChapterSpan(
    val starts: IntArray,
    val labels: List<String>,
    val depths: IntArray,
    val navigationIds: List<String>
) {

    val count: Int get() = starts.size

    /** Index of the chapter containing paragraph [para]. */
    fun at(para: Int): Int {
        var lo = 0; var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= para) lo = mid + 1 else hi = mid - 1
        }
        return (hi).coerceAtLeast(0)
    }

    fun title(i: Int): String = labels.getOrElse(i) { "Chapter ${i + 1}" }
    fun depth(i: Int): Int = depths.getOrElse(i) { 0 }
    fun navigationId(i: Int): String = navigationIds.getOrElse(i) { "" }

    /** First paragraph (inclusive) of chapter [i]; -1 when none. */
    fun start(i: Int): Int = starts.getOrNull(i) ?: -1

    /** First paragraph of the following chapter, used to detect "last paragraph of chapter". */
    fun nextStart(i: Int): Int = starts.getOrNull(i + 1) ?: Int.MAX_VALUE

    companion object {
        fun from(book: Book, paraCount: Int): ChapterSpan {
            val progress = chapterProgress(book, paraCount)
            return ChapterSpan(progress.starts, progress.labels, progress.depths, progress.navigationIds)
        }
    }
}
