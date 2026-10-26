package id.gerry.gbook

import android.text.Html

// ---- Parsed import results --------------------------------------------------

/** Generic result of parsing an imported file's content. */
internal sealed class ParsedBook {
    abstract val rawText: String
    abstract val title: String?
    abstract val author: String?
    abstract val coverPath: String?
    open val chapters: List<Chapter> = emptyList()
}

/** Flat file (TXT/HTML) or PDF result. */
internal data class ParsedText(
    override val rawText: String,
    override val title: String? = null,
    override val author: String? = null,
    override val coverPath: String? = null
) : ParsedBook()

/** Structured EPUB result with metadata + cover. */
internal data class ParsedEbook(
    override val rawText: String,
    override val title: String?,
    override val author: String?,
    override val coverPath: String? = null,
    override val chapters: List<Chapter> = emptyList()
) : ParsedBook()

// ---- shared text utilities ---------------------------------------------------

/** Splits running prose into readable paragraphs (blank-line or sentence aware). */
internal fun splitParagraphs(text: String): List<String> =
    text.split(Regex("\\n\\s*\\n|(?<=[.!?])\\s+(?=[A-Z])"))
        .map { it.trim() }
        .filter { it.length > 1 }

internal fun normalizeText(text: String): String = text
    .replace("\u00A0", " ")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

/** Strips HTML tags/Script/Style down to readable plain text. */
internal fun stripHtml(value: String): String = Html.fromHtml(
    value
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("</(p|div|h[1-6]|li|blockquote|br)>", RegexOption.IGNORE_CASE), "$0\n\n"),
    Html.FROM_HTML_MODE_LEGACY
).toString().trim()

internal fun queryName(context: android.content.Context, uri: android.net.Uri): String {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) return it.getString(0) }
    return uri.lastPathSegment ?: "Imported book"
}

internal fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "${ms / 1000} sec"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
