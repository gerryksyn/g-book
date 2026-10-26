package id.gerry.gbook

import android.Manifest
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import id.gerry.gbook.ui.library.LibraryScreen
import id.gerry.gbook.ui.library.LibraryTopBar
import id.gerry.gbook.ui.rememberLightHapticAction
import id.gerry.gbook.ui.reader.ReaderBridge
import id.gerry.gbook.ui.reader.ReaderPlaybackService
import id.gerry.gbook.ui.reader.ReaderScreen
import id.gerry.gbook.ui.share.shareProgress
import id.gerry.gbook.ui.stats.StatsScreen
import id.gerry.gbook.ui.theme.GBookTheme
import id.gerry.gbook.ui.theme.GBookMotion
import java.time.LocalDate

/**
 * Root activity. After splash it lands on the Library (chunk 2); tapping a book opens the
 * Reader (chunk 3) which owns reading progress + TTS. This activity is the single source of
 * truth for the system UI (status/nav bars) and for physical volume-key overrides while the
 * reader is showing.
 */
class MainActivity : ComponentActivity() {

    // Which book is open in the reader (null = Library).
    private var openBookId: String? = null
    private val requestedReaderBookId = mutableStateOf<String?>(null)

    /** True while the user is in the reader AND chrome is hidden -> immersive. */
    private var immersive = false

    private val insetsController get() = WindowInsetsControllerCompat(window, window.decorView)

    val overlayVisible: Boolean get() = !immersive

    /** System bars live entirely under the activity's control, never composed here. */
    fun applySystemUi(overlayVisible: Boolean) {
        immersive = !overlayVisible
        if (overlayVisible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun restoreSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        immersive = false
    }

    // Haptics are intentionally owned by the actionable composables that need them. A global
    // touch hook would also vibrate for passive reader taps, scroll gestures, and cancelled
    // touches, making the interface feel noisy and less responsive.

    // ---- physical volume overrides while the reader is foreground --------------------
    // V0.4.3: volume keys are reader navigation keys only when the Reading page is explicitly
    // fullscreen/immersive (chrome hidden). In that state every volume up/down event is consumed
    // so Android never changes device volume: DOWN moves to the next sentence, UP moves to the
    // previous sentence. Outside fullscreen the keys fall through to the normal system volume UI.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val volumeKey = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
        val readerActiveAndFullscreen = openBookId != null && immersive
        if (readerActiveAndFullscreen && volumeKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val ctrl = ReaderBridge.controller
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) ctrl?.nextSentence() else ctrl?.prevSentence()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun restoreVolumeConsumed() { }

    /** Select the fastest display mode that preserves the current resolution. */
    private fun preferHighestRefreshRate() {
        val display = window.decorView.display ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest.refreshRate
        }
    }

    override fun onResume() {
        super.onResume()
        preferHighestRefreshRate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        }
        // A newly created app task always starts at Library. Do not restore the reader from an
        // old playback-service cursor, a retained intent extra, or Android activity state after
        // the task was closed/swiped away. Explicit reader intents are handled only by
        // [onNewIntent] while this MainActivity instance is already alive.
        openBookId = null
        requestedReaderBookId.value = null
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        setContent {
            GBookTheme {
                val density = LocalDensity.current
                // Keep G-Book's UI stable even when Android's system font size is very large.
                // Reader body size is still controlled by the in-app slider.
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                    AppHost(
                        activity = this,
                        onSetOpenBook = { id -> openBookId = id },
                        getOpenBookId = { openBookId },
                        requestedReaderBookId = requestedReaderBookId.value,
                        onReaderRequestConsumed = { requestedReaderBookId.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedBookFrom(intent)?.let { bookId ->
            openBookId = bookId
            requestedReaderBookId.value = bookId
        }
    }

    private fun requestedBookFrom(intent: Intent?): String? =
        intent?.getStringExtra(ReaderPlaybackService.EXTRA_BOOK_ID)
}

@Composable
private fun AppHost(
    activity: MainActivity,
    onSetOpenBook: (String?) -> Unit,
    getOpenBookId: () -> String?,
    requestedReaderBookId: String?,
    onReaderRequestConsumed: () -> Unit
) {
    val store = remember { GBookStore(activity.applicationContext) }
    var books by remember { mutableStateOf(store.books()) }
    var listView by remember { mutableStateOf(store.viewIsList()) }
    var recentSort by remember { mutableStateOf(store.sortIsRecent()) }
    var importBusy by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    // House-level destination (Library | Stats). Reader is pushed full-screen and never a tab.
    var houseTab by remember { mutableStateOf(Tab.Library) }

    // open book id drives which screen shows
    var openId by remember { mutableStateOf(getOpenBookId.invoke()) }
    LaunchedEffect(requestedReaderBookId, books) {
        if (requestedReaderBookId != null && books.any { it.id == requestedReaderBookId }) {
            activity.restoreSystemUi()
            store.saveLastReadBook(requestedReaderBookId)
            onSetOpenBook(requestedReaderBookId)
            openId = requestedReaderBookId
            onReaderRequestConsumed()
        }
    }
    val openBook = books.firstOrNull { it.id == openId }

    fun persist(next: List<Book>) { books = next; store.saveBooks(next) }

    fun updateBook(id: String, block: (Book) -> Book) {
        persist(books.map { if (it.id == id) block(it) else it })
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            store.importBookFrom(uri) { updated ->
                books = books.map { if (it.id == updated.id) updated else it }
                store.saveBooks(books)
            }?.let { imported ->
                books = (books + imported).distinctBy { it.id }
                store.saveBooks(books)
            }
        }
        importBusy = false
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            // Read at completion time so the backup contains the latest persisted progress even
            // if playback or the reader updated a book while the document picker was open.
            val csv = exportProgressCsv(store.books())
            activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(csv)
                writer.flush()
            } ?: error("Could not open the selected file.")
        }.isSuccess
        Toast.makeText(
            activity,
            if (saved) "Reading progress backed up." else "Backup could not be saved.",
            Toast.LENGTH_SHORT
        ).show()
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val csv = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open the selected file.")
            // Merge into the latest stored rows rather than the snapshot from before the picker.
            restoreProgressCsv(csv, store.books())
        }
        result.onSuccess { restored ->
            persist(restored.books)
            val message = when {
                restored.restoredCount == 0 -> "No matching books were found on this device."
                restored.missingCount > 0 -> "Restored ${restored.restoredCount} books; ${restored.missingCount} were not installed."
                else -> "Restored progress for ${restored.restoredCount} books."
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(activity, "This backup file could not be restored.", Toast.LENGTH_LONG).show()
        }
    }

    // ----- READER / HOUSE ---------------------------------------------------------------
    // Animate only destination identity. Opening a book uses one clean full-height rise from the
    // bottom-center reading action; the stationary library remains behind it instead of running a
    // second, competing transition. Progress updates do not restart this destination animation.
    AnimatedContent(
        targetState = openBook?.id,
        transitionSpec = {
            if (targetState != null) {
                GBookMotion.readerOpenIn() togetherWith androidx.compose.animation.ExitTransition.None
            } else {
                androidx.compose.animation.EnterTransition.None togetherWith GBookMotion.readerCloseOut()
            }
        },
        label = "readerDestination"
    ) { visibleBookId ->
        val visibleBook = books.firstOrNull { it.id == visibleBookId }
        if (visibleBook != null) {
            ReaderScreen(
                sourceBook = visibleBook,
                store = store,
                onProgressChange = { updated -> updateBook(updated.id) { updated } },
                onSystemUi = { overlayVisible ->
                    if (overlayVisible) activity.restoreSystemUi() else activity.applySystemUi(false)
                },
                onExitRequest = { updated ->
                    updateBook(updated.id) { updated }
                    ReaderBridge.controller = null
                    activity.restoreSystemUi()
                    onSetOpenBook(null)
                    openId = null
                    activity.restoreVolumeConsumed()
                }
            )
        } else {
            val query = searchQuery.trim()
            val orderedAll = if (recentSort) books.sortedByDescending { it.lastReadAt }
            else books.sortedBy { it.title.lowercase() }
            val ordered = if (query.isEmpty()) orderedAll else orderedAll.filter {
                it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
            }

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxSize()) {
                    HouseBrandRow()
                    AnimatedContent(
                        targetState = houseTab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            GBookMotion.horizontalIn(direction) togetherWith
                                GBookMotion.horizontalOut(-direction)
                        },
                        label = "housePage"
                    ) { visibleTab ->
                        when (visibleTab) {
                            Tab.Library -> {
                                Column(Modifier.fillMaxSize()) {
                                    LibraryTopBar(
                                        booksCount = books.size,
                                        query = searchQuery,
                                        searchOpen = searchOpen,
                                        onSearchOpenChange = { searchOpen = it },
                                        onQueryChange = { searchQuery = it },
                                        listView = listView,
                                        recentSort = recentSort,
                                        onToggleView = { listView = !listView; store.saveView(listView) },
                                        onSelectRecent = { recentSort = true; store.saveSort(true) },
                                        onSelectTitle = { recentSort = false; store.saveSort(false) }
                                    )
                                    LibraryScreen(
                                        books = ordered,
                                        listView = listView,
                                        recentSort = recentSort,
                                        onImport = {
                                            if (!importBusy) {
                                                importBusy = true
                                                importLauncher.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "text/html"))
                                            }
                                        },
                                        onOpen = { book ->
                                            activity.restoreSystemUi()
                                            store.saveLastReadBook(book.id)
                                            onSetOpenBook(book.id)
                                            openId = book.id
                                        },
                                        onToggleFinished = { book ->
                                            updateBook(book.id) { it.copy(finished = !it.finished, lastReadAt = System.currentTimeMillis()) }
                                        },
                                        onRemove = { book ->
                                            store.remove(book)
                                            persist(books.filterNot { it.id == book.id })
                                        },
                                        onShare = { shareProgress(activity, it) }
                                    )
                                }
                            }
                            Tab.Stats -> StatsScreen(
                                books = books,
                                store = store,
                                onBackup = { backupLauncher.launch("gbook-progress-${LocalDate.now()}.csv") },
                                onRestore = { restoreLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }
                            )
                            Tab.Reader -> Unit
                        }
                    }
                }
                HouseTabBar(
                    current = houseTab,
                    lastReadBook = books.firstOrNull { store.lastReadBookId()?.let { id -> it.id == id } == true },
                    onSelect = { houseTab = it },
                    onOpenLastRead = { book ->
                        activity.restoreSystemUi()
                        store.saveLastReadBook(book.id)
                        onSetOpenBook(book.id)
                        openId = book.id
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/** Home glyph / library glyph for the house tab bar (hand-drawn to avoid the extended icon set). */
private val LibraryGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "HouseLibrary", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        // tiny open book
        path(fill = SolidColor(Color.Black)) {
            moveTo(2f, 5f)
            lineTo(4.5f, 3.4f); quadTo(6.2f, 2.6f, 9f, 4.2f); lineTo(9f, 19f);
            quadTo(6.2f, 17.4f, 4.5f, 17.8f); lineTo(2f, 19f); close()
            moveTo(13f, 4.2f); quadTo(15.8f, 2.6f, 19.5f, 3.4f); lineTo(22f, 5f); lineTo(22f, 19f);
            lineTo(19.5f, 17.8f); quadTo(17.8f, 17.4f, 15f, 19f); lineTo(15f, 4.2f); close()
            // book spine
            moveTo(11f, 4.5f); lineTo(13f, 4.5f); lineTo(13f, 19f); lineTo(11f, 19f); close()
        }
    }.build()
}

/** Stats glyph (three short bars) for the house tab bar. */
private val StatsGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "StatsBars", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 20f); lineTo(5f, 14f); lineTo(8f, 14f); lineTo(8f, 20f); close()
            moveTo(10f, 20f); lineTo(10f, 9f); lineTo(13f, 9f); lineTo(13f, 20f); close()
            moveTo(15f, 20f); lineTo(15f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); close()
        }
    }.build()
}

/** Play/keep-reading glyph (rounded play button) for the central Read tab. */
private val ReadGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "HouseRead", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        // rounded-square play button: squircle outline + center triangle.
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            quadTo(21f, 2f, 22f, 12f)
            quadTo(23f, 22f, 12f, 22f)
            quadTo(2f, 22f, 2f, 12f)
            quadTo(2f, 2f, 12f, 2f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 8f)
            lineTo(10f, 16f)
            lineTo(16.5f, 12f)
            close()
        }
    }.build()
}

/**
 * Bottom house bar: Library (left) · Read (center) · Stats (right). The Read tab is a jump
 * action — it re-opens the last book the user was reading (see [onOpenLastRead]) rather than
 * acting as a persistent destination.
 */
@Composable
private fun HouseTabBar(
    current: Tab,
    lastReadBook: Book?,
    onSelect: (Tab) -> Unit,
    onOpenLastRead: (Book) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A080B))
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HouseItem("Library", LibraryGlyph, current == Tab.Library) { onSelect(Tab.Library) }
        // Center “Read” action: no tab highlight, just re-opens the last book when present.
        HouseItem(
            label = "Read",
            glyph = ReadGlyph,
            active = false,
            enabled = lastReadBook != null,
            contentDescription = lastReadBook?.let { "Continue ${it.title}" } ?: "Read"
        ) {
            lastReadBook?.let(onOpenLastRead)
        }
        HouseItem("Stats", StatsGlyph, current == Tab.Stats) { onSelect(Tab.Stats) }
    }
}

/** One tappable tab: a glyph over a label, highlighted in the accent when selected. */
@Composable
private fun HouseItem(
    label: String,
    glyph: ImageVector,
    active: Boolean,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val hapticClick = rememberLightHapticAction(onClick)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = hapticClick)
            .padding(horizontal = 28.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription ?: label,
            tint = if (active) accent else Color(0xFF8A7D90)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (active) accent else Color(0xFF8A7D90)
        )
    }
}

/**
 * Centered G-Book brand strip shown at the top of the Library and Stats pages only. The
 * Reader returns from [AppHost] before this row is ever reached, so reading stays uncluttered.
 */
@Composable
private fun HouseBrandRow() {
    Row(
        Modifier
            .fillMaxWidth()
            // Keep the brand clear of the status bar: this is the topmost element under
            // edge-to-edge insets, so it needs top system-bar padding or the logo gets clipped.
            .statusBarsPadding()
            .background(Color(0x0DFFFFFF))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            Image(
                painter = painterResource(id.gerry.gbook.R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "-Book",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFCDB7FF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
