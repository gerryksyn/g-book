package id.gerry.gbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterProgressTest {

    private val hierarchicalBook = Book(
        id = "book",
        title = "Nested book",
        author = "Author",
        format = "EPUB",
        textPath = "unused",
        coverPath = null,
        paragraphCount = 100,
        chapters = listOf(
            Chapter("Part One", 0, 49, depth = 0),
            Chapter("Section 1.1", 0, 24, depth = 1),
            Chapter("Section 1.2", 25, 49, depth = 1),
            Chapter("Part Two", 50, 99, depth = 0),
            Chapter("Section 2.1", 50, 99, depth = 1)
        )
    )

    @Test
    fun countsEveryMainChapterAndNestedSubchapter() {
        val map = chapterProgress(hierarchicalBook)

        assertEquals(5, map.count)
        assertEquals(listOf(0, 1, 1, 0, 1), map.depths.toList())
    }

    @Test
    fun currentIndexUsesDeepestAuthoredEntryAtSharedTarget() {
        val beginning = readingProgress(hierarchicalBook, currentParagraph = 0)
        val secondSection = readingProgress(hierarchicalBook, currentParagraph = 25)
        val secondPart = readingProgress(hierarchicalBook, currentParagraph = 50)

        assertEquals(1, beginning.chapterIndex)
        assertEquals("Section 1.1", beginning.chapterTitle)
        assertEquals(2, secondSection.chapterIndex)
        assertEquals(4, secondPart.chapterIndex)
        assertEquals(5, secondPart.chapterCount)
    }

    @Test
    fun overallProgressUsesHierarchicalChapterTotalAndReachesOneHundredWhenFinished() {
        val first = readingProgress(hierarchicalBook, currentParagraph = 0)
        val middle = readingProgress(hierarchicalBook, currentParagraph = 50)
        val finished = readingProgress(hierarchicalBook, currentParagraph = 99, finished = true)

        assertEquals(20, first.percent)
        assertEquals(80, middle.percent)
        assertTrue(middle.fraction > first.fraction)
        assertEquals(100, finished.percent)
        assertEquals(1f, finished.fraction)
    }
}
