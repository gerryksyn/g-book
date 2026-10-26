package id.gerry.gbook.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.gerry.gbook.ui.rememberLightHapticAction
import id.gerry.gbook.ui.theme.GBookMotion

/**
 * Library action bar. A compact single row: the "My Library" title on the left and three
 * icon actions on the right — Search (magnifier), Sort (Recent/A-Z) and the grid/list view
 * toggle. Tapping the magnifier reveals a slim inline search field beneath the row (never a
 * big always-on bar). The persistent G-Book brand strip lives above this in the house shell
 * (MainActivity), so this composable never repeats the brand or a greeting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTopBar(
    booksCount: Int,
    query: String,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    listView: Boolean,
    recentSort: Boolean,
    onToggleView: () -> Unit,
    onSelectRecent: () -> Unit,
    onSelectTitle: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        // Row 1: title on the left; Search / Sort / View actions grouped on the right.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "My Library",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )

            // Search: a magnifier that toggles the inline field. Beacon to the active sort.
            val searching = searchOpen || query.isNotEmpty()
            IconButton(onClick = rememberLightHapticAction { onSearchOpenChange(!searchOpen) }) {
                Icon(
                    imageVector = if (searching) CloseGlyph else SearchGlyph,
                    contentDescription = if (searching) "Close search" else "Search books",
                    tint = if (searching) accent else MaterialTheme.colorScheme.onSurface
                )
            }

            // Sort: chevrons glyph; opens a picker menu, active order marked with a check.
            Box {
                IconButton(onClick = rememberLightHapticAction { sortMenuOpen = true }) {
                    Icon(
                        imageVector = SortGlyph,
                        contentDescription = "Sort books",
                        tint = if (sortMenuOpen) accent else MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Most recently read") },
                        onClick = rememberLightHapticAction {
                            sortMenuOpen = false
                            if (!recentSort) onSelectRecent()
                        },
                        trailingIcon = {
                            if (recentSort) Icon(CheckGlyph, null, tint = accent)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Title A-Z") },
                        onClick = rememberLightHapticAction {
                            sortMenuOpen = false
                            if (recentSort) onSelectTitle()
                        },
                        trailingIcon = {
                            if (!recentSort) Icon(CheckGlyph, null, tint = accent)
                        }
                    )
                }
            }

            // View toggle (grid <-> list).
            IconButton(onClick = rememberLightHapticAction(onToggleView)) {
                Icon(
                    imageVector = if (listView) GridGlyph else ListGlyph,
                    contentDescription = if (listView) "Switch to grid view" else "Switch to list view",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Row 2 (only when the search magnifier is toggled on): a slim inline search field.
        AnimatedVisibility(
            visible = searchOpen,
            enter = GBookMotion.verticalIn(fromTop = true),
            exit = GBookMotion.verticalOut(toTop = true)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF141014))
            ) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text("Search by title", color = Color(0xFF8A7D90), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = {
                        Icon(SearchGlyph, null, tint = accent, modifier = Modifier.size(22.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(40))
                                    .clickable(onClick = rememberLightHapticAction { onQueryChange("") })
                                    .padding(2.dp)
                            ) {
                                Icon(
                                    CloseGlyph,
                                    contentDescription = "Clear search",
                                    tint = Color(0xFF8A7D90),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        containerColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Row 3: result/summary line, quietly informing how many books match.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (searchOpen && query.isNotBlank()) {
                    val matches = booksCount // host already filters; keep it simple
                    "Results"
                } else {
                    "$booksCount book${if (booksCount == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/** Magnifier for the search toggle/field (circle + angled handle). */
val SearchGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "SearchMagnifier", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        // Keep the entire glyph safely inside the 24dp viewport. The previous custom handle
        // reached the bottom/right edge, which could look clipped inside IconButton on device.
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.5f, 3f)
            arcTo(6.5f, 6.5f, 0f, true, false, 9.5f, 16f)
            arcTo(6.5f, 6.5f, 0f, false, false, 9.5f, 3f)
            close()
            moveTo(9.5f, 5f)
            arcTo(4.5f, 4.5f, 0f, true, true, 9.5f, 14f)
            arcTo(4.5f, 4.5f, 0f, false, true, 9.5f, 5f)
            close()
            moveTo(14.2f, 14.2f)
            lineTo(19.8f, 19.8f)
            lineTo(18.4f, 21.2f)
            lineTo(12.8f, 15.6f)
            close()
        }
    }.build()
}

/** Close glyph for clearing/& closing search. */
val CloseGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "SearchClose", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 4f); lineTo(12f, 10f); lineTo(18f, 4f); lineTo(20f, 6f); lineTo(14f, 12f)
            lineTo(20f, 18f); lineTo(18f, 20f); lineTo(12f, 14f); lineTo(6f, 20f); lineTo(4f, 18f)
            lineTo(10f, 12f); lineTo(4f, 6f); close()
        }
    }.build()
}

/** Sort glyph: two opposed chevrons (AZ-ish), typical of a sort control. */
val SortGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "SortChevrons", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 4f); lineTo(12f, 10f); lineTo(17f, 4f); lineTo(15f, 3f); lineTo(12f, 7f); lineTo(9f, 3f); close()
            moveTo(7f, 20f); lineTo(12f, 14f); lineTo(17f, 20f); lineTo(15f, 21f); lineTo(12f, 17f); lineTo(9f, 21f); close()
        }
    }.build()
}

/** Check for the active sort order. */
val CheckGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Check", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9f, 16.2f); lineTo(4.8f, 12f); lineTo(3.4f, 13.4f); lineTo(9f, 19f)
            lineTo(20.6f, 7.4f); lineTo(19.2f, 6f); close()
        }
    }.build()
}

private val ListGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "ListLines", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 7f); lineTo(3f, 7f); close()
            moveTo(3f, 11f); lineTo(21f, 11f); lineTo(21f, 13f); lineTo(3f, 13f); close()
            moveTo(3f, 17f); lineTo(21f, 17f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        }
    }.build()
}

private val GridGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "GridSquares", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 3f); lineTo(10.5f, 3f); lineTo(10.5f, 10.5f); lineTo(3f, 10.5f); close()
            moveTo(13.5f, 3f); lineTo(21f, 3f); lineTo(21f, 10.5f); lineTo(13.5f, 10.5f); close()
            moveTo(3f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 21f); lineTo(3f, 21f); close()
            moveTo(13.5f, 13.5f); lineTo(21f, 13.5f); lineTo(21f, 21f); lineTo(13.5f, 21f); close()
        }
    }.build()
}
