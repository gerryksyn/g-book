package id.gerry.gbook.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/**
 * Adds one light confirmation pulse when an enabled UI action is actually invoked.
 *
 * Apply this to buttons, menu items, toggles, and other explicit choices. Do not attach it to
 * passive reader taps, blank space, scrolling containers, or gesture movement.
 */
@Composable
fun rememberLightHapticAction(action: () -> Unit): () -> Unit {
    val view = LocalView.current
    val currentAction = rememberUpdatedState(action)
    return remember(view) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentAction.value()
        }
    }
}

/** Variant for callbacks that receive a value, such as switches and menu expansion changes. */
@Composable
fun <T> rememberLightHapticAction(action: (T) -> Unit): (T) -> Unit {
    val view = LocalView.current
    val currentAction = rememberUpdatedState(action)
    return remember(view) {
        { value ->
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentAction.value(value)
        }
    }
}
