package id.gerry.gbook.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// AMOLED black theme with a light-purple accent (requirement: black AMOLED + light purple accent,
// fixed font scale so device font-size settings never reflow the layout).
private val AmoledColors = darkColorScheme(
    primary = Color(0xFFCDB7FF),          // light purple accent
    onPrimary = Color(0xFF24153F),
    primaryContainer = Color(0xFF3A2859),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDB7FF),
    onSecondary = Color(0xFF24153F),
    secondaryContainer = Color(0xFF2A1F3F),
    onSecondaryContainer = Color(0xFFEBDDFF),
    tertiary = Color(0xFFB39DDB),
    onTertiary = Color(0xFF24153F),
    background = Color(0xFF000000),       // true AMOLED black
    onBackground = Color(0xFFF3EDF7),
    surface = Color(0xFF000000),          // true AMOLED black
    onSurface = Color(0xFFF3EDF7),
    surfaceVariant = Color(0xFF141014),
    onSurfaceVariant = Color(0xFFD8CCDE),
    outline = Color(0xFF8A7D90),
    outlineVariant = Color(0xFF241E29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// High-contrast reading scheme used by the reader's contrast toggle: near-black ink on white.
private val InkColors = darkColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF111111),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF111111),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF2A2A2A),
    outline = Color(0xFF444444)
)

// V0.4.2 readability pass: make the whole app UI a touch larger while keeping Material3 rhythm.
private val LargerTypography = Typography(
    displayLarge = TextStyle(fontSize = 58.sp, lineHeight = 66.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontSize = 46.sp, lineHeight = 54.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun GBookTheme(highContrast: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (highContrast) InkColors else AmoledColors
    // One GPU-cheap interaction animation for every clickable Material/foundation control.
    // Buttons, rows, cards, menu items, tabs and custom click targets inherit this automatically.
    CompositionLocalProvider(
        LocalIndication provides rememberRipple(
            bounded = true,
            color = colors.primary
        )
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = LargerTypography,
            content = content
        )
    }
}
