package id.gerry.gbook.ui.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.gerry.gbook.Book
import id.gerry.gbook.readingProgress
import id.gerry.gbook.GBookStore
import id.gerry.gbook.splitParagraphs
import id.gerry.gbook.ui.reader.model.ReaderSettings
import id.gerry.gbook.ui.rememberLightHapticAction
import id.gerry.gbook.ui.reader.model.TtsSettings
import id.gerry.gbook.ui.share.shareQuote
import id.gerry.gbook.ui.theme.GBookMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reading + listening surface.
 *
 * Layout: the whole book is presented as one continuously scrolling column (LazyColumn) so the
 * visible paragraphs are the only ones composed at a time — a very long book stays flat in memory.
 *
 * Interaction (chunk-2 fixes):
 *  - A SINGLE tap anywhere on the reading view toggles the chrome (system status bar, navigation
 *    bar, top header, bottom TTS player). It is recognised by its own tap detector on the page
 *    background, which never consumes vertical drags, so scrolling is unaffected.
 *  - A DOUBLE tap on any sentence starts TTS from that exact sentence; TTS then advances sentence
 *    by sentence until paused. Single vs double tap are discriminated by [detectTapGestures], so
 *    the two never fire for the same gesture.
 *
 * Font scaling: reading text is drawn at a fixed size that ignores the device's dynamic system
 * font scale (accessibility "font size") so the layout never distorts. We rescale `sp` by the
 * inverse of the local font scale, yielding a constant physical size on every device setting.
 */
@Composable
internal fun ReaderScreen(
    sourceBook: Book,
    store: GBookStore,
    onProgressChange: (Book) -> Unit,
    onSystemUi: (overlayVisible: Boolean) -> Unit,
    onExitRequest: (updated: Book) -> Unit
) {
    // TTS may have persisted a newer cursor while the activity was stopped. A reopened reader
    // must initialize from that durable row, never from the activity's older in-memory snapshot.
    val book = remember(sourceBook.id) {
        store.books().firstOrNull { it.id == sourceBook.id } ?: sourceBook
    }
    // Keep the display awake for the complete lifetime of the reading page, including the
    // initial content-loading state. Restore the view's previous setting when leaving Reader.
    val readerView = LocalView.current
    DisposableEffect(readerView) {
        val wasKeepingScreenOn = readerView.keepScreenOn
        readerView.keepScreenOn = true
        onDispose { readerView.keepScreenOn = wasKeepingScreenOn }
    }

    // ---- source data (parsed on a background thread) -------------------------------
    val paragraphs by produceState<List<String>?>(initialValue = null, book.id, book.textPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val split = splitParagraphs(store.text(book))
                split.ifEmpty { listOf(" ") }
            }.getOrDefault(listOf(" "))
        }
    }
    if (paragraphs == null) {
        LoadingPage()
        return
    }
    val bookParagraphs = paragraphs!!
    val chapterSpan = remember(book.id, bookParagraphs.size) { ChapterSpan.from(book, bookParagraphs.size) }

    // ---- reader state ----------------------------------------------------------------
    var chrome by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    var settings by remember { mutableStateOf(store.readerSettings()) }
    var tts by remember { mutableStateOf(store.ttsSettings()) }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice?>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf<ReaderPlaybackService?>(null) }
    val playback by ReaderPlaybackService.state.collectAsState()

    val accent = MaterialTheme.colorScheme.primary
    val paper = if (settings.highContrast) Color(0xFFFFFFFF) else Color(0xFF000000)
    val ink = if (settings.highContrast) Color(0xFF17171A) else Color(0xFFE8E0EC)
    val chromeBg = if (settings.highContrast) Color(0xFFF0EFF2) else Color(0xFF0E0C12)

    val context = LocalContext.current
    val appContext = context.applicationContext
    val sentenceIndex = remember(book.id, bookParagraphs) { buildSentenceIndex(bookParagraphs) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var readerViewport by remember { mutableStateOf(0f to 0f) }
    var restoredSentenceRevealed by remember(book.id, book.currentSentence) { mutableStateOf(false) }

    DisposableEffect(book.id) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val playbackService = (binder as? ReaderPlaybackService.LocalBinder)?.service()
                service = playbackService
                playbackService?.loadBook(book.id, book.currentSentence)
                playbackService?.applyTts(tts.voiceName, tts.rate, tts.pitch)
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        val intent = ReaderPlaybackService.intent(appContext, ReaderPlaybackService.ACTION_LOAD, book.id)
        appContext.startService(intent)
        appContext.bindService(Intent(appContext, ReaderPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose {
            runCatching { service?.pause() }
            runCatching { appContext.unbindService(connection) }
            service = null
        }
    }

    LaunchedEffect(Unit) {
        voicesLoading = true
        voices = TtsVoiceLoader.load(appContext)
        voicesLoading = false
    }

    DisposableEffect(tts) {
        service?.applyTts(tts.voiceName, tts.rate, tts.pitch)
        onDispose { }
    }

    // ---- speech primitives -------------------------------------------------------------
    // All scroll helpers are launched on the UI [scope] so service-side TTS callbacks can keep
    // the highlighted sentence visible without blocking composition.
    fun scrollToParagraph(index: Int, smooth: Boolean = true, center: Boolean = false) {
        if (index < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex
        val last = visible.lastOrNull()?.index ?: first
        val visibleCount = (last - first + 1).coerceAtLeast(1)
        val comfortFirst = first + 1
        val comfortLast = last - 2
        val alreadyComfortablyVisible = visible.size >= 4 && index in comfortFirst..comfortLast
        val nearEdge = index <= first + 1 || index >= last - 1
        // Explicit chapter picks pass smooth=false and must always land exactly on the chapter
        // start, even if that paragraph happens to be partially visible already.
        if (smooth && (alreadyComfortablyVisible || (!center && !nearEdge))) return
        val target = if (center) (index - visibleCount / 3).coerceAtLeast(0) else index

        scrollJob?.cancel()
        scrollJob = scope.launch {
            val distance = kotlin.math.abs(target - listState.firstVisibleItemIndex)
            runCatching {
                if (smooth && distance <= 10) listState.animateScrollToItem(target)
                else listState.scrollToItem(target)
            }
        }
    }

    fun startSentence(global: Int) {
        service?.speakSentence(global)
            ?: appContext.startService(ReaderPlaybackService.intent(appContext, ReaderPlaybackService.ACTION_PLAY_SENTENCE, book.id, global))
    }

    fun playPause() {
        service?.playPause() ?: startSentence(
            playback.activeSentence.takeIf { playback.bookId == book.id && it >= 0 }
                ?: book.currentSentence.takeIf { it in 0 until sentenceIndex.size }
                ?: 0
        )
    }

    fun skipSentence(delta: Int) {
        val svc = service
        if (svc != null) {
            if (delta > 0) svc.nextSentence() else svc.prevSentence()
            return
        }
        val base = playback.activeSentence.takeIf { it >= 0 }
            ?: sentenceIndex.firstSentenceOf(listState.firstVisibleItemIndex.coerceAtLeast(0)).takeIf { it >= 0 }
            ?: 0
        val target = (base + delta).coerceIn(0, (sentenceIndex.size - 1).coerceAtLeast(0))
        startSentence(target)
    }

    fun gotoChapter(i: Int) {
        val p = chapterSpan.start(i)
        if (p >= 0) {
            val chapterSentence = sentenceIndex.firstSentenceAtOrAfter(p)
            scrollToParagraph(p, smooth = false)
            if (chapterSentence >= 0) {
                service?.seekToSentence(chapterSentence)
                    ?: appContext.startService(
                        ReaderPlaybackService.intent(
                            appContext,
                            ReaderPlaybackService.ACTION_SEEK_SENTENCE,
                            book.id,
                            chapterSentence
                        )
                    )
            }
            val latestBook = store.books().firstOrNull { it.id == book.id } ?: book
            onProgressChange(
                latestBook.copy(
                    currentParagraph = p,
                    currentSentence = chapterSentence.takeIf { it >= 0 }
                        ?: latestBook.currentSentence,
                    paragraphScrollOffset = 0,
                    paragraphCount = bookParagraphs.size,
                    finished = false,
                    lastReadAt = System.currentTimeMillis(),
                    currentChapterId = book.chapters
                        .filter { it.startParagraph in 0 until bookParagraphs.size }
                        .sortedBy { it.startParagraph }
                        .getOrNull(i)
                        ?.navigationId
                        ?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    LaunchedEffect(book.id, sentenceIndex) {
        val savedSentence = sentenceIndex.sentences.getOrNull(book.currentSentence)
        val savedParagraph = savedSentence?.paraIndex
            ?: book.currentParagraph.coerceIn(0, bookParagraphs.lastIndex.coerceAtLeast(0))
        // First compose the sentence's paragraph. Its text-layout callback then performs the
        // small line-level adjustment needed when the sentence sits deep inside a long paragraph.
        val savedOffset = if (savedSentence == null) book.paragraphScrollOffset.coerceAtLeast(0) else 0
        listState.scrollToItem(savedParagraph, savedOffset)
    }

    LaunchedEffect(playback.activeParagraph, playback.activeSentence, playback.bookId, playback.speaking) {
        // Loading the saved, paused sentence must not recenter and destroy the exact viewport.
        // Once speech is active, continue following each sentence normally.
        if (playback.bookId == book.id && playback.activeParagraph >= 0 && playback.speaking) {
            scrollToParagraph(playback.activeParagraph, smooth = true, center = true)
        }
    }

    fun exitToLibrary() {
        scrollJob?.cancel()
        service?.pause()
        val paragraph = listState.firstVisibleItemIndex.coerceIn(0, bookParagraphs.lastIndex.coerceAtLeast(0))
        val offset = listState.firstVisibleItemScrollOffset.coerceAtLeast(0)
        val sentence = playback.activeSentence.takeIf {
            playback.bookId == book.id && it >= 0
        }
            ?: sentenceIndex.firstSentenceOf(paragraph).takeIf { it >= 0 }
            ?: book.currentSentence
        // Pausing TTS can persist listening time. Merge the final reading position into that
        // freshest stored book instead of overwriting it with an older Compose snapshot.
        val latestBook = store.books().firstOrNull { it.id == book.id } ?: book
        val finished = latestBook.finished || paragraph >= bookParagraphs.lastIndex
        onExitRequest(
            latestBook.copy(
                currentParagraph = paragraph,
                currentSentence = sentence,
                paragraphScrollOffset = offset,
                paragraphCount = bookParagraphs.size,
                finished = finished,
                lastReadAt = System.currentTimeMillis()
            )
        )
    }

    BackHandler(onBack = { exitToLibrary() })

    val latestPlayPause by rememberUpdatedState { playPause() }
    val latestNext by rememberUpdatedState { skipSentence(+1) }
    val latestPrev by rememberUpdatedState { skipSentence(-1) }
    DisposableEffect(Unit) {
        ReaderBridge.controller = object : ReaderBridge.Controller {
            override fun nextSentence() = latestNext()
            override fun prevSentence() = latestPrev()
            override fun playPause() = latestPlayPause()
        }
        onDispose { if (ReaderBridge.controller != null) ReaderBridge.controller = null }
    }

    // Derive lightweight UI values from scroll state, but debounce disk persistence so a fast
    // 120 Hz fling never performs storage work on every paragraph boundary/frame.
    val liveProgress by remember(book, bookParagraphs.size) {
        derivedStateOf {
            readingProgress(
                book = book,
                currentParagraph = listState.firstVisibleItemIndex,
                paragraphCount = bookParagraphs.size,
                finished = book.finished
            )
        }
    }
    val chapterIdx = liveProgress.chapterIndex
    val progressPct = liveProgress.percent
    val latestProgressChange by rememberUpdatedState(onProgressChange)
    val latestBookSnapshot by rememberUpdatedState(book)

    LaunchedEffect(book.id, bookParagraphs.size) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (visibleParagraph, visibleOffset) ->
                delay(250)
                val paragraph = visibleParagraph.coerceIn(0, bookParagraphs.lastIndex.coerceAtLeast(0))
                val offset = visibleOffset.coerceAtLeast(0)
                val sentence = playback.activeSentence.takeIf {
                    playback.bookId == book.id && it >= 0
                }
                    ?: sentenceIndex.firstSentenceOf(paragraph).takeIf { it >= 0 }
                    ?: latestBookSnapshot.currentSentence
                if (
                    paragraph != latestBookSnapshot.currentParagraph ||
                    offset != latestBookSnapshot.paragraphScrollOffset ||
                    sentence != latestBookSnapshot.currentSentence
                ) {
                    val latestBook = store.books().firstOrNull { it.id == book.id } ?: latestBookSnapshot
                    latestProgressChange(
                        latestBook.copy(
                            currentParagraph = paragraph,
                            currentSentence = sentence,
                            paragraphScrollOffset = offset,
                            paragraphCount = bookParagraphs.size,
                            finished = latestBook.finished || paragraph >= bookParagraphs.lastIndex,
                            lastReadAt = System.currentTimeMillis()
                        )
                    )
                }
            }
    }

    // AppHost already locks LocalDensity.fontScale to 1f for the whole app. Keep the reader body
    // controlled only by its own slider, never by Android's oversized system font setting.
    val readSize = settings.textSizeSp

    // ---- reading surface -------------------------------------------------------------
    // Single tap ANYWHERE toggles chrome; double tap on a sentence starts TTS from it.
    // Both are handled by per-paragraph [detectTapGestures] detectors: Compose gives each a
    // single gesture, so a tap is never both a toggle and a speak. Drags are not consumed by
    // these tap detectors, so the LazyColumn scrolls normally.
    Box(Modifier.fillMaxSize().background(paper)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    readerViewport = bounds.top to bounds.bottom
                },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 46.dp, bottom = 88.dp)
        ) {
            itemsIndexed(
                bookParagraphs,
                key = { i, _ -> i },
                contentType = { _, paraText -> if (paraText.isBlank()) "gap" else "paragraph" }
            ) { i, paraText ->
                val activePara = if (playback.bookId == book.id) playback.activeParagraph else -1
                ParagraphItem(
                    text = paraText,
                    index = i,
                    fontSizeSansScale = readSize,
                    fontWeight = settings.fontWeight,
                    lineSpacing = settings.lineSpacing,
                    serif = settings.serif,
                    centered = settings.centered,
                    ink = ink,
                    highlightColor = Color(0xFFCDB7FF),
                    activeSentenceText = if (activePara == i) playback.activeSentenceText else null,
                    activeRangeStart = if (activePara == i) playback.activeRangeStart else -1,
                    activeRangeEnd = if (activePara == i) playback.activeRangeEnd else -1,
                    sentenceIndex = sentenceIndex,
                    paraLower = paragraphLower(i, bookParagraphs, sentenceIndex),
                    onActiveSentenceLayout = { top, bottom ->
                        if (!restoredSentenceRevealed && playback.bookId == book.id) {
                            restoredSentenceRevealed = true
                            scope.launch {
                                revealSentence(listState, readerViewport, top, bottom)
                            }
                        }
                    },
                    onSingleTap = {
                        chrome = !chrome
                        onSystemUi(chrome)
                    },
                    onDoubleTapSentence = { g -> startSentence(g) }
                )
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = GBookMotion.verticalIn(fromTop = true),
            exit = GBookMotion.verticalOut(toTop = true),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopBar(
                book = book,
                settings = settings,
                currentChapter = chapterIdx,
                chapterCount = liveProgress.chapterCount,
                chapterTitle = liveProgress.chapterTitle,
                progressPct = progressPct,
                chromeBg = chromeBg,
                onBack = { exitToLibrary() },
                onOpenChapters = { showChapters = true },
                onShare = {
                    val activeQuote = playback.activeSentenceText
                        ?.takeIf {
                            playback.bookId == book.id &&
                                playback.activeParagraph >= 0 &&
                                listState.layoutInfo.visibleItemsInfo.any { item ->
                                    item.index == playback.activeParagraph
                                }
                        }
                    if (activeQuote != null) {
                        shareQuote(
                            context = context,
                            book = book,
                            quote = activeQuote,
                            paragraph = playback.activeParagraph,
                            paragraphCount = bookParagraphs.size
                        )
                    } else {
                        Toast.makeText(
                            context,
                            "Keep a highlighted sentence visible before sharing.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onOpenSettings = { showSettings = true }
            )
        }
        AnimatedVisibility(
            visible = chrome,
            enter = GBookMotion.verticalIn(fromTop = false),
            exit = GBookMotion.verticalOut(toTop = false),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerBar(
                speaking = playback.speaking,
                enabled = playback.engineReady,
                chromeBg = chromeBg,
                accent = accent,
                onPrev = { skipSentence(-1) },
                onNext = { skipSentence(+1) },
                onPlayPause = { playPause() }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            tts = tts,
            voices = voices,
            voicesLoading = voicesLoading,
            engineError = playback.engineError,
            onSettings = { next -> settings = next; store.saveReaderSettings(next) },
            onTts = { next -> tts = next; store.saveTtsSettings(next); service?.applyTts(next.voiceName, next.rate, next.pitch) },
            onDismiss = { showSettings = false }
        )
    }

    if (showChapters) {
        ChaptersDialog(
            labels = (0 until chapterSpan.count).map { chapterSpan.title(it) },
            depths = (0 until chapterSpan.count).map { chapterSpan.depth(it) },
            current = chapterIdx,
            onPick = { i -> gotoChapter(i); showChapters = false },
            onDismiss = { showChapters = false }
        )
    }
}

/**
 * First flat [SentenceIndex] global belonging to [para], else the previous paragraph's head, else 0.
 * Lets us map a scroll position to a playable sentence and keep double-tap within a paragraph coherent.
 */
private fun paragraphLower(
    para: Int,
    paragraphs: List<String>,
    index: SentenceIndex
): Int {
    var g = index.firstSentenceOf(para)
    if (g < 0) {
        // Empty paragraph (spacer) -> play from the next sentence at/after this para.
        g = index.sentences.indexOfFirst { it.paraIndex >= para }
    }
    if (g < 0) {
        // Paragraphs with no sentences in the flat index at all -> fall back to the last globe.
        g = (index.size - 1).coerceAtLeast(0)
    }
    return g
}

// ---- Loading state -------------------------------------------------------------
@Composable
private fun LoadingPage() {
    Box(Modifier.fillMaxSize().background(Color(0xFF12101A))) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Loading book…", color = Color(0xFFB8AEBF))
        }
    }
}

// ---- A single paragraph = one scrollable row with double-tap-to-speak -------------
@Composable
private fun ParagraphItem(
    text: String,
    index: Int,
    fontSizeSansScale: Float,
    fontWeight: Float,
    lineSpacing: Float,
    serif: Boolean,
    centered: Boolean,
    ink: Color,
    highlightColor: Color,
    activeSentenceText: String?,
    activeRangeStart: Int,
    activeRangeEnd: Int,
    sentenceIndex: SentenceIndex,
    paraLower: Int,
    onActiveSentenceLayout: (topInRoot: Float, bottomInRoot: Float) -> Unit,
    onSingleTap: () -> Unit,
    onDoubleTapSentence: (Int) -> Unit
) {
    if (text.isBlank()) {
        // Blank/structural paragraph -> visual gap; single tap still toggles chrome.
        Box(
            Modifier
                .fillMaxWidth()
                .height((fontSizeSansScale * 1.6f).dp)
                .pointerInput(onSingleTap) {
                    detectTapGestures(onTap = { onSingleTap() })
                }
        )
        return
    }
    val fontFamily = if (serif) FontFamily.Serif else FontFamily.Default
    val lineHeight = (fontSizeSansScale * lineSpacing).sp
    val size = fontSizeSansScale.sp
    val headGlobal = remember(sentenceIndex, index) { sentenceIndex.firstSentenceOf(index) }
    val speakFrom = if (headGlobal >= 0) headGlobal else paraLower
    val paragraphSentences = remember(sentenceIndex, index, text) {
        var searchFrom = 0
        sentenceIndex.sentences.withIndex()
            .filter { it.value.paraIndex == index }
            .mapNotNull { indexed ->
                val start = text.indexOf(indexed.value.text, startIndex = searchFrom)
                if (start < 0) null else {
                    searchFrom = start + indexed.value.text.length
                    indexed.index to (start until searchFrom)
                }
            }
    }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textTopInRoot by remember { mutableStateOf<Float?>(null) }
    val latestSingleTap by rememberUpdatedState(onSingleTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTapSentence)
    val latestActiveSentenceLayout by rememberUpdatedState(onActiveSentenceLayout)
    val hapticView = LocalView.current

    fun publishActiveSentenceLayout(layout: TextLayoutResult?, textTop: Float?) {
        val sentence = activeSentenceText ?: return
        val result = layout ?: return
        val rootTop = textTop ?: return
        val start = text.indexOf(sentence)
        if (start < 0 || result.lineCount <= 0) return
        val lastCharacter = (start + sentence.length - 1).coerceIn(start, text.lastIndex)
        val firstLine = result.getLineForOffset(start.coerceAtMost(text.length))
        val lastLine = result.getLineForOffset(lastCharacter)
        latestActiveSentenceLayout(
            rootTop + result.getLineTop(firstLine),
            rootTop + result.getLineBottom(lastLine)
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .pointerInput(speakFrom) {
                // Stable pointer input avoids restarting every visible paragraph's gesture
                // detector when unrelated reader state changes during a fling.
                detectTapGestures(
                    onTap = {
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latestSingleTap()
                    },
                    onDoubleTap = { position ->
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val characterOffset = textLayout?.getOffsetForPosition(position) ?: 0
                        val tappedSentence = paragraphSentences.firstOrNull { (_, range) ->
                            characterOffset in range
                        }?.first ?: paragraphSentences.lastOrNull { (_, range) ->
                            range.first <= characterOffset
                        }?.first ?: speakFrom
                        latestDoubleTap(tappedSentence)
                    }
                )
            }
    ) {
        val displayText = remember(text, activeSentenceText, activeRangeStart, activeRangeEnd, highlightColor) {
            buildHighlightedParagraph(text, activeSentenceText, activeRangeStart, activeRangeEnd, ink, highlightColor)
        }
        Text(
            displayText,
            color = ink,
            fontSize = size,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            fontWeight = FontWeight((400 + fontWeight.coerceIn(0f, 1f) * 300).toInt()),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            onTextLayout = {
                textLayout = it
                publishActiveSentenceLayout(it, textTopInRoot)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    textTopInRoot = it.boundsInRoot().top
                    publishActiveSentenceLayout(textLayout, textTopInRoot)
                }
        )
    }
}

/** Scroll only as far as needed to place the restored sentence inside the readable viewport. */
private suspend fun revealSentence(
    listState: LazyListState,
    viewport: Pair<Float, Float>,
    sentenceTop: Float,
    sentenceBottom: Float
) {
    val margin = 24f
    val visibleTop = viewport.first + margin
    val visibleBottom = viewport.second - margin
    val delta = when {
        sentenceTop < visibleTop -> sentenceTop - visibleTop
        sentenceBottom > visibleBottom -> sentenceBottom - visibleBottom
        else -> 0f
    }
    if (delta != 0f) listState.scrollBy(delta)
}

private fun buildHighlightedParagraph(
    paragraph: String,
    activeSentenceText: String?,
    rangeStart: Int,
    rangeEnd: Int,
    ink: Color,
    highlightColor: Color
) = buildAnnotatedString {
    append(paragraph)
    val sentence = activeSentenceText ?: return@buildAnnotatedString
    val sentenceStart = paragraph.indexOf(sentence)
    if (sentenceStart < 0) return@buildAnnotatedString

    // Luke's V0.4.1 test showed word-range highlighting can look jumpy and incomplete.
    // Always paint the whole active sentence so next/previous sentence movement is clean.
    val start = sentenceStart
    val end = sentenceStart + sentence.length
    if (start in 0 until end && end <= paragraph.length) {
        addStyle(
            SpanStyle(
                color = Color(0xFF1B1022),
                background = highlightColor
            ),
            start,
            end
        )
    } else {
        addStyle(SpanStyle(color = ink), 0, paragraph.length)
    }
}

// ---- Top header -----------------------------------------------------------------
@Composable
private fun TopBar(
    book: Book,
    settings: ReaderSettings,
    currentChapter: Int,
    chapterCount: Int,
    chapterTitle: String,
    progressPct: Int,
    chromeBg: Color,
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fg = if (settings.highContrast) Color(0xFF17171A) else Color(0xFFF3EDF7)
    val sub = if (settings.highContrast) Color(0xFF4A4A55) else Color(0xFFB8AEBF)
    val accent = MaterialTheme.colorScheme.primary

    Surface(color = chromeBg, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = rememberLightHapticAction(onBack)) {
                Icon(Icons.Filled.ArrowBack, "Back to library", tint = fg)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
            ) {
                Text(
                    text = book.title,
                    modifier = Modifier.fillMaxWidth(),
                    color = fg,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$progressPct%", color = accent, style = MaterialTheme.typography.bodySmall)
                    Text("  ·  ", color = sub)
                    Text(
                        text = if (chapterCount > 1) chapterTitle else "One sitting",
                        color = accent,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = rememberLightHapticAction(onOpenChapters))
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = rememberLightHapticAction(onShare)) {
                    Icon(Icons.Filled.Share, "Share highlighted sentence", tint = accent)
                }
                IconButton(onClick = rememberLightHapticAction(onOpenSettings)) {
                    Icon(Icons.Filled.Settings, "Reading settings", tint = accent)
                }
            }
        }
    }
}

// ---- Bottom TTS player bar ------------------------------------------------------
@Composable
private fun PlayerBar(
    speaking: Boolean,
    enabled: Boolean,
    chromeBg: Color,
    accent: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = chromeBg, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = rememberLightHapticAction(onPrev), enabled = enabled) {
                Icon(Icons.Filled.SkipPrevious, "Previous sentence", tint = accent, modifier = Modifier.size(30.dp))
            }
            Surface(
                onClick = rememberLightHapticAction(onPlayPause),
                enabled = enabled,
                shape = RoundedCornerShape(50),
                color = accent,
                modifier = Modifier.size(58.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (speaking) "Pause" else "Play",
                        tint = Color(0xFF24153F),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            IconButton(onClick = rememberLightHapticAction(onNext), enabled = enabled) {
                Icon(Icons.Filled.SkipNext, "Next sentence", tint = accent, modifier = Modifier.size(30.dp))
            }
        }
    }
}

// ---- Settings dialog ------------------------------------------------------------
private enum class SettingsTab(val title: String) {
    Page("Page"),
    Tts("TTS")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    settings: ReaderSettings,
    tts: TtsSettings,
    voices: List<android.speech.tts.Voice?>,
    voicesLoading: Boolean,
    engineError: String?,
    onSettings: (ReaderSettings) -> Unit,
    onTts: (TtsSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetBg = Color(0xFF0E0C12)
    val sheetText = Color(0xFFF3EDF7)
    val sheetSubText = Color(0xFFD8CCDE)
    val fieldBg = Color(0xFF1A1422)
    val hapticView = LocalView.current

    // Changes stay local until OK is pressed, so dismissing the dialog never applies a partial
    // edit. Reset restores the app's canonical defaults and leaves them ready for confirmation.
    var pendingSettings by remember(settings) { mutableStateOf(settings) }
    var pendingTts by remember(tts) { mutableStateOf(tts) }
    var textSizeTick by remember { mutableStateOf((pendingSettings.textSizeSp * 4f).toInt()) }
    var fontWeightTick by remember { mutableStateOf((pendingSettings.fontWeight * 4f).toInt()) }
    var spacingTick by remember { mutableStateOf((pendingSettings.lineSpacing * 4f).toInt()) }
    var speechRateTick by remember { mutableStateOf((pendingTts.rate * 4f).toInt()) }
    var speechPitchTick by remember { mutableStateOf((pendingTts.pitch * 4f).toInt()) }
    var selectedTab by remember { mutableStateOf(SettingsTab.Page) }

    fun tickIfChanged(next: Int, previous: Int, update: (Int) -> Unit) {
        if (next != previous) {
            update(next)
            hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = sheetBg.copy(alpha = 0.98f),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 30.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                Text(
                    "Reading settings",
                    color = sheetText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {},
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    SettingsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = rememberLightHapticAction { selectedTab = tab },
                            text = {
                                Text(
                                    tab.title,
                                    color = if (selectedTab == tab) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        sheetSubText
                                    },
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = GBookMotion.standard(),
                    label = "readerSettingsTab",
                    modifier = Modifier.weight(1f)
                ) { tab ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 14.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (tab) {
                            SettingsTab.Page -> {
                                SettingSliderLabel("Text size", "${pendingSettings.textSizeSp.quarterLabel()} sp")
                                Slider(
                                    value = pendingSettings.textSizeSp,
                                    onValueChange = {
                                        tickIfChanged((it * 4f).toInt(), textSizeTick) { next -> textSizeTick = next }
                                        pendingSettings = pendingSettings.withSize(it)
                                    },
                                    valueRange = 16f..44f,
                                    steps = 111
                                )

                                SettingSliderLabel("Bold", pendingSettings.fontWeight.quarterLabel())
                                Slider(
                                    value = pendingSettings.fontWeight,
                                    onValueChange = {
                                        tickIfChanged((it * 4f).toInt(), fontWeightTick) { next -> fontWeightTick = next }
                                        pendingSettings = pendingSettings.withFontWeight(it)
                                    },
                                    valueRange = 0f..1f,
                                    steps = 3
                                )

                                SettingSliderLabel("Line spacing", pendingSettings.lineSpacing.quarterLabel())
                                Slider(
                                    value = pendingSettings.lineSpacing,
                                    onValueChange = {
                                        tickIfChanged((it * 4f).toInt(), spacingTick) { next -> spacingTick = next }
                                        pendingSettings = pendingSettings.withSpacing(it)
                                    },
                                    valueRange = 1f..3f,
                                    steps = 7
                                )

                                SettingSwitchRow(
                                    label = "Serif font",
                                    checked = pendingSettings.serif,
                                    onCheckedChange = { pendingSettings = pendingSettings.copy(serif = it) }
                                )
                                SettingSwitchRow(
                                    label = "Center paragraphs",
                                    checked = pendingSettings.centered,
                                    onCheckedChange = { pendingSettings = pendingSettings.copy(centered = it) }
                                )
                                SettingSwitchRow(
                                    label = "High contrast paper",
                                    checked = pendingSettings.highContrast,
                                    onCheckedChange = { pendingSettings = pendingSettings.copy(highContrast = it) }
                                )
                            }

                            SettingsTab.Tts -> {
                                if (engineError != null) {
                                    Text(engineError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                }
                                SettingSliderLabel("Speech speed", "${pendingTts.rate.quarterLabel()}x")
                                Slider(
                                    value = pendingTts.rate,
                                    onValueChange = {
                                        tickIfChanged((it * 4f).toInt(), speechRateTick) { next -> speechRateTick = next }
                                        pendingTts = pendingTts.withRate(it)
                                    },
                                    valueRange = 0.5f..2f,
                                    steps = 5
                                )

                                SettingSliderLabel("Speech pitch", "${pendingTts.pitch.quarterLabel()}x")
                                Slider(
                                    value = pendingTts.pitch,
                                    onValueChange = {
                                        tickIfChanged((it * 4f).toInt(), speechPitchTick) { next -> speechPitchTick = next }
                                        pendingTts = pendingTts.withPitch(it)
                                    },
                                    valueRange = 0.5f..2f,
                                    steps = 5
                                )

                                val cleanVoices = voices.filterNotNull()
                                if (voicesLoading) {
                                    Text("Loading voices…", color = sheetSubText, fontSize = 13.sp)
                                } else if (cleanVoices.isEmpty()) {
                                    Text(
                                        "Install a TTS engine or voice to choose one.",
                                        color = sheetSubText,
                                        fontSize = 13.sp
                                    )
                                } else {
                                    var expanded by remember { mutableStateOf(false) }
                                    val voiceLabels = remember(cleanVoices) { cleanVoices.conciseDisplayNames() }
                                    val selected = cleanVoices.firstOrNull { it.name == pendingTts.voiceName }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = rememberLightHapticAction<Boolean> { expanded = !expanded }
                                    ) {
                                        TextField(
                                            value = selected?.let { voiceLabels[it.name] } ?: "System default voice",
                                            onValueChange = {},
                                            readOnly = true,
                                            singleLine = true,
                                            label = { Text("TTS voice", fontSize = 12.sp) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                            colors = TextFieldDefaults.textFieldColors(
                                                containerColor = fieldBg,
                                                cursorColor = MaterialTheme.colorScheme.primary,
                                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                                unfocusedLabelColor = sheetSubText
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = sheetText,
                                                fontSize = 14.sp
                                            ),
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("System default voice", fontSize = 12.sp) },
                                                onClick = rememberLightHapticAction {
                                                    expanded = false
                                                    pendingTts = pendingTts.copy(voiceName = null)
                                                },
                                                colors = MenuDefaults.itemColors(textColor = sheetText),
                                                trailingIcon = {
                                                    if (pendingTts.voiceName == null) {
                                                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                            cleanVoices.forEach { voice ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = voiceLabels.getValue(voice.name),
                                                            fontSize = 12.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    },
                                                    onClick = rememberLightHapticAction {
                                                        expanded = false
                                                        pendingTts = pendingTts.copy(voiceName = voice.name)
                                                    },
                                                    colors = MenuDefaults.itemColors(textColor = sheetText),
                                                    trailingIcon = {
                                                        if (voice.name == pendingTts.voiceName) {
                                                            Icon(
                                                                Icons.Filled.Check,
                                                                null,
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = rememberLightHapticAction {
                            pendingSettings = ReaderSettings()
                            pendingTts = TtsSettings()
                            textSizeTick = (pendingSettings.textSizeSp * 4f).toInt()
                            fontWeightTick = (pendingSettings.fontWeight * 4f).toInt()
                            spacingTick = (pendingSettings.lineSpacing * 4f).toInt()
                            speechRateTick = (pendingTts.rate * 4f).toInt()
                            speechPitchTick = (pendingTts.pitch * 4f).toInt()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset", fontSize = 14.sp)
                    }
                    Surface(
                        onClick = rememberLightHapticAction {
                            onSettings(pendingSettings)
                            onTts(pendingTts)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "OK",
                            color = Color(0xFF24153F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSliderLabel(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun Float.quarterLabel(): String =
    String.format(java.util.Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val hapticToggle = rememberLightHapticAction<Boolean>(onCheckedChange)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { hapticToggle(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = hapticToggle)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

// ---- Chapter picker -------------------------------------------------------------
@Composable
private fun ChaptersDialog(
    labels: List<String>,
    depths: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hapticPick = rememberLightHapticAction<Int>(onPick)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contents", fontSize = 18.sp) },
        text = {
            val chapterListState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
            LazyColumn(Modifier.height(360.dp), state = chapterListState) {
                itemsIndexed(labels) { i, label ->
                        val isCurrent = i == current
                        Row(
                            Modifier.fillMaxWidth().clickable { hapticPick(i) }
                                .padding(
                                    top = 12.dp,
                                    bottom = 12.dp,
                                    start = (4 + depths.getOrElse(i) { 0 }.coerceIn(0, 6) * 18).dp,
                                    end = 4.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                chapterMenuLabel(label, i),
                                Modifier.weight(1f),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )
                            if (isCurrent) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = rememberLightHapticAction(onDismiss)) { Text("Close") } }
    )
}

/** Compact chapter-picker text: remove the redundant word without altering the saved title. */
private fun chapterMenuLabel(label: String, index: Int): String =
    label.replace(Regex("(?i)\\bchapters?\\b"), "")
        .replace(Regex("^[\\s:–—-]+|[\\s:–—-]+$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .ifEmpty { "${index + 1}" }
