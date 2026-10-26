package id.gerry.gbook.ui.reader

import android.speech.tts.Voice
import java.util.Locale

/** Produces the strict language + variant label used by the voice picker. */
internal fun Voice.conciseDisplayName(): String =
    "${languageAcronym()} A"

/**
 * Gives every local voice a stable variant letter. Variants are counted independently for each
 * exact language/region, ordered by the engine's stable voice name.
 */
internal fun List<Voice>.conciseDisplayNames(): Map<String, String> =
    groupBy { it.languageAcronym() }
        .values
        .flatMap { matchingVoices ->
            matchingVoices.sortedBy { it.name }.mapIndexed { index, voice ->
                voice.name to "${voice.languageAcronym()} ${alphabeticalSuffix(index)}"
            }
        }
        .toMap()

private fun Voice.languageAcronym(): String {
    val locale = locale
    val language = when (locale?.language?.lowercase(Locale.ROOT)) {
        "en" -> "eng"
        "id", "in" -> "id"
        else -> locale?.language?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "und"
    }
    val region = locale?.country
        ?.uppercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it == "GB") "UK" else it }

    // Indonesian is intentionally displayed without a region: `id A`.
    return if (language == "eng" && region != null) "$language ($region)" else language
}

private fun alphabeticalSuffix(index: Int): String {
    var value = index + 1
    return buildString {
        while (value > 0) {
            value--
            append(('A'.code + value % 26).toChar())
            value /= 26
        }
    }.reversed()
}

