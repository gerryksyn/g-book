package id.gerry.gbook

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One entry from an EPUB 2 NCX or EPUB 3 navigation document. */
internal data class EpubNavigationPoint(
    val title: String,
    val href: String,
    val depth: Int,
    /** Stable authored hierarchy path, for example `0/2/1`. */
    val navigationId: String = ""
)

/** Parses the publication-authored table of contents while preserving its nesting order. */
internal object EpubNavigationParser {

    fun parseNcx(xml: String): List<EpubNavigationPoint> {
        val document = parseXml(xml) ?: return emptyList()
        val navMap = document.documentElement.descendants()
            .firstOrNull { it.localTagName().equals("navMap", ignoreCase = true) }
            ?: return emptyList()
        return buildList {
            navMap.childElements("navPoint").forEachIndexed { index, point ->
                parseNavPoint(point, 0, index.toString(), this)
            }
        }
    }

    fun parseNavDocument(xhtml: String): List<EpubNavigationPoint> {
        val document = parseXml(xhtml) ?: return emptyList()
        val toc = document.documentElement.descendants()
            .filter { it.localTagName().equals("nav", ignoreCase = true) }
            .firstOrNull { nav ->
                nav.attributeByLocalName("type")
                    .orEmpty()
                    .split(Regex("\\s+"))
                    .any { it.equals("toc", ignoreCase = true) } ||
                    nav.getAttribute("role").equals("doc-toc", ignoreCase = true)
            } ?: return emptyList()
        val rootList = toc.childElements("ol").firstOrNull()
            ?: toc.descendants().firstOrNull { it.localTagName().equals("ol", ignoreCase = true) }
            ?: return emptyList()
        return buildList { parseOrderedList(rootList, 0, "", this) }
    }

    private fun parseNavPoint(
        navPoint: Element,
        depth: Int,
        navigationId: String,
        output: MutableList<EpubNavigationPoint>
    ) {
        val label = navPoint.childElements("navLabel").firstOrNull()
            ?.descendants()
            ?.firstOrNull { it.localTagName().equals("text", ignoreCase = true) }
            ?.textContent
            ?.cleanLabel()
        val href = navPoint.childElements("content").firstOrNull()
            ?.getAttribute("src")
            ?.trim()
        if (!label.isNullOrBlank() && !href.isNullOrBlank()) {
            output += EpubNavigationPoint(label, href, depth, navigationId)
        }
        navPoint.childElements("navPoint").forEachIndexed { index, child ->
            parseNavPoint(child, depth + 1, "$navigationId/$index", output)
        }
    }

    private fun parseOrderedList(
        orderedList: Element,
        depth: Int,
        parentId: String,
        output: MutableList<EpubNavigationPoint>
    ) {
        orderedList.childElements("li").forEachIndexed { index, item ->
            val navigationId = if (parentId.isEmpty()) index.toString() else "$parentId/$index"
            val link = item.children().firstOrNull {
                it.localTagName().equals("a", ignoreCase = true) && it.getAttribute("href").isNotBlank()
            }
            val labelElement = link ?: item.children().firstOrNull {
                it.localTagName().equals("span", ignoreCase = true)
            }
            val label = labelElement?.textContent?.cleanLabel()
            val href = link?.getAttribute("href")?.trim()
            if (!label.isNullOrBlank() && !href.isNullOrBlank()) {
                output += EpubNavigationPoint(label, href, depth, navigationId)
            }
            item.childElements("ol").forEach { parseOrderedList(it, depth + 1, navigationId, output) }
        }
    }

    private fun parseXml(value: String) = runCatching {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    private fun Element.children(): List<Element> = buildList {
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element) add(node)
            node = node.nextSibling
        }
    }

    private fun Element.childElements(name: String): List<Element> =
        children().filter { it.localTagName().equals(name, ignoreCase = true) }

    private fun Element.descendants(): Sequence<Element> = sequence {
        yield(this@descendants)
        children().forEach { yieldAll(it.descendants()) }
    }

    private fun Element.localTagName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.attributeByLocalName(name: String): String? {
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if ((attribute.localName ?: attribute.nodeName.substringAfter(':')).equals(name, ignoreCase = true)) {
                return attribute.nodeValue
            }
        }
        return null
    }

    private fun String.cleanLabel(): String = replace(Regex("\\s+"), " ").trim()
}
