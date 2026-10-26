package id.gerry.gbook.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Loads the system TTS voice list away from Compose click handlers so the dropdown opens smoothly. */
internal object TtsVoiceLoader {
    suspend fun load(context: Context): List<Voice?> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(emptyList())
                    engine?.shutdown()
                    return@TextToSpeech
                }
                val voices = runCatching {
                    engine?.language = Locale.ENGLISH
                    engine?.voices
                        ?.filter { it.isLocalGBookVoice() }
                        ?.sortedWith(
                            compareBy<Voice> { it.locale?.language.orEmpty() }
                                .thenBy { it.locale?.country.orEmpty() }
                                .thenBy { it.name }
                        )
                        ?: emptyList()
                }.getOrDefault(emptyList())
                if (cont.isActive) cont.resume(voices)
                engine?.shutdown()
            }
            cont.invokeOnCancellation { engine?.shutdown() }
        }
    }
}


private fun Voice.isLocalGBookVoice(): Boolean {
    if (isNetworkConnectionRequired) return false

    val locale = locale ?: return false
    val language = locale.language.lowercase(Locale.ROOT)
    val country = locale.country.uppercase(Locale.ROOT)
    return (language == "en" && (country == "US" || country == "GB")) ||
        language == "id" ||
        // Some older Android engines expose Indonesian as in-ID.
        language == "in"
}
