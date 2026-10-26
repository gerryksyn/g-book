package id.gerry.gbook.ui.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import id.gerry.gbook.Book
import id.gerry.gbook.GBookStore
import id.gerry.gbook.MainActivity
import id.gerry.gbook.R
import id.gerry.gbook.readingProgress
import id.gerry.gbook.splitParagraphs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground TTS player for the reader. Keeping the TextToSpeech engine in this service lets
 * playback continue while the phone screen is off or the app is backgrounded, while the Compose
 * reader simply observes [state] and sends media-style commands.
 */
class ReaderPlaybackService : Service() {

    private val binder = LocalBinder()
    private lateinit var store: GBookStore
    private lateinit var engine: TtsEngine
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val readinessProbe = object : Runnable {
        override fun run() {
            publishState(Companion.state.value.speaking)
            if (!engine.ready) {
                handler.postDelayed(this, 100)
            } else {
                pendingSentenceUntilReady?.let { speakSentence(it) }
            }
        }
    }

    private var book: Book? = null
    private var paragraphs: List<String> = emptyList()
    private var sentenceIndex = SentenceIndex(emptyList())
    private var activeSentence = -1
    private var activeRangeStart = -1
    private var activeRangeEnd = -1
    private var utteranceGeneration = 0L
    private var activeUtteranceId: String? = null
    private var pendingSentenceUntilReady: Int? = null
    private var sessionStartMs = 0L

    inner class LocalBinder : Binder() {
        fun service(): ReaderPlaybackService = this@ReaderPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        store = GBookStore(applicationContext)
        engine = TtsEngine(applicationContext)
        val savedTts = store.ttsSettings()
        engine.selectVoice(savedTts.voiceName)
        engine.setSpeechParams(savedTts.rate, savedTts.pitch)
        engine.listener = object : TtsEngine.Listener {
            override fun onSpeechStart() {
                if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
                setSpeaking(true)
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
                if (utteranceId == activeUtteranceId) {
                    activeRangeStart = start
                    activeRangeEnd = end
                    publishState(true)
                }
            }

            override fun onUtteranceDone(utteranceId: String) {
                // Manual previous/next and double-tap use QUEUE_FLUSH. Android can deliver a
                // delayed completion callback for the replaced sentence, so only the latest
                // issued utterance may advance playback.
                if (utteranceId != activeUtteranceId) return
                activeUtteranceId = null
                val next = activeSentence + 1
                if (next in 0 until sentenceIndex.size) speakSentence(next) else stopPlayback(clearSentence = true)
            }

            override fun onError(what: String) {
                Companion.state.value = Companion.state.value.copy(engineError = what, speaking = false)
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GBook:ReaderPlayback").apply {
                setReferenceCounted(false)
            }
        mediaSession = MediaSessionCompat(this, "GBookReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = playPause()
                override fun onPause() = pause()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = prevSentence()
            })
            isActive = true
        }
        createNotificationChannel()
        handler.post(readinessProbe)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> loadBook(intent.getStringExtra(EXTRA_BOOK_ID), intent.getIntExtra(EXTRA_START_SENTENCE, -1))
            ACTION_PLAY_SENTENCE -> {
                loadBook(intent.getStringExtra(EXTRA_BOOK_ID), intent.getIntExtra(EXTRA_START_SENTENCE, -1))
                speakSentence(activeSentence.coerceAtLeast(0))
            }
            ACTION_SEEK_SENTENCE -> {
                val target = intent.getIntExtra(EXTRA_START_SENTENCE, -1)
                loadBook(intent.getStringExtra(EXTRA_BOOK_ID), target)
                seekToSentence(target)
            }
            ACTION_PLAY_PAUSE -> playPause()
            ACTION_NEXT -> nextSentence()
            ACTION_PREV -> prevSentence()
            ACTION_STOP -> stopPlayback(clearSentence = false)
        }
        return START_STICKY
    }

    fun loadBook(bookId: String?, startSentence: Int = -1) {
        val nextBook = store.books().firstOrNull { it.id == bookId } ?: return
        book = nextBook
        paragraphs = splitParagraphs(store.text(nextBook)).ifEmpty { listOf(" ") }
        sentenceIndex = buildSentenceIndex(paragraphs)
        activeSentence = startSentence.takeIf { it in 0 until sentenceIndex.size }
            ?: nextBook.currentSentence.takeIf { it in 0 until sentenceIndex.size }
            ?: sentenceIndex.firstSentenceOf(nextBook.currentParagraph.coerceAtLeast(0)).takeIf { it >= 0 }
            ?: 0
        activeRangeStart = -1
        activeRangeEnd = -1
        activeUtteranceId = null
        pendingSentenceUntilReady = null
        publishState(false)
        updateMediaSession(false)
    }

    fun applyTts(voiceName: String?, rate: Float, pitch: Float) {
        engine.selectVoice(voiceName)
        engine.setSpeechParams(rate.coerceIn(0.5f, 2f), pitch.coerceIn(0.5f, 2f))
    }

    fun playPause() {
        if (Companion.state.value.speaking) pause() else speakSentence(activeSentence.coerceAtLeast(0))
    }

    fun pause() = stopPlayback(clearSentence = false)

    /**
     * Move the paused playback cursor without speaking. Chapter navigation uses this so the next
     * Play action begins at the selected heading rather than resuming the previous chapter.
     */
    fun seekToSentence(global: Int) {
        if (global !in 0 until sentenceIndex.size) return
        if (Companion.state.value.speaking || activeUtteranceId != null || pendingSentenceUntilReady != null) {
            stopPlayback(clearSentence = false)
        }
        activeSentence = global
        activeRangeStart = -1
        activeRangeEnd = -1
        activeUtteranceId = null
        pendingSentenceUntilReady = null
        persistActivePosition()
        publishState(false)
        updateMediaSession(false)
    }

    fun nextSentence() = stepSentence(+1)

    fun prevSentence() = stepSentence(-1)

    private fun stepSentence(delta: Int) {
        if (sentenceIndex.size <= 0) return
        val base = activeSentence.takeIf { it in 0 until sentenceIndex.size } ?: 0
        speakSentence((base + delta).coerceIn(0, sentenceIndex.size - 1))
    }

    fun speakSentence(global: Int) {
        val sentence = sentenceIndex.sentences.getOrNull(global) ?: return
        activeSentence = global
        activeRangeStart = -1
        activeRangeEnd = -1
        persistActivePosition()
        publishState(engine.ready)
        updateMediaSession(engine.ready)
        if (!engine.ready) {
            pendingSentenceUntilReady = global
            return
        }
        pendingSentenceUntilReady = null
        engine.clearStopSuppress()
        startForeground(NOTIFICATION_ID, buildNotification(true))
        acquirePlaybackWakeLock()
        activeUtteranceId = "${++utteranceGeneration}:$global"
        engine.speak(sentence.text, activeUtteranceId!!)
    }

    private fun stopPlayback(clearSentence: Boolean) {
        activeUtteranceId = null
        pendingSentenceUntilReady = null
        engine.stop()
        releasePlaybackWakeLock()
        foldListeningTime()
        if (clearSentence) {
            activeSentence = -1
            activeRangeStart = -1
            activeRangeEnd = -1
        }
        publishState(false)
        updateMediaSession(false)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        val b = book
        if (clearSentence || b == null) stopSelf()
    }

    private fun setSpeaking(speaking: Boolean) {
        if (speaking) acquirePlaybackWakeLock() else releasePlaybackWakeLock()
        publishState(speaking)
        updateMediaSession(speaking)
    }

    private fun acquirePlaybackWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) runCatching { lock.acquire() }
    }

    private fun releasePlaybackWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private fun publishState(speaking: Boolean) {
        val sentence = sentenceIndex.sentences.getOrNull(activeSentence)
        val sourceBook = book
        val activeParagraph = sentence?.paraIndex ?: sourceBook?.currentParagraph ?: 0
        val progress = sourceBook?.let {
            readingProgress(it, activeParagraph, paragraphs.size, it.finished)
        }
        Companion.state.value = PlaybackUiState(
            bookId = sourceBook?.id,
            bookTitle = sourceBook?.title.orEmpty(),
            coverPath = sourceBook?.coverPath,
            currentChapter = progress?.chapterTitle ?: "Beginning",
            chapterNumber = progress?.chapterNumber ?: 1,
            chapterCount = progress?.chapterCount ?: 1,
            progressPercent = progress?.percent ?: 0,
            progressFraction = progress?.fraction ?: 0f,
            activeSentence = activeSentence,
            activeParagraph = sentence?.paraIndex ?: -1,
            activeSentenceText = sentence?.text,
            activeRangeStart = activeRangeStart,
            activeRangeEnd = activeRangeEnd,
            speaking = speaking,
            engineReady = engine.ready,
            engineError = Companion.state.value.engineError
        )
        if (speaking) notifyPlayback()
    }

    private fun foldListeningTime() {
        val start = sessionStartMs
        val b = book ?: return
        if (start > 0) {
            val now = System.currentTimeMillis()
            val activePara = sentenceIndex.sentences.getOrNull(activeSentence)?.paraIndex ?: b.currentParagraph
            val updated = store.recordListeningSession(
                b.copy(
                    currentParagraph = activePara.coerceAtLeast(0),
                    currentSentence = activeSentence,
                    paragraphScrollOffset = if (activePara == b.currentParagraph) b.paragraphScrollOffset else 0,
                    finished = activePara >= paragraphs.lastIndex
                ),
                now - start,
                now
            )
            store.saveBooks(store.books().map { if (it.id == updated.id) updated else it })
            book = updated
            sessionStartMs = 0L
        }
    }

    /** Save each sentence jump immediately so process death cannot lose the last spoken row. */
    private fun persistActivePosition() {
        val source = book ?: return
        val sentence = sentenceIndex.sentences.getOrNull(activeSentence) ?: return
        book = store.persistTtsPosition(
            fallback = source,
            currentParagraph = sentence.paraIndex,
            currentSentence = activeSentence,
            paragraphCount = paragraphs.size
        )
    }

    private fun updateMediaSession(speaking: Boolean) {
        val state = if (speaking) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, activeSentence.toLong().coerceAtLeast(0), 1f)
                .build()
        )
    }

    private fun notifyPlayback() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(true))
    }

    private fun buildNotification(speaking: Boolean): Notification {
        val title = book?.title ?: "G-Book"
        val playback = Companion.state.value
        val chapter = playback.currentChapter.ifBlank { "Reading" }
        val progressText = "Chapter ${playback.chapterNumber} of ${playback.chapterCount} · ${playback.progressPercent}%"
        val openReaderIntent = Intent(this, MainActivity::class.java).apply {
            book?.id?.let { putExtra(EXTRA_BOOK_ID, it) }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, openReaderIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prev = servicePendingIntent(ACTION_PREV, 1)
        val playPause = servicePendingIntent(ACTION_PLAY_PAUSE, 2)
        val next = servicePendingIntent(ACTION_NEXT, 3)
        val cover = loadCoverBitmap(book?.coverPath)
        val playPauseIcon = if (speaking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (speaking) "Pause" else "Play"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("$chapter · ${playback.progressPercent}%")
            .setSubText(progressText)
            .setProgress(100, playback.progressPercent, false)
            .setLargeIcon(cover)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(speaking)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous sentence", prev)
            .addAction(playPauseIcon, playPauseLabel, playPause)
            .addAction(android.R.drawable.ic_media_next, "Next sentence", next)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReaderPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun loadCoverBitmap(path: String?): Bitmap? = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "G-Book playback", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Reader text-to-speech playback controls"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(readinessProbe)
        foldListeningTime()
        releasePlaybackWakeLock()
        engine.release()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gbook_reader_playback"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_LOAD = "id.gerry.gbook.reader.LOAD"
        const val ACTION_PLAY_SENTENCE = "id.gerry.gbook.reader.PLAY_SENTENCE"
        const val ACTION_SEEK_SENTENCE = "id.gerry.gbook.reader.SEEK_SENTENCE"
        const val ACTION_PLAY_PAUSE = "id.gerry.gbook.reader.PLAY_PAUSE"
        const val ACTION_NEXT = "id.gerry.gbook.reader.NEXT"
        const val ACTION_PREV = "id.gerry.gbook.reader.PREV"
        const val ACTION_STOP = "id.gerry.gbook.reader.STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_START_SENTENCE = "start_sentence"

        val state: MutableStateFlow<PlaybackUiState> = MutableStateFlow(PlaybackUiState())

        fun intent(context: Context, action: String, bookId: String? = null, startSentence: Int = -1): Intent =
            Intent(context, ReaderPlaybackService::class.java).setAction(action).apply {
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                if (startSentence >= 0) putExtra(EXTRA_START_SENTENCE, startSentence)
            }
    }
}

data class PlaybackUiState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val coverPath: String? = null,
    val currentChapter: String = "Beginning",
    val chapterNumber: Int = 1,
    val chapterCount: Int = 1,
    val progressPercent: Int = 0,
    val progressFraction: Float = 0f,
    val activeSentence: Int = -1,
    val activeParagraph: Int = -1,
    val activeSentenceText: String? = null,
    val activeRangeStart: Int = -1,
    val activeRangeEnd: Int = -1,
    val speaking: Boolean = false,
    val engineReady: Boolean = false,
    val engineError: String? = null
)
