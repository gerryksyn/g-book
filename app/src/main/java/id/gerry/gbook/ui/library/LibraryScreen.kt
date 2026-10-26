@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package id.gerry.gbook.ui.library

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.gerry.gbook.Book
import id.gerry.gbook.ui.rememberLightHapticAction
import id.gerry.gbook.ui.theme.GBookMotion

/**
 * Library content surface (chunk 2). Book list only — the screen title row + view/sort toggle
 * live in [LibraryTopBar], which the host places directly above this.
 *
 * Requirements covered:
 *  - 2x2 scrolling grid (default) and a list layout, driven by [listView]
 *  - Pinterest-style masonry when [recentSort] true: freshest book largest, tiles shrink with rank
 *  - long-press on a tile opens the SAME 3-dot options menu
 *  - "Remove from library" asks for confirmation before deleting
 *  - a "Share reading progress" entry in the options menu
 */
@Composable
internal fun LibraryScreen(
    books: List<Book>,
    listView: Boolean,
    recentSort: Boolean,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
    onToggleFinished: (Book) -> Unit,
    onRemove: (Book) -> Unit,
    onShare: (Book) -> Unit
) {
    var detailBook by remember { mutableStateOf<Book?>(null) }
    var removeTarget by remember { mutableStateOf<Book?>(null) }

    // A responsive sheet keeps long metadata bounded and remains stable at every system font size.
    detailBook?.let { target ->
        BookDetailsSheet(book = target, onDismiss = { detailBook = null })
    }

    // Requirement 8: confirm before deleting.
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from library?") },
            text = { Text("This deletes \"${target.title}\" and its cover from this device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = rememberLightHapticAction {
                    onRemove(target)
                    removeTarget = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = rememberLightHapticAction { removeTarget = null }) { Text("Cancel") } }
        )
    }

    val options: @Composable (Book, () -> Unit) -> Unit = { book, dismiss ->
        BookOptionsMenu(
            book,
            onDismiss = dismiss,
            onDetails = { detailBook = book },
            onToggleFinished = onToggleFinished,
            onShare = onShare,
            onRemoveClick = { removeTarget = book }
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (books.isEmpty()) {
            EmptyLibrary(onImport)
        } else if (listView) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    ListBook(
                        book,
                        onOpen,
                        options,
                        Modifier.animateItemPlacement(GBookMotion.standard())
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(books, key = { _, b -> b.id }) { index, book ->
                    val aspect = if (recentSort) recencyAspect(index) else FIXED_ASPECT
                    GridBook(
                        book,
                        aspect,
                        onOpen,
                        options,
                        Modifier.animateItemPlacement(GBookMotion.standard())
                    )
                }
            }
        }

        // Persistent import FAB pinned bottom-right, above the house tab bar.
        ExtendedFloatingActionButton(
            onClick = rememberLightHapticAction(onImport),
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Import") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 90.dp)
        )
    }
}

// ---- Pinterest-style recency sizing ------------------------------------------

/** Aspect (width/height): index 0 = smallest value = tallest tile. Shrinks as index grows. */
private fun recencyAspect(index: Int): Float {
    val curve = floatArrayOf(0.62f, 0.70f, 0.78f, 0.85f, 0.90f, 0.94f, 0.97f, 0.99f)
    return if (index < curve.size) curve[index] else 1.00f
}

private const val FIXED_ASPECT = 0.70f // even portrait for non-recency sorts

// ---- Grid tile ---------------------------------------------------------------

@Composable
private fun GridBook(
    book: Book,
    aspect: Float,
    onOpen: (Book) -> Unit,
    options: @Composable (Book, () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hapticView = LocalView.current
    val hapticOpen = rememberLightHapticAction { onOpen(book) }
    val hapticMenu = rememberLightHapticAction { menuOpen = true }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = hapticOpen,
                onLongClick = {
                    hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    menuOpen = true
                }
            )
    ) {
        Cover(book, Modifier.fillMaxSize())
        if (menuOpen) options(book) { menuOpen = false }
        // 3-dots button in the top corner opens the same menu as long-press.
        Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
            IconButton(onClick = hapticMenu) { Icon(Icons.Default.MoreVert, null) }
        }
        if (book.finished) {
            Icon(
                Icons.Default.CheckCircle, "Finished", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp)
            )
        }
    }
}

// ---- List row ----------------------------------------------------------------

@Composable
private fun ListBook(
    book: Book,
    onOpen: (Book) -> Unit,
    options: @Composable (Book, () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hapticView = LocalView.current
    val hapticOpen = rememberLightHapticAction { onOpen(book) }
    val hapticMenu = rememberLightHapticAction { menuOpen = true }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = hapticOpen,
                onLongClick = {
                    hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    menuOpen = true
                }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, Modifier.width(52.dp).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (book.finished) "Finished" else "${book.progressPercent}% complete",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Box {
                IconButton(onClick = hapticMenu) { Icon(Icons.Default.MoreVert, null) }
                if (menuOpen) options(book) { menuOpen = false }
            }
        }
    }
}

// ---- Responsive book details sheet -------------------------------------------

@Composable
private fun BookDetailsSheet(
    book: Book,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hapticDismiss = rememberLightHapticAction(onDismiss)
    val currentDensity = LocalDensity.current

    // The app normally fixes fontScale globally, but keeping the sheet self-contained prevents a
    // future host/theme change from allowing an oversized system font to break this compact view.
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .navigationBarsPadding()
            ) {
                val compact = maxWidth < 360.dp
                val titleSize = if (compact) 20.sp else 24.sp
                val coverWidth = if (compact) 72.dp else 88.dp

                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Cover(
                            book,
                            Modifier
                                .width(coverWidth)
                                .aspectRatio(0.70f)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AutoSizeText(
                                text = book.title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxFontSize = titleSize,
                                minFontSize = 16.sp,
                                maxLines = 3
                            )
                            AutoSizeText(
                                text = book.author.ifBlank { "Unknown author" },
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxFontSize = if (compact) 14.sp else 16.sp,
                                minFontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                    }

                    DetailRow("Format", book.format.ifBlank { "Unknown" })
                    DetailRow(
                        "Progress",
                        if (book.finished) "Finished" else "${book.progressPercent}% complete"
                    )
                    DetailRow(
                        "Chapters",
                        if (book.chapters.isEmpty()) "No table of contents" else book.chapters.size.toString()
                    )

                    TextButton(
                        onClick = hapticDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 320.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            AutoSizeText(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                ),
                maxFontSize = 14.sp,
                minFontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(if (compact) 72.dp else 84.dp)
            )
            AutoSizeText(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxFontSize = 15.sp,
                minFontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Shrinks text in half-sp increments until every complete word fits inside its measured bounds.
 * Ellipsis is enabled only after the minimum readable size has been tried, avoiding premature
 * truncation on narrow screens while keeping pathological metadata safely bounded.
 */
@Composable
private fun AutoSizeText(
    text: String,
    style: TextStyle,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    maxLines: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        var resolvedSize by remember(text, maxWidth, maxHeight, maxFontSize, minFontSize, maxLines) {
            mutableStateOf(maxFontSize)
        }
        val atMinimum = resolvedSize.value <= minFontSize.value

        Text(
            text = text,
            style = style.copy(
                fontSize = resolvedSize,
                lineHeight = (resolvedSize.value * 1.22f).sp
            ),
            maxLines = maxLines,
            softWrap = true,
            overflow = if (atMinimum) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && !atMinimum) {
                    resolvedSize = (resolvedSize.value - 0.5f)
                        .coerceAtLeast(minFontSize.value)
                        .sp
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---- Options menu (shared by long-press + 3-dots) ----------------------------

@Composable
private fun BookOptionsMenu(
    book: Book,
    onDismiss: () -> Unit,
    onDetails: (Book) -> Unit,
    onToggleFinished: (Book) -> Unit,
    onShare: (Book) -> Unit,
    onRemoveClick: (Book) -> Unit
) {
    val details = rememberLightHapticAction { onDismiss(); onDetails(book) }
    val share = rememberLightHapticAction { onDismiss(); onShare(book) }
    val toggleFinished = rememberLightHapticAction { onDismiss(); onToggleFinished(book) }
    val remove = rememberLightHapticAction { onDismiss(); onRemoveClick(book) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            { Text("Details") },
            onClick = details,
            leadingIcon = { Icon(Icons.Default.Info, null) }
        )
        DropdownMenuItem(
            { Text("Share reading progress") },
            onClick = share,
            leadingIcon = { Icon(Icons.Default.Share, null) }
        )
        DropdownMenuItem(
            { Text(if (book.finished) "Mark as unfinished" else "Mark as finished") },
            onClick = toggleFinished,
            leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
        )
        DropdownMenuItem(
            { Text("Remove from library") },
            onClick = remove,
            leadingIcon = { Icon(Icons.Default.Delete, null) }
        )
    }
}

// ---- Cover ---------------------------------------------------------------

/** Cover thumbnail; styled title-card fallback when no cover file is present. */
@Composable
internal fun Cover(book: Book, modifier: Modifier) {
    val bmp = remember(book.coverPath) { book.coverPath?.let { BitmapFactory.decodeFile(it) } }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), book.title, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color(0xFF241A2E)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(book.title, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(24.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Tap + below to add an EPUB, PDF, TXT or HTML book", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
