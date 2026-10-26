package id.gerry.gbook.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceIndexTest {

    @Test
    fun chapterHeadingAndBodyAreConsecutivePlaybackSentences() {
        val index = buildSentenceIndex(
            listOf(
                "Chapter One",
                "The opening sentence. The next sentence continues without a chapter stop."
            )
        )

        assertEquals(0, index.firstSentenceAtOrAfter(0))
        assertEquals("Chapter One", index.sentences[0].text)
        assertEquals("The opening sentence.", index.sentences[1].text)
        assertEquals("The next sentence continues without a chapter stop.", index.sentences[2].text)
    }

    @Test
    fun chapterSeekSkipsEmptyParagraphsButKeepsExactNextHeading() {
        val index = buildSentenceIndex(listOf("Previous text.", "", "Nested Heading", "Body text."))

        assertEquals(1, index.firstSentenceAtOrAfter(1))
        assertEquals("Nested Heading", index.sentences[1].text)
        assertEquals(2, index.sentences[1].paraIndex)
    }
}
