package id.gerry.gbook.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * One lightweight motion language for G-Book.
 *
 * Motion is deliberately short, distance-limited, and tween-based: it only animates alpha and
 * translation, which Compose can render without continuously remeasuring complex reading pages.
 */
internal object GBookMotion {
    const val Quick = 120
    const val Standard = 180
    const val Emphasized = 220
    private const val ReaderOpen = 320
    private const val ReaderClose = 220

    // A quick launch followed by a soft settle. Tween duration is time-based rather than
    // frame-count-based, so 60 Hz, 90 Hz, and 120 Hz panels all render the same motion correctly.
    private val ReaderOpenEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    private val ReaderCloseEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = FastOutSlowInEasing)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = FastOutSlowInEasing)
    fun <T> emphasized(): FiniteAnimationSpec<T> = tween(Emphasized, easing = FastOutSlowInEasing)

    fun fadeIn(): EnterTransition = fadeIn(standard())
    fun fadeOut(): ExitTransition = fadeOut(quick())

    fun horizontalIn(direction: Int): EnterTransition =
        slideInHorizontally(emphasized()) { direction * it / 12 } + fadeIn()

    fun horizontalOut(direction: Int): ExitTransition =
        slideOutHorizontally(standard()) { direction * it / 14 } + fadeOut()

    /** Full-screen reader rises from the bottom-center reading action without a competing fade. */
    fun readerOpenIn(): EnterTransition =
        slideInVertically(tween(ReaderOpen, easing = ReaderOpenEasing)) { fullHeight -> fullHeight }

    /** Reverse the same vertical route when returning to the library. */
    fun readerCloseOut(): ExitTransition =
        slideOutVertically(tween(ReaderClose, easing = ReaderCloseEasing)) { fullHeight -> fullHeight }

    fun verticalIn(fromTop: Boolean): EnterTransition =
        slideInVertically(standard()) { if (fromTop) -it / 8 else it / 8 } + fadeIn()

    fun verticalOut(toTop: Boolean): ExitTransition =
        slideOutVertically(quick()) { if (toTop) -it / 10 else it / 10 } + fadeOut()
}
