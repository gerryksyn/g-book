package id.gerry.gbook.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import id.gerry.gbook.Book
import id.gerry.gbook.GBookStore
import id.gerry.gbook.R
import id.gerry.gbook.ReadingProgress
import id.gerry.gbook.readingProgress
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Story-format sharing: renders a polished 1080x1920 social image inspired by Luke's concept.
 * The book cover drives the whole scene: a soft full-bleed echo background, a large G-Book
 * header, and a black rounded progress card with the cover on the left and reading stats on the
 * right. The primary book art stays unobstructed while progress/chapter data remains clear.
 *
 * The in-app preview ([ShareCard.kt]) mirrors this arrangement.
 */
private const val ACCENT = 0xFFCDB7FF.toInt()
private const val ACCENT_2 = 0xFFE7B8FF.toInt()
private const val WIDTH = 1080
private const val HEIGHT = 1920

/**
 * The authoritative source for the number we publish: the reading-progress row currently
 * persisted in the stats store (GBookStore) for [book], not a stale in-memory UI copy.
 */
internal fun freshBook(context: Context, book: Book): Book =
    GBookStore(context.applicationContext).books().firstOrNull { it.id == book.id } ?: book

internal fun renderShareImage(context: Context, book: Book): File? = runCatching {
    val targetDir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(targetDir, "gbook-${book.id}.png")

    val source = freshBook(context, book)
    val progress = readingProgress(source)
    val readPercent = progress.percent
    val finished = source.finished || readPercent >= 100

    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cover = source.coverPath?.let { decodeSampled(it, 900, 1300) }

    drawBackdrop(canvas, cover)
    drawBrandHeader(context, canvas)
    drawProgressPanel(canvas, cover, source, progress, finished)

    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    cover?.recycle()
    bitmap.recycle()
    file
}.getOrNull()

internal fun shareProgress(context: Context, book: Book) {
    val png = renderShareImage(context, book) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", png)
    val shareText = "My reading progress on G-Book: ${book.title}"
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        clipData = ClipData.newUri(context.contentResolver, "G-Book reading progress", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    grantShareTargets(context, uri, base)

    val instagramStory = instagramStoryIntent(context, uri)
    try {
        val chooser = Intent.createChooser(base, "Share to…")
        if (instagramStory != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(instagramStory))
        }
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(base) }
    } catch (_: Exception) {
        // Keep the app usable if a social target rejects the intent.
    }
}

/** Renders the currently visible highlighted sentence as a standalone story image. */
internal fun renderQuoteImage(
    context: Context,
    book: Book,
    quote: String,
    paragraph: Int = book.currentParagraph,
    paragraphCount: Int = book.paragraphCount
): File? = runCatching {
    val targetDir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(targetDir, "gbook-quote-${book.id}.png")
    val source = freshBook(context, book)
    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cover = source.coverPath?.let { decodeSampled(it, 900, 1300) }
    val progress = readingProgress(
        book = source,
        currentParagraph = paragraph,
        paragraphCount = paragraphCount,
        finished = source.finished
    )

    drawBackdrop(canvas, cover)
    drawBrandHeader(context, canvas)
    drawQuotePanel(canvas, source, cover, quote.trim(), progress)

    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    cover?.recycle()
    bitmap.recycle()
    file
}.getOrNull()

/** Generates the quote card and immediately opens Android's native share picker. */
internal fun shareQuote(
    context: Context,
    book: Book,
    quote: String,
    paragraph: Int,
    paragraphCount: Int
) {
    if (quote.isBlank()) return
    val source = freshBook(context, book)
    val png = renderQuoteImage(context, source, quote, paragraph, paragraphCount) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", png)
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        clipData = ClipData.newUri(context.contentResolver, "G-Book highlighted quote", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "“${quote.trim()}” — ${source.title}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    grantShareTargets(context, uri, base)
    try {
        context.startActivity(Intent.createChooser(base, "Share quote…"))
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(base) }
    } catch (_: Exception) {
        // Keep reading usable if a target rejects the generated image.
    }
}

// ---- drawing ---------------------------------------------------------------------------

private fun drawBackdrop(canvas: Canvas, cover: Bitmap?) {
    if (cover != null) {
        // Luke's concept rule: the story background is the shared book cover itself.
        // Every shared book therefore gets a different full-screen cover-art backdrop.
        val bgSrc = centerCropRect(cover.width, cover.height, WIDTH, HEIGHT)
        canvas.drawBitmap(
            cover,
            bgSrc,
            Rect(0, 0, WIDTH, HEIGHT),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 146 }
        )

        // A large soft echo of the lower cover keeps the Instagram-story feel without replacing
        // the cover-art concept with a fixed decorative background.
        canvas.drawBitmap(
            cover,
            bgSrc,
            Rect(-110, 1080, WIDTH + 110, HEIGHT + 170),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 64 }
        )
    } else {
        val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                intArrayOf(0xFF0A1021.toInt(), 0xFF26113B.toInt(), 0xFF3E254D.toInt(), 0xFF081220.toInt()),
                floatArrayOf(0f, 0.32f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), fallback)
        val rng = Random(41)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        repeat(34) {
            val x = rng.nextInt(42, WIDTH - 42).toFloat()
            val y = rng.nextInt(42, HEIGHT - 220).toFloat()
            val r = rng.nextInt(2, 7).toFloat()
            starPaint.alpha = rng.nextInt(70, 180)
            canvas.drawCircle(x, y, r, starPaint)
            if (it % 6 == 0) drawSpark(canvas, x, y, r * 5f)
        }
    }

    // Legibility scrim only; do not introduce a fixed concept background over the book art.
    canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            intArrayOf(0x77000000, 0x33000000, 0x66000000, 0xAA000000.toInt()),
            floatArrayOf(0f, 0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    })
}

private fun drawBrandHeader(context: Context, canvas: Canvas) {
    val logo = decodeResourceSampled(context, R.drawable.ic_logo, 240, 240)
    val icon = Rect(274, 292, 430, 448)
    if (logo != null) {
        canvas.drawBitmap(logo, null, icon, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        logo.recycle()
    } else {
        canvas.drawRoundRect(RectF(icon), 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x221A1026 })
    }

    val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        textSize = 86f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        setShadowLayer(10f, 0f, 3f, 0x70000000)
    }
    canvas.drawText("G-Book", 462f, 404f, word)
}

private fun drawProgressPanel(
    canvas: Canvas,
    cover: Bitmap?,
    book: Book,
    progress: ReadingProgress,
    finished: Boolean
) {
    val percent = progress.percent
    // Completed and in-progress books use the same compact social-card dimensions.
    val card = RectF(60f, 520f, WIDTH - 60f, 1298f)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5000000.toInt() }
    canvas.drawRoundRect(card, 58f, 58f, cardPaint)
    canvas.drawRoundRect(card, 58f, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ACCENT
    })

    val topBandBottom = card.bottom - 170f
    canvas.drawLine(card.left, topBandBottom, card.right, topBandBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeWidth = 3f
    })

    val coverRect = RectF(118f, 616f, 492f, 1096f)
    if (cover != null) drawRoundedBitmap(canvas, cover, coverRect, 9f) else drawNoCover(canvas, coverRect)
    drawBookIdentity(canvas, book, RectF(card.left + 44f, topBandBottom + 24f, card.right - 44f, card.bottom - 24f), finished)

    val rightCenter = 746f
    if (finished) {
        drawCenteredText(canvas, "100%", ACCENT, 136f, 792f, Typeface.NORMAL, rightCenter)
        drawCenteredText(canvas, "COMPLETE", 0xFFF3E6FF.toInt(), 60f, 878f, Typeface.NORMAL, rightCenter)
        drawConceptProgressBar(canvas, RectF(524f, 928f, 986f, 996f), 1f, showBookmark = false)
        val info = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7EBDD.toInt()
            textSize = 36f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("All Chapters Read", rightCenter, 1068f, info)
        canvas.drawText(
            "Completed (${progress.chapterCount} of ${progress.chapterCount})",
            rightCenter,
            1112f,
            info
        )
    } else {
        drawCenteredText(canvas, "$percent%", ACCENT, 140f, 790f, Typeface.NORMAL, rightCenter)
        drawCenteredText(canvas, "COMPLETE", 0xFFF3E6FF.toInt(), 60f, 878f, Typeface.NORMAL, rightCenter)
        drawConceptProgressBar(canvas, RectF(524f, 928f, 986f, 996f), percent / 100f, showBookmark = true)
        val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7EBDD.toInt()
            textSize = 43f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Chapter ${progress.chapterNumber} of ${progress.chapterCount}",
            rightCenter,
            1100f,
            chapterPaint
        )

        // Bottom identity band mirrors the finished-card position: title, then author below it.
    }
}


private fun drawBookIdentity(canvas: Canvas, book: Book, band: RectF, finished: Boolean) {
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDEFFFFFF.toInt()
        textSize = if (finished) 39f else 36f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 2f, 0x88000000.toInt())
    }
    val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCF7EBDD.toInt()
        textSize = if (finished) 33f else 30f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 2f, 0x88000000.toInt())
    }
    val title = ellipsizeForPaint(book.title.ifBlank { "Untitled" }, titlePaint, band.width())
    val author = ellipsizeForPaint(book.author.ifBlank { "Unknown author" }, authorPaint, band.width() * 0.9f)
    val centerX = band.centerX()
    val titleY = band.top + 50f
    val authorY = titleY + 42f
    canvas.drawText(title, centerX, titleY, titlePaint)
    canvas.drawText(author, centerX, authorY, authorPaint)
}

private fun drawQuotePanel(
    canvas: Canvas,
    book: Book,
    cover: Bitmap?,
    quote: String,
    progress: ReadingProgress
) {
    // A substantially taller card gives the cover, progress metadata, quote and attribution
    // independent breathing room instead of forcing all content into one text-heavy block.
    val card = RectF(54f, 290f, WIDTH - 54f, 1710f)
    canvas.drawRoundRect(card, 58f, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF5000000.toInt()
    })
    canvas.drawRoundRect(card, 58f, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ACCENT
    })

    // Cover and synchronized chapter position form a compact book-context header.
    val coverRect = RectF(card.left + 58f, card.top + 64f, card.left + 318f, card.top + 454f)
    if (cover != null) {
        drawRoundedBitmap(canvas, cover, coverRect, 18f)
    } else {
        canvas.drawRoundRect(coverRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                coverRect.left,
                coverRect.top,
                coverRect.right,
                coverRect.bottom,
                0xFF2A173D.toInt(),
                0xFF0B0710.toInt(),
                Shader.TileMode.CLAMP
            )
        })
        drawCenteredText(
            canvas = canvas,
            text = "G",
            color = ACCENT,
            size = 118f,
            baselineY = coverRect.centerY() + 40f,
            style = Typeface.BOLD,
            centerX = coverRect.centerX()
        )
    }
    canvas.drawRoundRect(coverRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ACCENT
    })

    val metaLeft = coverRect.right + 46f
    val metaRight = card.right - 58f
    val metaCenter = (metaLeft + metaRight) / 2f
    drawCenteredText(canvas, "${progress.percent}%", ACCENT, 104f, card.top + 164f, Typeface.NORMAL, metaCenter)
    drawCenteredText(
        canvas,
        "CHAPTER ${progress.chapterNumber} OF ${progress.chapterCount}",
        0xFFF7EBDD.toInt(),
        30f,
        card.top + 222f,
        Typeface.BOLD,
        metaCenter
    )
    val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDF8F0FF.toInt()
        textSize = 32f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(
        ellipsizeForPaint(progress.chapterTitle, chapterPaint, metaRight - metaLeft),
        metaCenter,
        card.top + 278f,
        chapterPaint
    )
    drawConceptProgressBar(
        canvas,
        RectF(metaLeft, card.top + 322f, metaRight, card.top + 382f),
        progress.fraction,
        showBookmark = !book.finished
    )

    val headerDividerY = card.top + 502f
    canvas.drawLine(card.left + 42f, headerDividerY, card.right - 42f, headerDividerY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeWidth = 3f
    })

    val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8F0FF.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        setShadowLayer(6f, 0f, 2f, 0x88000000.toInt())
    }
    val quoteArea = RectF(card.left + 82f, headerDividerY + 66f, card.right - 82f, card.bottom - 244f)
    val lines = fitWrappedText(quote, quotePaint, quoteArea.width(), quoteArea.height())
    val lineHeight = quotePaint.textSize * 1.28f
    val blockHeight = lines.size * lineHeight
    var y = quoteArea.centerY() - blockHeight / 2f + lineHeight * 0.78f
    canvas.drawText("“", quoteArea.left - 30f, y - lineHeight * 0.70f, Paint(quotePaint).apply {
        color = ACCENT
        textSize = quotePaint.textSize * 1.55f
    })
    lines.forEach { line ->
        canvas.drawText(line, quoteArea.left, y, quotePaint)
        y += lineHeight
    }

    val dividerY = card.bottom - 190f
    canvas.drawLine(card.left + 42f, dividerY, card.right - 42f, dividerY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeWidth = 3f
    })
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = 37f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    val authorPaint = Paint(titlePaint).apply { color = 0xCCF7EBDD.toInt(); textSize = 31f }
    canvas.drawText(
        ellipsizeForPaint(book.title.ifBlank { "Untitled" }, titlePaint, card.width() - 120f),
        card.centerX(), dividerY + 68f, titlePaint
    )
    canvas.drawText(
        ellipsizeForPaint(book.author.ifBlank { "Unknown author" }, authorPaint, card.width() - 160f),
        card.centerX(), dividerY + 116f, authorPaint
    )
}

private fun drawConceptProgressBar(canvas: Canvas, rect: RectF, fraction: Float, showBookmark: Boolean) {
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawRoundRect(rect, 31f, 31f, outline)

    val inner = RectF(rect.left + 9f, rect.top + 9f, rect.right - 52f, rect.bottom - 9f)
    val fillRight = inner.left + inner.width() * fraction.coerceIn(0f, 1f)
    if (fillRight > inner.left) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(inner.left, inner.top, inner.right, inner.bottom, ACCENT, ACCENT_2, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(inner.left, inner.top, fillRight, inner.bottom), 20f, 20f, fill)
    }

    if (showBookmark) {
        // Bookmark tab only appears on in-progress shares; finished shares use a clean full bar.
        val tab = Path().apply {
            moveTo(rect.right - 36f, rect.top + 13f)
            lineTo(rect.right - 16f, rect.top + 13f)
            lineTo(rect.right - 16f, rect.bottom - 12f)
            lineTo(rect.right - 26f, rect.bottom - 22f)
            lineTo(rect.right - 36f, rect.bottom - 12f)
            close()
        }
        canvas.drawPath(tab, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
    }
}

private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float) {
    canvas.save()
    val clip = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
    canvas.clipPath(clip)
    canvas.drawBitmap(
        bitmap,
        Rect(0, 0, bitmap.width, bitmap.height),
        Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt()),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    )
    canvas.restore()
}

private fun drawNoCover(canvas: Canvas, rect: RectF) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFF2A173D.toInt(), 0xFF0B0710.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRoundRect(rect, 9f, 9f, paint)
    drawCenteredText(canvas, "G", ACCENT, 118f, rect.centerY() + 38f, Typeface.BOLD, rect.centerX())
}

private fun drawSpark(canvas: Canvas, x: Float, y: Float, size: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFF8D8.toInt(); strokeWidth = 3f }
    canvas.drawLine(x - size, y, x + size, y, p)
    canvas.drawLine(x, y - size, x, y + size, p)
    canvas.drawCircle(x, y, max(2f, size / 8f), p)
}

// ---- helpers ----------------------------------------------------------------------------

private fun instagramStoryIntent(context: Context, uri: android.net.Uri): Intent? {
    val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
        setDataAndType(uri, "image/png")
        clipData = ClipData.newUri(context.contentResolver, "G-Book Instagram story", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage("com.instagram.android")
    }
    return if (intent.resolveActivity(context.packageManager) != null) {
        context.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent
    } else null
}

private fun fitWrappedText(text: String, paint: Paint, maxWidth: Float, maxHeight: Float): List<String> {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    for (size in listOf(68f, 62f, 56f, 50f, 44f, 38f)) {
        paint.textSize = size
        val lines = wrapText(clean, paint, maxWidth)
        if (lines.size * size * 1.28f <= maxHeight) return lines
    }
    paint.textSize = 38f
    val lines = wrapText(clean, paint, maxWidth)
    val maxLines = (maxHeight / (paint.textSize * 1.28f)).toInt().coerceAtLeast(1)
    if (lines.size <= maxLines) return lines
    val clipped = lines.take(maxLines).toMutableList()
    clipped[clipped.lastIndex] = ellipsizeForPaint(clipped.last() + "…", paint, maxWidth)
    return clipped
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (text.isBlank()) return listOf("")
    val lines = mutableListOf<String>()
    var current = ""
    text.split(' ').forEach { word ->
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) current = candidate
        else {
            lines += current
            current = word
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

private fun grantShareTargets(context: Context, uri: android.net.Uri, intent: Intent) {
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { info ->
        context.grantUriPermission(info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight, reqW, reqH)
    opts.inJustDecodeBounds = false
    BitmapFactory.decodeFile(path, opts)
}.getOrNull()

private fun decodeResourceSampled(context: Context, resId: Int, reqW: Int, reqH: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(context.resources, resId, opts)
    opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight, reqW, reqH)
    opts.inJustDecodeBounds = false
    BitmapFactory.decodeResource(context.resources, resId, opts)
}.getOrNull()

private fun calcSample(w: Int, h: Int, rw: Int, rh: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var sample = 1
    while (w / (sample * 2) >= rw && h / (sample * 2) >= rh) sample *= 2
    return sample
}

private fun centerCropRect(srcW: Int, srcH: Int, outW: Int, outH: Int): Rect {
    if (srcW <= 0 || srcH <= 0) return Rect(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
    val srcRatio = srcW.toFloat() / srcH
    val outRatio = outW.toFloat() / outH
    return if (srcRatio > outRatio) {
        val cropW = (srcH * outRatio).toInt().coerceAtLeast(1)
        val left = ((srcW - cropW) / 2).coerceAtLeast(0)
        Rect(left, 0, left + cropW, srcH)
    } else {
        val cropH = (srcW / outRatio).toInt().coerceAtLeast(1)
        val top = ((srcH - cropH) / 2).coerceAtLeast(0)
        Rect(0, top, srcW, top + cropH)
    }
}


private fun ellipsizeForPaint(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val ellipsis = "…"
    var end = text.length
    while (end > 1 && paint.measureText(text.substring(0, end).trimEnd() + ellipsis) > maxWidth) end--
    return text.substring(0, end).trimEnd() + ellipsis
}

private fun drawCenteredText(
    canvas: Canvas,
    text: String,
    color: Int,
    size: Float,
    baselineY: Float,
    style: Int = Typeface.NORMAL,
    centerX: Float = WIDTH / 2f
) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setShadowLayer(6f, 0f, 2f, 0x66000000)
    }
    canvas.drawText(text, centerX, baselineY, p)
}
