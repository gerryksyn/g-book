package id.gerry.gbook.ui.share

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.gerry.gbook.Book
import id.gerry.gbook.R
import id.gerry.gbook.readingProgress

/**
 * Social-media share graphic preview. Mirrors the real story PNG produced by
 * [ShareUtils.renderShareImage]: a dreamy full-bleed cover/space backdrop, large G-Book header,
 * and a black rounded progress card with book art on the left and progress/chapter data on the
 * right, matching Luke's supplied concept.
 */
internal const val SHARE_ASPECT = 0.5625f // 1080x1920 story

@Composable
internal fun ShareProgressCard(book: Book) {
    val accent = Color(0xFFCDB7FF)
    val bmp = book.coverPath?.let { BitmapFactory.decodeFile(it) }
    val progress = readingProgress(book)
    val frac = progress.fraction
    val percent = progress.percent
    val chapter = progress.chapterNumber
    val totalChapters = progress.chapterCount
    // Completed and in-progress books share one compact social-card layout.
    val cardHeight = 282.dp

    Box(
        Modifier
            .fillMaxWidth()
            .height(620.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070812), Color(0xFF27153E), Color(0xFF12364A), Color(0xFF080711))
                )
            )
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.44f; scaleX = 1.08f; scaleY = 1.08f },
                contentScale = ContentScale.Crop
            )
        }
        // Only a readability scrim: the design background remains the selected book's cover art.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.46f), Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.66f))
                )
            )
        )

        // Header: logo + G-Book, large and centered like the concept.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 78.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BookLogoBadge(52.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "G-Book",
                color = accent,
                fontSize = 36.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal
            )
        }

        // Main black stat card. Finished and in-progress books intentionally use the same shell.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 164.dp)
                .fillMaxWidth(0.92f)
                .height(cardHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.96f))
                .border(1.4.dp, accent, RoundedCornerShape(24.dp))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(if (book.finished) 220.dp else 210.dp)
                    .padding(start = 24.dp, end = 22.dp, top = 28.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(126.dp)
                        .height(186.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF21132E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painterResource(R.drawable.ic_logo),
                            contentDescription = "G-Book logo",
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (book.finished) "100%" else "$percent%",
                        color = accent,
                        fontSize = if (book.finished) 56.sp else 58.sp,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 60.sp
                    )
                    Text(
                        "COMPLETE",
                        color = Color(0xFFF2E5FF),
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    ConceptProgressBar(frac, accent, showBookmark = !book.finished)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (book.finished) {
                            "All Chapters Read\nCompleted ($totalChapters of $totalChapters)"
                        } else {
                            "Chapter $chapter of $totalChapters"
                        },
                        color = Color(0xFFF7EBDD),
                        fontSize = if (book.finished) 18.sp else 17.sp,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center,
                        maxLines = if (book.finished) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(58.dp)
                    .border(1.dp, accent, RoundedCornerShape(0.dp, 0.dp, 24.dp, 24.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(book.title, color = Color.White.copy(alpha = 0.84f), fontSize = 13.sp, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(book.author.ifBlank { "Unknown author" }, color = Color(0xFFF7EBDD).copy(alpha = 0.78f), fontSize = 11.sp, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConceptProgressBar(frac: Float, accent: Color, showBookmark: Boolean) {
    Box(
        Modifier
            .fillMaxWidth(0.94f)
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, accent, RoundedCornerShape(50))
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (showBookmark) 0.86f else 1f)
                .height(20.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1B1022))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .height(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent, Color(0xFFE7B8FF))))
            )
        }
        if (showBookmark) {
            // Tiny bookmark marker only for currently-reading progress shares.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(10.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

/** Brand lockup for other branded surfaces. */
@Composable
internal fun GBrandFooter() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BookLogoBadge(48.dp)
        Spacer(Modifier.width(10.dp))
        Text("G-Book", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    }
}

@Composable
internal fun BookLogoBadge(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.ic_logo),
        contentDescription = "G-Book logo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
    )
}

