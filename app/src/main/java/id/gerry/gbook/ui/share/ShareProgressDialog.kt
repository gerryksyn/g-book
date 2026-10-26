package id.gerry.gbook.ui.share

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.gerry.gbook.Book
import id.gerry.gbook.ui.rememberLightHapticAction

/**
 * Dialog that previews the generated social-media share card for one book. The graphic is the
 * Compose preview only; tapping "Share…" renders a pixel-exact PNG (cover + progress + app
 * icon), exposes it through a [androidx.core.content.FileProvider] Uri, and hands it to the
 * target social app via an image ACTION_SEND intent (WhatsApp / Instagram when installed,
 * system share sheet otherwise).
 */
@Composable
internal fun ShareProgressDialog(context: Context, book: Book, onDismiss: () -> Unit) {
    // Preview and exported PNG read the same latest persisted row rather than a stale library copy.
    val source = freshBook(context, book)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        title = {
            Text(
                "Share your progress",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A ready-to-post card for ${source.title}.", color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(10.dp))
                ShareProgressCard(source)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tapping Share generates a 1080×1920 story card (cover + progress + G-Book) and sends it straight to Instagram or WhatsApp.",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = rememberLightHapticAction {
                    onDismiss()
                    shareProgress(context, source)
                }
            ) { Text("Share…", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = rememberLightHapticAction(onDismiss)) { Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    )
}
