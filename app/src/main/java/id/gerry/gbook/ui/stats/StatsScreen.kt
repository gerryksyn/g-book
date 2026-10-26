package id.gerry.gbook.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import id.gerry.gbook.Book
import id.gerry.gbook.GBookStore
import id.gerry.gbook.readingProgress
import id.gerry.gbook.formatDuration
import java.time.LocalDate
import id.gerry.gbook.ui.rememberLightHapticAction

/**
 * Reading-habit stats (chunk 4). Deliverable content is split into exactly three stacked
 * sections, faithful to the v0.3.0 "Stats" tab:
 *
 *   1. TODAY      – how long was actually *listened to* (TTS) today, from the per-day store
 *                   buckets. Not wall-clock reading; purely engine-speaking time.
 *   2. CURRENT    – the most recently read book: its title, overall progress %, and the total
 *                   time spent on *that book*, tracked independently per book (book.listeningMs).
 *   3. MONTHLY    – how many distinct days had any reading in the current calendar month.
 *
 * All figures come fresh from [store] each composition so Stats never needs its own state.
 */
@Composable
internal fun StatsScreen(
    books: List<Book>,
    store: GBookStore,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    val now = LocalDate.now()
    val todayMillis = store.todayListeningMillis()
    val monthDays = store.readingDaysInMonth(now)

    // "Current book" = the one whose read position moved most recently.
    val lastRead = books.maxByOrNull { it.lastReadAt }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatsHeader(onBackup = onBackup, onRestore = onRestore)
        // ---- section 1: Today ----------------------------------------------------------
        StatCard(
            title = "Today",
            subtitle = "Listening time"
        ) {
            MetricRow(
                big = formatDuration(todayMillis),
                bigAccent = todayMillis > 0,
                small = if (todayMillis > 0) "You listened today" else "Start listening to begin tracking today's time"
            )
        }

        // ---- section 2: Current book -----------------------------------------------------
        StatCard(
            title = "Current book",
            subtitle = lastRead?.let { "Last read · ${friendlyDate(it.lastReadAt)}" } ?: "Most recent reading"
        ) {
            if (lastRead == null) {
                MetricRow(big = "—", bigAccent = false, small = "Open a book from your library to see its progress here")
            } else {
                CurrentBookBlock(lastRead)
            }
        }

        // ---- section 3: Monthly view -----------------------------------------------------
        StatCard(
            title = "Monthly view",
            subtitle = now.month.toString().lowercase().replaceFirstChar { it.titlecase() } +
                " · " + now.year
        ) {
            MetricRow(
                big = "$monthDays",
                bigAccent = monthDays > 0,
                small = when (monthDays) {
                    0 -> "No reading days tracked yet this month"
                    1 -> "You read on 1 day this month"
                    else -> "You read on $monthDays days this month"
                }
            )
        }
    }
}

/** "My Stats" masthead shown above the three sections. */
@Composable
private fun StatsHeader(onBackup: () -> Unit, onRestore: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // Reserve the action width so long accessibility text never covers the overflow button.
        Column(Modifier.fillMaxWidth().padding(end = 56.dp)) {
            Text(
                "My Stats",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Your listening streaks & progress",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = rememberLightHapticAction { menuOpen = true }) {
                // An explicit tint is required here because the Stats page is drawn on a plain
                // black Box rather than a Material Surface that supplies LocalContentColor.
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Backup and restore",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Backup progress") },
                    onClick = rememberLightHapticAction { menuOpen = false; onBackup() }
                )
                DropdownMenuItem(
                    text = { Text("Restore progress") },
                    onClick = rememberLightHapticAction { menuOpen = false; onRestore() }
                )
            }
        }
    }
}

/** White-on-black section card shell: purple section title, grey caption, content below. */
@Composable
private fun StatCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0E0C12),   // deep raised surface on AMOLED black
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Big purple-or-white value line with a supporting caption. */
@Composable
private fun MetricRow(big: String, bigAccent: Boolean, small: String) {
    Text(
        big,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = if (bigAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        small,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** Title + independent progress fraction + per-book listening total. */
@Composable
private fun CurrentBookBlock(book: Book) {
    val progress = readingProgress(book)
    Text(
        book.title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 2
    )
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = progress.fraction,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Progress", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${progress.percent}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Chapter ${progress.chapterNumber} of ${progress.chapterCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Time read", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(book.listeningMs), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold)
        }
    }
    if (book.finished) {
        Text(
            "Finished reading",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Compact date like "Sep 3" from a lastRead epoch-millis stamp. */
private fun friendlyDate(epochMillis: Long): String {
    if (epochMillis <= 0) return "never"
    val d = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return d.month.toString().lowercase().replaceFirstChar { it.titlecase() } + " " + d.dayOfMonth
}
