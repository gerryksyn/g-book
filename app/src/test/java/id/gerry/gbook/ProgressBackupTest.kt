package id.gerry.gbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressBackupTest {

    private val chapters = listOf(
        Chapter("Part One", 0, 39, depth = 0, navigationId = "0"),
        Chapter("Opening", 0, 19, depth = 1, navigationId = "0/0"),
        Chapter("Later", 20, 39, depth = 1, navigationId = "0/1"),
        Chapter("Part Two", 40, 79, depth = 0, navigationId = "1")
    )

    @Test
    fun exportStoresExactHierarchicalIdentityAtSharedAnchor() {
        val parent = book(currentParagraph = 0, currentChapterId = "0")
        val nested = book(id = "nested", currentParagraph = 0, currentChapterId = "0/0")

        val lines = exportProgressCsv(listOf(parent, nested))
            .lineSequence().filter(String::isNotBlank).toList()

        assertTrue(lines.first().contains("active_chapter_id,active_chapter_depth"))
        assertTrue(lines[1].contains(",0,0,Part One,"))
        assertTrue(lines[2].contains(",0/0,1,Opening,"))
    }

    @Test
    fun restoreUsesHierarchyIdToRecoverExactNestedChapter() {
        val csv = """
            book_id,title,author,current_paragraph,current_sentence,paragraph_scroll_offset,paragraph_count,active_chapter_id,active_chapter_depth,active_chapter_title,finished,listening_ms,last_read_at
            book,Book,Author,70,8,14,80,0/1,1,Later,false,1234,5678
        """.trimIndent()

        val result = restoreProgressCsv(csv, listOf(book()))
        val restored = result.books.single()

        assertEquals(1, result.restoredCount)
        assertEquals("0/1", restored.currentChapterId)
        assertEquals(20, restored.currentParagraph)
        assertEquals(8, restored.currentSentence)
        assertEquals(14, restored.paragraphScrollOffset)
    }

    @Test
    fun restoreAcceptsLegacyFlatChapterSchema() {
        val csv = """
            book_id,title,author,current_paragraph,paragraph_count,active_chapter,active_chapter_title,finished,listening_ms,last_read_at
            book,Book,Author,20,80,3,Later,true,9000,10000
        """.trimIndent()

        val restored = restoreProgressCsv(csv, listOf(book())).books.single()

        assertEquals("0/1", restored.currentChapterId)
        assertEquals(20, restored.currentParagraph)
        assertEquals(true, restored.finished)
        assertEquals(9000L, restored.listeningMs)
    }

    @Test
    fun legacyChapterNumberDisambiguatesDuplicateTitles() {
        val duplicateChapters = listOf(
            Chapter("Introduction", 0, 9, navigationId = "0"),
            Chapter("Introduction", 10, 19, navigationId = "1")
        )
        val local = book().copy(chapters = duplicateChapters, paragraphCount = 20)
        val csv = """
            book_id,title,author,current_paragraph,paragraph_count,active_chapter,active_chapter_title,finished,listening_ms,last_read_at
            book,Book,Author,10,20,2,Introduction,false,0,0
        """.trimIndent()

        val restored = restoreProgressCsv(csv, listOf(local)).books.single()

        assertEquals("1", restored.currentChapterId)
        assertEquals(10, restored.currentParagraph)
    }

    private fun book(
        id: String = "book",
        currentParagraph: Int = 0,
        currentChapterId: String? = null
    ) = Book(
        id = id,
        title = "Book",
        author = "Author",
        format = "EPUB",
        textPath = "unused",
        coverPath = null,
        currentParagraph = currentParagraph,
        paragraphCount = 80,
        chapters = chapters,
        currentChapterId = currentChapterId
    )
}
