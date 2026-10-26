package id.gerry.gbook

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubNavigationParserTest {

    @Test
    fun parsesNestedEpub2NcxInAuthoredOrder() {
        val points = EpubNavigationParser.parseNcx(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="part"><navLabel><text>Part One</text></navLabel><content src="part.xhtml"/>
                  <navPoint id="one"><navLabel><text>First Chapter</text></navLabel><content src="chapter.xhtml#one"/></navPoint>
                  <navPoint id="two"><navLabel><text>Second Chapter</text></navLabel><content src="chapter.xhtml#two"/></navPoint>
                </navPoint>
              </navMap>
            </ncx>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                EpubNavigationPoint("Part One", "part.xhtml", 0, "0"),
                EpubNavigationPoint("First Chapter", "chapter.xhtml#one", 1, "0/0"),
                EpubNavigationPoint("Second Chapter", "chapter.xhtml#two", 1, "0/1")
            ),
            points
        )
    }

    @Test
    fun parsesNestedEpub3NavigationDocumentWithDoctype() {
        val points = EpubNavigationParser.parseNavDocument(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol>
                <li><a href="part.xhtml">Part One</a><ol>
                  <li><a href="chapter.xhtml#one">First Chapter</a></li>
                  <li><a href="chapter.xhtml#two">Second Chapter</a></li>
                </ol></li>
              </ol></nav></body>
            </html>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                EpubNavigationPoint("Part One", "part.xhtml", 0, "0"),
                EpubNavigationPoint("First Chapter", "chapter.xhtml#one", 1, "0/0"),
                EpubNavigationPoint("Second Chapter", "chapter.xhtml#two", 1, "0/1")
            ),
            points
        )
    }
}
