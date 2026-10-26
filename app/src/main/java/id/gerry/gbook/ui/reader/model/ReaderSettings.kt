package id.gerry.gbook.ui.reader.model

/**
 * User-configurable reading surface options (chunk 3). Stored in Compose state for the
 * session — persistence of preferences is optional and can be added later without touching
 * this contract.
 */
data class ReaderSettings(
    // Regular comfortable reading size. The reader draws body text at a FIXED physical size that
    // ignores the device's dynamic system font-size (accessibility) setting, so this base value is
    // what actually appears on screen regardless of the phone's font-scale preference.
    val textSizeSp: Float = 23f,
    val fontWeight: Float = 0f,          // 0 = regular (400), 1 = bold (700)
    val lineSpacing: Float = 1.5f,
    val serif: Boolean = false,          // font family: serif vs. system sans
    val centered: Boolean = false,       // paragraph alignment (center vs. justify-left)
    val paragraphGapLines: Int = 1,      // blank line between paragraphs (1..3)
    val highContrast: Boolean = false    // ink-on-paper vs. AMOLED black
) {
    val fontSizeStep = textSizeSp.coerceIn(16f, 44f)
    fun withSize(sp: Float) = copy(textSizeSp = sp.nearestQuarter().coerceIn(16f, 44f))
    fun withFontWeight(weight: Float) = copy(fontWeight = weight.nearestQuarter().coerceIn(0f, 1f))
    fun withSpacing(s: Float) = copy(lineSpacing = s.nearestQuarter().coerceIn(1f, 3f))
}

/** TTS player options (rate, pitch + selected engine voice). Persisted alongside settings. */
data class TtsSettings(
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceName: String? = null
) {
    fun withRate(next: Float) = copy(rate = next.nearestQuarter().coerceIn(0.5f, 2f))
    fun withPitch(next: Float) = copy(pitch = next.nearestQuarter().coerceIn(0.5f, 2f))
}

private fun Float.nearestQuarter(): Float = kotlin.math.round(this * 4f) / 4f
