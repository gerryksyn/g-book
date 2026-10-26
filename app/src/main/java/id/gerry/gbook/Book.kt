package id.gerry.gbook

/**
 * Single exported book model. Immutable data class persisted as JSON via GBookStore.
 * Faithful to the v0.3.0 monolith (id.gerry.gbook.Book).
 *
 * @property chapters Optional list of chapter titles mapped to paragraph indexes, used for
 *   reader chapter navigation. When non-empty, [currentChapterIndex] can be derived.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val textPath: String,
    val coverPath: String?,
    val currentParagraph: Int = 0,
    /** Flat sentence index used by TTS; -1 means an older save without sentence precision. */
    val currentSentence: Int = -1,
    /** Pixel offset of [currentParagraph] from the top of the reader viewport. */
    val paragraphScrollOffset: Int = 0,
    val paragraphCount: Int = 1,
    val finished: Boolean = false,
    val listeningMs: Long = 0L,
    val lastReadAt: Long = 0L,
    val chapters: List<Chapter> = emptyList(),
    /** Last explicitly selected authored TOC entry; disambiguates shared parent/child anchors. */
    val currentChapterId: String? = null
) {
    /** 0..1 progress from the application's shared reading-progress calculation. */
    val progress: Float
        get() = readingProgress(this).fraction

    /** Display percentage from the same calculation used by Reader, Stats, Share and playback. */
    val progressPercent: Int
        get() = readingProgress(this).percent
}

/** Reader navigation entry, including its authored depth in the EPUB table of contents. */
data class Chapter(
    val title: String,
    val startParagraph: Int,
    val endParagraph: Int,
    val depth: Int = 0,
    /** Stable hierarchy path from the EPUB navigation document, or a fallback source id. */
    val navigationId: String = ""
) {
    val isSingleParagraph: Boolean get() = endParagraph <= startParagraph
}
