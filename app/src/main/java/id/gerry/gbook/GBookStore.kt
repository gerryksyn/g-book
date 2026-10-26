package id.gerry.gbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Persistence + import layer for G-Book. Holds the library as JSON in SharedPreferences and the
 * parsed files under [filesDir]/books.
 *
 * Requirement 1 (cover art): import attempts to extract the embedded cover (EPUB cover image, or
 * PDF first page rendered). When there is none locally, [importBookFrom] falls back to the internet
 * (Google Books by title + author) and reports the saved cover path through [onCoverDownloaded],
 * which the caller invokes on the main thread.
 */
class GBookStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("gbook", Context.MODE_PRIVATE)
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- presentation prefs -----------------------------------------------------------

    fun viewIsList(): Boolean = prefs.getString("library_view", "grid") == "list"
    fun saveView(list: Boolean) = prefs.edit().putString("library_view", if (list) "list" else "grid").apply()

    fun sortIsRecent(): Boolean = prefs.getString("library_sort", "recent") != "atoz"
    fun saveSort(recent: Boolean) = prefs.edit().putString("library_sort", if (recent) "recent" else "atoz").apply()

    // ---- reader + speech preferences --------------------------------------------------

    fun readerSettings(): id.gerry.gbook.ui.reader.model.ReaderSettings =
        id.gerry.gbook.ui.reader.model.ReaderSettings(
            textSizeSp = prefs.getFloat("reader_text_size", 23f).nearestQuarter().coerceIn(16f, 44f),
            fontWeight = prefs.getFloat("reader_font_weight", 0f).nearestQuarter().coerceIn(0f, 1f),
            lineSpacing = prefs.getFloat("reader_line_spacing", 1.5f).nearestQuarter().coerceIn(1f, 3f),
            serif = prefs.getBoolean("reader_serif", false),
            centered = prefs.getBoolean("reader_centered", false),
            highContrast = prefs.getBoolean("reader_high_contrast", false)
        )

    fun saveReaderSettings(settings: id.gerry.gbook.ui.reader.model.ReaderSettings) {
        prefs.edit()
            .putFloat("reader_text_size", settings.textSizeSp.nearestQuarter().coerceIn(16f, 44f))
            .putFloat("reader_font_weight", settings.fontWeight.nearestQuarter().coerceIn(0f, 1f))
            .putFloat("reader_line_spacing", settings.lineSpacing.nearestQuarter().coerceIn(1f, 3f))
            .putBoolean("reader_serif", settings.serif)
            .putBoolean("reader_centered", settings.centered)
            .putBoolean("reader_high_contrast", settings.highContrast)
            .apply()
    }

    fun ttsSettings(): id.gerry.gbook.ui.reader.model.TtsSettings =
        id.gerry.gbook.ui.reader.model.TtsSettings(
            rate = prefs.getFloat("tts_rate", 1.0f).nearestQuarter().coerceIn(0.5f, 2.0f),
            pitch = prefs.getFloat("tts_pitch", 1.0f).nearestQuarter().coerceIn(0.5f, 2.0f),
            voiceName = prefs.getString("tts_voice", null)?.takeIf { it.isNotBlank() }
        )

    fun saveTtsSettings(settings: id.gerry.gbook.ui.reader.model.TtsSettings) {
        prefs.edit()
            .putFloat("tts_rate", settings.rate.nearestQuarter().coerceIn(0.5f, 2.0f))
            .putFloat("tts_pitch", settings.pitch.nearestQuarter().coerceIn(0.5f, 2.0f))
            .putString("tts_voice", settings.voiceName.orEmpty())
            .apply()
    }

    // ---- last-read book (for the "Read" bottom-nav tab) ------------------------------

    /** Id of the last book the user opened in the reader, or null. */
    fun lastReadBookId(): String? =
        prefs.getString("last_read_book", null)?.takeIf { it.isNotBlank() }

    fun saveLastReadBook(id: String) =
        prefs.edit().putString("last_read_book", id).apply()

    fun clearLastReadBook() =
        prefs.edit().remove("last_read_book").apply()

    // ---- books CRUD -------------------------------------------------------------------

    fun books(): List<Book> = synchronized(BOOKS_LOCK) { readBooksLocked() }

    private fun readBooksLocked(): List<Book> = runCatching {
        val data = JSONArray(prefs.getString("books", "[]"))
        (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                author = o.optString("author"),
                format = o.optString("format", "Book"),
                textPath = o.getString("textPath"),
                coverPath = o.optString("coverPath").takeIf { it.isNotBlank() && File(it).exists() },
                currentParagraph = o.optInt("currentParagraph"),
                currentSentence = o.optInt("currentSentence", -1),
                paragraphScrollOffset = o.optInt("paragraphScrollOffset", 0).coerceAtLeast(0),
                paragraphCount = o.optInt("paragraphCount", 1),
                finished = o.optBoolean("finished"),
                listeningMs = o.optLong("listeningMs"),
                lastReadAt = o.optLong("lastReadAt"),
                chapters = parseChapters(o.optJSONArray("chapters")),
                currentChapterId = o.optString("currentChapterId").takeIf { it.isNotBlank() }
            )
        }
    }.getOrDefault(emptyList())

    private fun parseChapters(arr: JSONArray?): List<Chapter> =
        if (arr == null) emptyList()
        else (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Chapter(
                o.optString("title", "Chapter ${i + 1}"),
                o.optInt("startParagraph"),
                o.optInt("endParagraph"),
                o.optInt("depth", 0).coerceAtLeast(0),
                o.optString("navigationId")
            )
        }

    fun saveBooks(books: List<Book>) = synchronized(BOOKS_LOCK) {
        writeBooksLocked(books, synchronous = false)
    }

    /**
     * Atomically merge and durably commit an exact TTS cursor. Unlike ordinary UI persistence,
     * this is synchronous because the process may be killed immediately after a sentence starts.
     */
    fun persistTtsPosition(
        fallback: Book,
        currentParagraph: Int,
        currentSentence: Int,
        paragraphCount: Int,
        savedAt: Long = System.currentTimeMillis()
    ): Book = synchronized(BOOKS_LOCK) {
        val allBooks = readBooksLocked()
        val latest = allBooks.firstOrNull { it.id == fallback.id } ?: fallback
        val updated = latest.copy(
            currentParagraph = currentParagraph.coerceAtLeast(0),
            currentSentence = currentSentence,
            paragraphScrollOffset = if (latest.currentParagraph == currentParagraph) {
                latest.paragraphScrollOffset
            } else 0,
            paragraphCount = paragraphCount.coerceAtLeast(1),
            finished = false,
            lastReadAt = savedAt
        )
        val merged = if (allBooks.any { it.id == updated.id }) {
            allBooks.map { if (it.id == updated.id) updated else it }
        } else {
            allBooks + updated
        }
        check(writeBooksLocked(merged, synchronous = true)) {
            "Unable to persist TTS reading position"
        }
        updated
    }

    private fun writeBooksLocked(books: List<Book>, synchronous: Boolean): Boolean {
        val array = JSONArray()
        books.forEach { b ->
            array.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("author", b.author); put("format", b.format)
                put("textPath", b.textPath); put("coverPath", b.coverPath ?: "")
                put("currentParagraph", b.currentParagraph)
                put("currentSentence", b.currentSentence)
                put("paragraphScrollOffset", b.paragraphScrollOffset.coerceAtLeast(0))
                put("paragraphCount", b.paragraphCount)
                put("finished", b.finished); put("listeningMs", b.listeningMs); put("lastReadAt", b.lastReadAt)
                put("currentChapterId", b.currentChapterId.orEmpty())
                put("chapters", JSONArray().apply {
                    b.chapters.forEach { c ->
                        put(JSONObject().apply {
                            put("title", c.title)
                            put("startParagraph", c.startParagraph)
                            put("endParagraph", c.endParagraph)
                            put("depth", c.depth)
                            put("navigationId", c.navigationId)
                        })
                    }
                })
            })
        }
        val editor = prefs.edit().putString("books", array.toString())
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    fun text(book: Book): String =
        runCatching { File(book.textPath).readText() }.getOrDefault("This book's text could not be loaded.")

    /** Deletes a book's backing files. */
    fun remove(book: Book) {
        runCatching { File(book.textPath).delete() }
        book.coverPath?.let { runCatching { File(it).delete() } }
    }

    fun recordListening(bookId: String, startedAt: Long, endedAt: Long) {
        if (startedAt <= 0 || endedAt <= startedAt) return
        val zone = ZoneId.systemDefault()
        var cursor = startedAt
        val days = prefs.getStringSet("reading_days", emptySet()).orEmpty().toMutableSet()
        val editor = prefs.edit()
        while (cursor < endedAt) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(endedAt, nextMidnight)
            val key = "listen_$date"
            editor.putLong(key, prefs.getLong(key, 0) + (segmentEnd - cursor))
            days.add(date.toString())
            cursor = segmentEnd
        }
        editor.putStringSet("reading_days", days).apply()
    }

    fun dayMillis(date: LocalDate): Long = prefs.getLong("listen_${date}", 0)
    fun readingDays(): Set<String> = prefs.getStringSet("reading_days", emptySet()).orEmpty()

    // ---- stats (chunk 4) ---------------------------------------------------------------

    /** TTS listening time recorded so far today, in milliseconds. */
    fun todayListeningMillis(): Long = dayMillis(LocalDate.now())

    /** Number of distinct days the user listened in the calendar month containing [month]. */
    fun readingDaysInMonth(month: LocalDate): Int =
        readingDays().count { it.startsWith(month.toString().take(7)) }

    /** Calendar dates ("yyyy-MM-dd") in the month of [month] on which the user listened. */
    fun readingDayDatesInMonth(month: LocalDate): List<String> =
        readingDays().filter { it.startsWith(month.toString().take(7)) }.sorted()

    /**
     * Records [deltaMs] of TTS listening for [book] ending at [nowMillis], splitting it across
     * midnight into per-day buckets exactly like [recordListening] (which updates the "Today"
     * and "Monthly view" aggregates), then returns a copy of the book whose own independent
     * [listeningMs] total is bumped and [lastReadAt] refreshed. The caller persists the result.
     */
    fun recordListeningSession(book: Book, deltaMs: Long, nowMillis: Long): Book {
        if (deltaMs <= 0) return book
        recordListening(book.id, (nowMillis - deltaMs).coerceAtLeast(0), nowMillis)
        return book.copy(listeningMs = book.listeningMs + deltaMs, lastReadAt = nowMillis)
    }

    // ---- import -----------------------------------------------------------------------

    /**
     * Imports a picked file and returns the new [Book]. When the file carried no local cover, an
     * internet fetch is started; on success [onCoverDownloaded] fires on the main thread.
     */
    fun importBookFrom(uri: Uri, onCoverDownloaded: (Book) -> Unit = {}): Book? {
        val id = UUID.randomUUID().toString()
        val displayName = queryName(context, uri)
        val lower = displayName.lowercase()
        val ext = lower.substringAfterLast('.', "book").lowercase()
        if (ext !in IMPORTABLE_EXTS) return null

        val source = File(booksDir, "$id.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        } catch (_: Exception) {
            return null
        }

        val parsed: ParsedBook = when (ext) {
            "epub" -> parseEpub(source, id)
            "pdf" -> parsePdf(source, id)
            "html", "htm", "xhtml" -> ParsedText(stripHtml(source.readText()))
            else -> ParsedText(source.readText())
        }

        val text = normalizeText(parsed.rawText).ifBlank { "G-Book could not extract readable text from this file." }
        val paragraphCount = splitParagraphs(text).size.coerceAtLeast(1)
        val textFile = File(booksDir, "$id.txt").apply { writeText(text) }

        val book = Book(
            id = id,
            title = parsed.title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.').replace('_', ' '),
            author = parsed.author.orEmpty(),
            format = ext.uppercase(),
            textPath = textFile.absolutePath,
            coverPath = parsed.coverPath,
            paragraphCount = paragraphCount,
            chapters = parsed.chapters
        )

        if (book.coverPath == null) {
            val target = coverFileFor(id)
            fetchCover(book.title, book.author, target) { downloaded ->
                if (downloaded != null) {
                    mainHandler.post { onCoverDownloaded(book.copy(coverPath = downloaded)) }
                }
            }
        }
        return book
    }

    // ---- EPUB -------------------------------------------------------------------------

    private fun parseEpub(file: File, id: String): ParsedEbook {
        var title: String? = null
        var author: String? = null
        var cover: String? = null
        val html = mutableListOf<String>()
        val chapters = mutableListOf<Chapter>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            entries.firstOrNull { it.name.endsWith(".opf", true) }?.let { opfEntry ->
                val opf = zip.getInputStream(opfEntry).bufferedReader().readText()
                title = Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>", RegexOption.IGNORE_CASE)
                    .find(opf)?.groupValues?.get(1)?.let(::stripHtml)
                author = Regex("<dc:creator[^>]*>([\\s\\S]*?)</dc:creator>", RegexOption.IGNORE_CASE)
                    .find(opf)?.groupValues?.get(1)?.let(::stripHtml)

                // Resolve the cover the way real EPUBs declare it: the OPF <metadata>
                // carries <meta name="cover" content="<manifest-id>"/> which maps, through
                // <manifest><item id=... href=... media-type=image/.../>, to the image file.
                // The item href is relative to the folder the OPF lives in inside the zip.
                val coverImageEntry = resolveOpfCover(zip, entries, opfEntry, opf)
                if (coverImageEntry != null) {
                    cover = extractCover(zip, coverImageEntry, id)
                }

                // EPUB reading order is defined by the OPF spine. Preserve that order and make
                // one selectable chapter for every readable spine document in this imported
                // book; the reader picker therefore reflects the book itself, not a fixed grid.
                val orderedDocuments = resolveSpineDocuments(entries, opfEntry, opf)
                val documentStarts = mutableMapOf<String, Int>()
                val documentSources = mutableMapOf<String, String>()
                var paragraphStart = 0
                orderedDocuments.forEachIndexed { index, entry ->
                    val sourceHtml = zip.getInputStream(entry).bufferedReader().readText()
                    val chapterText = normalizeText(stripHtml(sourceHtml))
                    if (chapterText.isNotBlank()) {
                        val paragraphLength = splitParagraphs(chapterText).size.coerceAtLeast(1)
                        documentStarts[entry.name] = paragraphStart
                        documentSources[entry.name] = sourceHtml
                        html += chapterText
                        paragraphStart += paragraphLength
                    }
                }

                val navigation = resolveEpubNavigation(zip, entries, opfEntry, opf)
                chapters += navigation.mapNotNull { point ->
                    val target = resolveNavigationTarget(point.href, documentStarts, documentSources)
                        ?: return@mapNotNull null
                    Chapter(point.title, target, target, point.depth, point.navigationId)
                }.let { points ->
                    points.mapIndexed { index, chapter ->
                        val nextStart = points.drop(index + 1)
                            .firstOrNull { it.startParagraph > chapter.startParagraph }
                            ?.startParagraph
                            ?: paragraphStart
                        chapter.copy(endParagraph = (nextStart - 1).coerceAtLeast(chapter.startParagraph))
                    }
                }

                // A malformed/missing TOC still gets useful flat spine navigation.
                if (chapters.isEmpty()) {
                    orderedDocuments.forEachIndexed { index, entry ->
                        val start = documentStarts[entry.name] ?: return@forEachIndexed
                        val next = orderedDocuments.drop(index + 1)
                            .firstNotNullOfOrNull { documentStarts[it.name] }
                            ?: paragraphStart
                        chapters += Chapter(
                            title = extractChapterTitle(documentSources[entry.name].orEmpty(), index),
                            startParagraph = start,
                            endParagraph = (next - 1).coerceAtLeast(start),
                            navigationId = "spine:$index"
                        )
                    }
                }
            }

            // Fallback: no OPF or the OPF didn't name a cover — scan for an image whose
            // ZIP path looks like a cover (filename contains "cover"), then any image item
            // whose path mentions cover/front/title.
            if (cover == null) {
                val ofDir = opfDir(zip, entries)
                val imageEntry = entries.firstOrNull {
                    !it.isDirectory && COVER_IMAGE_EXTS.any { e -> it.name.endsWith(e, true) } &&
                        (it.name.contains("cover", true) ||
                            (!ofDir.isNullOrEmpty() && ofDir.lowercase() !in it.name.lowercase() &&
                                requiresCoverHeuristic(it.name)))
                }
                if (imageEntry != null) cover = extractCover(zip, imageEntry, id)
            }

            // Malformed EPUB fallback: use archive order only when no readable OPF spine was
            // available. These entries still become real selectable chapters for this book.
            if (html.isEmpty()) {
                var paragraphStart = 0
                entries.filter(::isReadableEpubDocument).forEachIndexed { index, entry ->
                    val sourceHtml = zip.getInputStream(entry).bufferedReader().readText()
                    val chapterText = normalizeText(stripHtml(sourceHtml))
                    if (chapterText.isNotBlank()) {
                        val paragraphLength = splitParagraphs(chapterText).size.coerceAtLeast(1)
                        html += chapterText
                        chapters += Chapter(
                            extractChapterTitle(sourceHtml, index),
                            paragraphStart,
                            paragraphStart + paragraphLength - 1,
                            navigationId = "archive:$index"
                        )
                        paragraphStart += paragraphLength
                    }
                }
            }
        }
        return ParsedEbook(html.joinToString("\n\n"), title, author, cover, chapters)
    }

    /** Resolve OPF manifest + spine into the exact readable document order of this EPUB. */
    private fun resolveSpineDocuments(
        entries: List<ZipEntry>,
        opfEntry: ZipEntry,
        opf: String
    ): List<ZipEntry> {
        val opfFolder = opfEntry.name.substringBeforeLast('/', missingDelimiterValue = "").let {
            if (it.isEmpty()) "" else "$it/"
        }
        val entriesByName = entries.associateBy { it.name }
        val manifest = mutableMapOf<String, Pair<String, String>>()
        Regex("<item\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val attrs = match.groupValues[1]
            val itemId = attribute(attrs, "id") ?: return@forEach
            val href = attribute(attrs, "href") ?: return@forEach
            val properties = attribute(attrs, "properties").orEmpty()
            manifest[itemId] = href to properties
        }
        return Regex("<itemref\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
            .findAll(opf)
            .mapNotNull { match ->
                val itemId = attribute(match.groupValues[1], "idref") ?: return@mapNotNull null
                val item = manifest[itemId] ?: return@mapNotNull null
                if (item.second.split(Regex("\\s+")).any { it.equals("nav", true) }) return@mapNotNull null
                val path = resolveZipPath(opfFolder, item.first.substringBefore('#'))
                (entriesByName[path] ?: entriesByName[path.removePrefix("./")])
                    ?.takeIf(::isReadableEpubDocument)
            }
            .distinctBy { it.name }
            .toList()
    }

    /** Resolve and parse the EPUB-authored TOC: EPUB 3 nav first, then EPUB 2 NCX. */
    private fun resolveEpubNavigation(
        zip: ZipFile,
        entries: List<ZipEntry>,
        opfEntry: ZipEntry,
        opf: String
    ): List<EpubNavigationPoint> {
        val opfFolder = opfEntry.name.substringBeforeLast('/', missingDelimiterValue = "").let {
            if (it.isEmpty()) "" else "$it/"
        }
        val entriesByName = entries.associateBy { it.name }
        val manifest = mutableMapOf<String, Triple<String, String, String>>()
        Regex("<item\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val attrs = match.groupValues[1]
            val itemId = attribute(attrs, "id") ?: return@forEach
            manifest[itemId] = Triple(
                attribute(attrs, "href").orEmpty(),
                attribute(attrs, "media-type").orEmpty(),
                attribute(attrs, "properties").orEmpty()
            )
        }

        fun entryFor(href: String): ZipEntry? {
            val path = resolveZipPath(opfFolder, decodeEpubHref(href).substringBefore('#'))
            return entriesByName[path] ?: entriesByName[path.removePrefix("./")]
        }

        val navItem = manifest.values.firstOrNull { item ->
            item.third.split(Regex("\\s+")).any { it.equals("nav", true) }
        }
        val navEntry = navItem?.first?.let(::entryFor)
            ?: entries.firstOrNull { it.name.endsWith("nav.xhtml", true) }
        if (navEntry != null) {
            val points = zip.getInputStream(navEntry).bufferedReader().use {
                EpubNavigationParser.parseNavDocument(it.readText())
            }
            if (points.isNotEmpty()) return points.map { point ->
                point.copy(href = resolveZipPath(navEntry.name.substringBeforeLast('/', "").let {
                    if (it.isEmpty()) "" else "$it/"
                }, decodeEpubHref(point.href)))
            }
        }

        val ncxId = Regex("<spine\\b([^>]*)>", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { attribute(it, "toc") }
        val ncxEntry = ncxId?.let { manifest[it]?.first }?.let(::entryFor)
            ?: manifest.values.firstOrNull { it.second.equals("application/x-dtbncx+xml", true) }
                ?.first?.let(::entryFor)
            ?: entries.firstOrNull { it.name.endsWith("toc.ncx", true) }
        if (ncxEntry != null) {
            return zip.getInputStream(ncxEntry).bufferedReader().use {
                EpubNavigationParser.parseNcx(it.readText())
            }.map { point ->
                point.copy(href = resolveZipPath(ncxEntry.name.substringBeforeLast('/', "").let {
                    if (it.isEmpty()) "" else "$it/"
                }, decodeEpubHref(point.href)))
            }
        }
        return emptyList()
    }

    private fun resolveNavigationTarget(
        href: String,
        documentStarts: Map<String, Int>,
        documentSources: Map<String, String>
    ): Int? {
        val decoded = decodeEpubHref(href)
        val path = decoded.substringBefore('#').removePrefix("./")
        val documentPath = documentStarts.keys.firstOrNull { it == path }
            ?: documentStarts.keys.firstOrNull { it.equals(path, true) }
            ?: return null
        val documentStart = documentStarts.getValue(documentPath)
        val fragment = decoded.substringAfter('#', "").takeIf { it.isNotBlank() } ?: return documentStart
        val source = documentSources[documentPath].orEmpty()
        val anchor = Regex(
            "<[^>]+\\b(?:id|name)\\s*=\\s*[\"']${Regex.escape(fragment)}[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        ).find(source) ?: return documentStart
        val paragraphsBeforeAnchor = splitParagraphs(normalizeText(stripHtml(source.substring(0, anchor.range.first)))).size
        return documentStart + paragraphsBeforeAnchor
    }

    private fun decodeEpubHref(href: String): String = runCatching {
        java.net.URLDecoder.decode(href.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(href).replace("&amp;", "&")

    private fun attribute(attrs: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)

    private fun isReadableEpubDocument(entry: ZipEntry): Boolean =
        !entry.isDirectory && (entry.name.endsWith(".xhtml", true) ||
            entry.name.endsWith(".html", true) || entry.name.endsWith(".htm", true))

    private fun extractChapterTitle(html: String, index: Int): String {
        val heading = Regex("<(h[1-3]|title)\\b[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.let(::stripHtml)?.trim()
        return heading?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
    }

    /**
     * Returns the ZIP entry holding the EPUB cover image per the OPF manifest, or null.
     * Honors <meta name="cover" content="...id"/> -> <item id> href; otherwise falls back to
     * whichever image manifest item looks like a cover.
     */
    private fun resolveOpfCover(
        zip: ZipFile,
        entries: List<ZipEntry>,
        opfEntry: ZipEntry,
        opf: String
    ): ZipEntry? {
        val manifestId = Regex("<meta[^>]*name=[\"']cover[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)
            ?: Regex("<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*name=[\"']cover[\"']", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)

        // Directory of the OPF within the zip, used to resolve relative hrefs.
        val opfFolder = opfEntry.name.substringBeforeLast('/', missingDelimiterValue = "").let {
            if (it.isEmpty()) "" else "$it/"
        }
        val byName = entries.associateBy { it.name }

        // Preferred: a manifest <item> whose href targets an image; track (href-lower, zipEntry).
        val imageItems = mutableListOf<Pair<String, ZipEntry>>()
        Regex("<item\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val attrs = m.groupValues[1]
            val iid = Regex("id=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            val media = Regex("media-type=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            if (iid == null || href == null) return@forEach
            if (!(media?.startsWith("image/") == true) &&
                COVER_IMAGE_EXTS.none { href.lowercase().endsWith(it) }) return@forEach
            val resolvedPath = resolveZipPath(opfFolder, href)
            val entry = byName[resolvedPath] ?: byName[resolvedPath.removePrefix("./")]
            if (entry == null) return@forEach
            if (manifestId != null && iid.equals(manifestId, ignoreCase = true)) {
                // Exact OPF-declared cover wins immediately.
                return entry
            }
            imageItems += href.lowercase() to entry
        }

        if (imageItems.isEmpty()) return null

        // No exact manifest-declared cover: prefer paths suggesting a cover.
        val hint = Regex("(cover|front|title|jacket)", RegexOption.IGNORE_CASE)
        return imageItems.firstOrNull { hint.containsMatchIn(it.first) }?.second
            ?: imageItems.first().second
    }

    /** Directory of the first OPF entry within the zip, with trailing slash, or null if none. */
    private fun opfDir(zip: ZipFile, entries: List<ZipEntry>): String? =
        entries.firstOrNull { it.name.endsWith(".opf", true) }?.name
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }

    private fun requiresCoverHeuristic(name: String): Boolean =
        COVER_IMAGE_EXTS.any { name.lowercase().endsWith(it) } &&
            (name.contains("cover", true) || name.contains("front", true) ||
                name.contains("jacket", true) || name.contains("title", true))

    /** Joins an OPF-relative href to the OPF's folder, normalizing ../ and ./. */
    private fun resolveZipPath(folder: String, href: String): String {
        val combined = folder + href.replace('\\', '/')
        val parts = ArrayDeque<String>()
        combined.split('/').forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Pulls an image entry out of the zip, jpeg-converts it, and returns the saved cover path. */
    private fun extractCover(zip: ZipFile, entry: java.util.zip.ZipEntry, id: String): String? {
        val raw = File(booksDir, "$id-cover.${entry.name.substringAfterLast('.')}")
        return try {
            zip.getInputStream(entry).use { input -> raw.outputStream().use { input.copyTo(it) } }
            val jpeg = jpegConvert(raw)
            if (jpeg != null) { raw.delete(); jpeg.absolutePath }
            else raw.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // ---- PDF --------------------------------------------------------------------------

    private fun parsePdf(file: File, id: String): ParsedText {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        val text = runCatching { PDDocument.load(file).use { PDFTextStripper().getText(it) } }.getOrDefault("")
        var cover: String? = null
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount > 0) renderer.openPage(0).use { page ->
                        val width = 720
                        val height = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(960)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = coverFileFor(id)
                        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        bitmap.recycle()
                        cover = out.absolutePath
                    }
                }
            }
        }
        return ParsedText(text, coverPath = cover)
    }

    // ---- covers -----------------------------------------------------------------------

    private fun coverFileFor(id: String): File = File(booksDir, "$id-cover.jpg")

    /** Decodes any image file and rewrites as a compact JPEG; null when undecodable. */
    private fun jpegConvert(file: File): File? = runCatching {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val out = File(booksDir, "${UUID.randomUUID()}-cover.jpg")
        out.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        src.recycle()
        out
    }.getOrNull()

    /** Google Books cover lookup by title+author. Callback on a background thread. */
    private fun fetchCoverUrl(title: String, author: String, onResult: (String?) -> Unit) {
        val query = "intitle:${title}" + (if (author.isNotBlank()) "+inauthor:$author" else "")
        Thread {
            val url = "https://www.googleapis.com/books/v1/volumes?q=${Uri.encode(query)}&maxResults=1&printType=books"
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    Regex("\"thumbnail\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                        ?.replace("\\u0026", "&")
                        ?.replace("http://", "https://")
                        ?.replace("&edge=curl", "")
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            onResult(result)
        }.start()
    }

    /** Fetches + downloads the cover into [target]; reports its absolute path or null. */
    private fun fetchCover(title: String, author: String, target: File, onDone: (String?) -> Unit) {
        fetchCoverUrl(title, author) { thumbUrl ->
            val path = thumbUrl?.let { downloadCover(it, target) }
            onDone(path)
        }
    }

    private fun downloadCover(url: String, target: File): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "G-Book/1.0")
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 512) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            if (target.exists() && target.length() > 100) target.absolutePath else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val PREFS = "gbook"
        /** Coordinates whole-library reads/writes made by the activity and playback service. */
        val BOOKS_LOCK = Any()
        val IMPORTABLE_EXTS = setOf("epub", "pdf", "txt", "html", "htm", "xhtml")
        val COVER_IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp")
    }
}

/** Normalizes persisted slider values to the strict half-step contract used by the UI. */
private fun Float.nearestQuarter(): Float = kotlin.math.round(this * 4f) / 4f
