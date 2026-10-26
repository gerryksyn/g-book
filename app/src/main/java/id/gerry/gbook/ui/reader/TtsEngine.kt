package id.gerry.gbook.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Thin, engine-agnostic TTS wrapper.
 *
 * Chunk 4 responsibility: report genuine per-utterance completion so the reader can walk the
 * book sentence by sentence. A real [UtteranceProgressListener] is attached at init, so
 * [Listener.onUtteranceDone] fires with the same [utteranceId] passed to [speak] only once the
 * engine has actually finished that utterance (or error/stop). When the engine never delivers
 * progress callbacks for an id (some devices), a timeout keeps the reader from stalling.
 */
internal class TtsEngine(context: Context) {

    interface Listener {
        fun onSpeechStart()
        /** Highlights the character range the engine is currently speaking within this utterance. */
        fun onRangeStart(utteranceId: String, start: Int, end: Int) {}
        /** An utterance with [utteranceId] truly finished speaking (engine callback or timeout). */
        fun onUtteranceDone(utteranceId: String)
        fun onError(what: String)
    }

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context) { status -> onInit(status) } }.getOrNull()
    /** result of init; -1 undecided, 1 success, 0/2 failure */
    private var initState = -1
    private var defaultVoice: Voice? = null
    private var desiredVoiceName: String? = null
    private var desiredRate: Float = 1.0f
    private var desiredPitch: Float = 1.0f
    var ready: Boolean = false
        private set

    private fun onInit(status: Int) {
        initState = status
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            // sane default: english (any), else follow device loc→en
            runCatching { tts?.language = Locale.ENGLISH }
            defaultVoice = runCatching { tts?.defaultVoice }.getOrNull()
            attachProgressListener()
            applyDesiredParams()
        }
    }

    // Fallback guard for engines that never surface UtteranceProgressListener.onDone/onError
    // for a QUEUE_FLUSH'd utterance. If nothing arrives within the cap, we report done anyway so
    // continuous playback never wedges between sentences.
    private val pendingFlushGuard = Runnable {
        val id = lastFlushedId
        if (id != null) {
            finishUtterance(id, guarded = true)
        }
    }
    private var lastFlushedId: String? = null
    private var pendingUtteranceId: String? = null
    /** Set by an explicit [stop] call so the engine's own completion/stop callback for the
     *  interrupted utterance is swallowed (the reader decides whether to continue after a pause). */
    private var suppressCompletion = false

    private fun attachProgressListener() {
        runCatching {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == lastFlushedId) pendingUtteranceId = utteranceId
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val id = utteranceId ?: pendingUtteranceId ?: return
                    handler.post { listener?.onRangeStart(id, start, end) }
                }

                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: pendingUtteranceId
                    if (id != null) finishUtterance(id, guarded = false)
                }

                override fun onError(utteranceId: String?) {
                    val id = utteranceId ?: pendingUtteranceId
                    if (id != null) finishUtterance(id, guarded = false)
                }
                // onStop is not deprecated on all API levels; some call it instead of onError
                // for a flush/stop. Handle it so a skipped sentence still advances cleanly.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val id = utteranceId ?: pendingUtteranceId
                    if (id != null) finishUtterance(id, guarded = false)
                }
            })
        }
    }

    private fun finishUtterance(id: String, guarded: Boolean) {
        // QUEUE_FLUSH can report onStop/onError for the sentence that was just replaced after the
        // new sentence has already started. Never let that stale callback complete (or cancel the
        // timeout for) the new utterance.
        val expected = lastFlushedId
        if (!guarded && expected != null && id != expected) return
        if (suppressCompletion) {
            // Explicit stop() already ran; a natural completion would wrongly auto-advance.
            suppressCompletion = false
            return
        }
        if (expected == null && !guarded) {
            // not our utterance (e.g. an external engine cue); ignore
            return
        }
        pendingUtteranceId = null
        handler.removeCallbacks(pendingFlushGuard)
        lastFlushedId = null
        listener?.onUtteranceDone(id)
    }

    /** Fresh list of selectable engine voices. Computed on demand so the settings dropdown does
     *  not get stuck empty if it opens before TextToSpeech finishes initializing. */
    val availableVoices: List<Voice?>
        get() = if (!ready) emptyList() else runCatching {
            tts?.voices?.sortedWith(compareBy<Voice> { it.locale?.displayName ?: "" }.thenBy { it.name })
        }.getOrNull() ?: emptyList()

    /** Human label for a voice. */
    fun labelOf(v: Voice): String {
        val loc = v.locale
        val lang = loc?.getDisplayName(Locale.ENGLISH) ?: "Unknown"
        val quality = when (v.quality) {
            Voice.QUALITY_HIGH -> " · high"
            Voice.QUALITY_NORMAL -> " · normal"
            Voice.QUALITY_LOW -> " · low"
            Voice.QUALITY_VERY_HIGH -> " · very high"
            else -> ""
        }
        return "$lang — ${v.name.substringAfterLast('-')}$quality"
    }

    fun selectVoice(name: String?) {
        desiredVoiceName = name
        if (ready) applyDesiredParams()
    }

    fun setSpeechParams(rate: Float, pitch: Float) {
        desiredRate = rate
        desiredPitch = pitch
        if (ready) applyDesiredParams()
    }

    private fun applyDesiredParams() {
        if (!ready) return
        val name = desiredVoiceName
        if (name.isNullOrBlank()) {
            defaultVoice?.let { runCatching { tts?.voice = it } }
        } else {
            val voice = availableVoices.firstOrNull { it?.name == name }
            if (voice != null) runCatching { tts?.voice = voice }
        }
        runCatching { tts?.setSpeechRate(desiredRate) }
        runCatching { tts?.setPitch(desiredPitch) }
    }

    /**
     * Speaks [text] as one utterance identified by [utteranceId]. [utteranceId] is echoed back
     * through [Listener.onUtteranceDone] when the engine finishes that utterance, letting the
     * caller chain to the next sentence.
     */
    fun speak(text: String, utteranceId: String) {
        if (text.isBlank() || !ready) {
            // Treat a no-op as an instantaneous completion so the caller can move on.
            suppressCompletion = false
            finishUtterance(utteranceId, guarded = false)
            listener?.onSpeechStart()
            return
        }
        suppressCompletion = false
        // Register before calling TextToSpeech.speak(): some engines synchronously report that the
        // previous QUEUE_FLUSH utterance stopped while this call is in progress.
        pendingUtteranceId = utteranceId
        lastFlushedId = utteranceId
        handler.removeCallbacks(pendingFlushGuard)
        runCatching {
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                finishUtterance(utteranceId, guarded = false)
            }
        }.onFailure { listener?.onError("TTS could not run.") }
        listener?.onSpeechStart()
        // If the engine never reports completion for this id, release it after a generous cap.
        handler.postDelayed(pendingFlushGuard, UTTERANCE_TIMEOUT_MS)
    }

    /** Stops (flushes) any pending speech. Completion of the flushed id is reported to the reader
     *  (via onStop/onError or the guard) so continuous playback unwinds cleanly on an explicit
     *  pause rather than skipping ahead.
     */
    /** Stops (flushes) any pending speech. Suppresses the engine's own completion callback for the
     *  interrupted utterance so an explicit pause does NOT auto-advance to the next sentence.
     *  Call [clearStopSuppress] before speaking again. */
    fun stop() {
        suppressCompletion = true
        handler.removeCallbacks(pendingFlushGuard)
        lastFlushedId = null
        pendingUtteranceId = null
        runCatching { tts?.stop() }
    }

    fun clearStopSuppress() {
        suppressCompletion = false
    }

    fun release() {
        suppressCompletion = true
        handler.removeCallbacks(pendingFlushGuard)
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
    }

    init {
        handler.postDelayed({
            if (initState == -1 && tts != null && !ready) {
                // init never delivered SUCCESS within 5s → treat as engine missing
                listener?.onError("No usable text-to-speech engine found.")
            }
        }, 5000)
    }

    companion object {
        /** Generous ceiling before we declare a flushed utterance finished on engines that drop callbacks. */
        private const val UTTERANCE_TIMEOUT_MS = 60_000L
    }
}

/**
 * Chunk 4 sentence model. The whole book is walked one sentence at a time, so we flatten it
 * into a single ordered list of [Sentence] entries each tagged with the paragraph it came from.
 * [paraIndex] keeps the existing paragraph-based progress / chapter model; the flat list index is
 * the unit the TTS engine advances over. [text] is exactly what is spoken.
 */
internal data class Sentence(val paraIndex: Int, val text: String)

/** Splits running prose into sentences on terminal punctuation . ! ? … (keeps it attached). */
internal fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val parts = Regex("(?<=[.!?…]['”’»]?)\\s+(?=\\S)").split(text.trim())
    return parts.map { it.trim() }.filter { it.isNotBlank() }
}

/**
 * Flattens [paragraphs] into one ordered [Sentence] list. First sentence of each paragraph carries
 * [isParagraphStart] so the reading page can render paragraph breaks in the flowing text.
 */
internal data class SentenceIndex(val sentences: List<Sentence>) {
    val size: Int get() = sentences.size
    fun firstSentenceOf(para: Int): Int = sentences.indexOfFirst { it.paraIndex == para }
    /** First playable sentence at [para] or the next non-empty paragraph. */
    fun firstSentenceAtOrAfter(para: Int): Int = sentences.indexOfFirst { it.paraIndex >= para }
    fun lastSentenceOf(para: Int): Int = sentences.indexOfLast { it.paraIndex == para }
}

internal fun buildSentenceIndex(paragraphs: List<String>): SentenceIndex =
    SentenceIndex(
        paragraphs.flatMapIndexed { pi, para ->
            splitSentences(para)
                // A single-sentence paragraph is still a real paragraph start; keep its flag.
                .map { Sentence(pi, it) }
        }
    )

