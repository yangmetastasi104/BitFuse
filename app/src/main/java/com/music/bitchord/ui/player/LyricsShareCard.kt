package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.drawArtwork
import com.music.bitchord.ui.replay.drawBackdrop
import com.music.bitchord.ui.replay.drawLogo
import com.music.bitchord.ui.replay.ellipsised
import com.music.bitchord.ui.replay.loadBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picked lines as one picture, drawn to a bitmap you can send.
 *
 * ## Why this is drawn rather than screenshotted
 *
 * The same reason the Replay is. A screenshot of the lyrics panel carries the
 * scrubber, the glow falloff, whichever lines happened to be on screen and the
 * status bar of that particular phone, so two people sharing the same verse
 * would send two different-looking pictures. A card laid out here is the verse,
 * the sleeve and this app's mark, and nothing else — and it is only as tall as
 * that verse needs, which holds it inside 1080×1920 (9:16): the shape a story
 * or a chat head expects. Keeping it there is the player's job, by refusing
 * more words at the pick rather than by shrinking them here.
 *
 * ## Why it isn't Compose
 *
 * `Bitmap` + `android.graphics.Canvas`, exactly as
 * [com.music.bitchord.ui.replay.ReplayPoster] does it, and for the same
 * reasons written up there: Compose offscreen on minSdk 26 is a cascade of
 * hardware bitmaps and layers that fails differently per device, while a
 * software canvas draws identically from API 26 up. The backdrop, the logo, the
 * fonts and the sleeve are shared with the Replay by importing them rather than
 * by copying, so the two cards cannot drift apart.
 *
 * ## Fitting the type
 *
 * A passage can be one line or twenty, so both the size and the height are
 * chosen rather than fixed: the layout is retried down a fixed ladder of sizes
 * until the rows stop overflowing, and the card is then grown to exactly the
 * room those rows take. The bottom rung of that ladder is a floor rather than a
 * last resort — no card ever sets type smaller than it to squeeze a passage
 * in, so a passage too long for the frame lengthens the card instead of being
 * cut, or made small enough to hold a phone at arm's length to read.
 */
internal suspend fun renderLyricsShareCard(
    context: Context,
    card: LyricsShareRequest,
    /** Pad out to the full 1080×1920 frame, the passage centred in the room. */
    story: Boolean = false,
    style: LyricsCardStyle? = null,
): Bitmap = withContext(Dispatchers.Default) {
    val type = Fonts(context)
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }
    val custom = style?.takeUnless { it == LyricsCardStyle.DEFAULT }
    val theme = custom?.theme ?: LyricsCardTheme.BITFUSE

    // ── Sizing ──────────────────────────────────────────────────────────────
    // No room is reserved for the words to sit in: they get their own height,
    // and the card is built down from that number. A chorus comes out a strip
    // barely taller than its own text instead of a 9:16 sheet with a paragraph
    // floating in the middle of it, and the size comes down only as far as the
    // ladder goes — past that the frame gives rather than the type does.
    val headTop = (if (custom?.showBrand == false) 48f else HEADER_BOTTOM) + GAP_ABOVE_BODY
    val footerReserve = if (custom?.showTrackInfo == false) FOOTER_PAD else FOOTER_THUMB + FOOTER_PAD
    val frameRoom = MAX_CARD_H - footerReserve - GAP_BELOW_BODY - headTop
    val plan = fittestPlan(type, card.lines, frameRoom, custom)
    // A story is always the whole frame, so the spare room is split above and
    // below the passage; otherwise the card is only as tall as its words.
    val spare = if (story) (frameRoom - plan.content).coerceAtLeast(0f) else 0f
    val bodyTop = headTop + spare / 2
    // The passage ends where its own rows end, and everything under it — the
    // gap, the sleeve credit, the foot — hangs off that. Because the card is
    // measured from the same sum the rows are drawn from, no line can ever
    // land past the bottom by a rounding error and be replaced by a mark
    // saying something was left out.
    val bodyBottom = bodyTop + plan.content
    val cardH = bodyBottom + spare / 2 + GAP_BELOW_BODY + footerReserve

    val bitmap = Bitmap.createBitmap(CARD_W, cardH.roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (custom != null && custom.cornerRadius > 0f) {
        val rounded = Path().apply {
            addRoundRect(RectF(0f, 0f, CARD_W.toFloat(), cardH), custom.cornerRadius, custom.cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(rounded)
    }
    when (custom?.background) {
        LyricsCardBackground.SOLID -> canvas.drawColor(custom.backgroundStart)
        LyricsCardBackground.GRADIENT -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, CARD_W.toFloat(), cardH, custom.backgroundStart, custom.backgroundEnd, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, CARD_W.toFloat(), cardH, paint)
        }
        else -> drawBackdrop(canvas, cover, 0f, cardH)
    }

    when (theme) {
        LyricsCardTheme.RUSHED, LyricsCardTheme.ALBUM_ART -> if (custom?.background == LyricsCardBackground.ARTWORK) {
            drawFullCoverBackdrop(canvas, cover, cardH)
        }
        LyricsCardTheme.HYPNOTIC -> drawHypnoticBackdrop(
            canvas, cardH, custom?.backgroundStart ?: 0xFF1F1235.toInt(),
            custom?.backgroundEnd ?: 0xFF70458A.toInt(), custom?.background == LyricsCardBackground.GRADIENT,
        )
        LyricsCardTheme.BRAT -> if (custom?.background == LyricsCardBackground.SOLID) canvas.drawColor(custom.backgroundStart)
        else -> Unit
    }

    // ── Header ──────────────────────────────────────────────────────────────
    // The mark alone, at the top right — the same one the Replay carries, so
    // two of these cards still read as one family. Nothing else goes above
    // the passage: the picture is the verse, not a caption for it.
    val brand = type.heading(46f, custom?.textColor ?: 0xE6FFFFFF.toInt(), custom?.font).apply {
        textAlign = Paint.Align.RIGHT
    }
    if (theme == LyricsCardTheme.SPOTIFY && custom != null) {
        drawArtwork(canvas, cover, card.song.title, CARD_MARGIN, 54f, 140f, custom.artworkShape == LyricsCardArtworkShape.CIRCLE, 0.20f)
        val titlePaint = type.body(52f, custom.textColor, bold = true, font = custom.font)
        val artistPaint = type.body(40f, custom.textColor, font = custom.font)
        canvas.drawText(ellipsised(card.song.title, titlePaint, 700f), 246f, 112f, titlePaint)
        canvas.drawText(ellipsised(card.song.artist, artistPaint, 700f), 246f, 168f, artistPaint)
    } else if (theme == LyricsCardTheme.QUOTE && custom != null) {
        val quote = type.heading(150f, custom.textColor, custom.font)
        canvas.drawText("“", CARD_W / 2f, 205f, quote.apply { textAlign = Paint.Align.CENTER })
    } else if (theme == LyricsCardTheme.VERTICAL && custom != null) {
        val vertical = type.label(36f, custom.textColor)
        canvas.save()
        canvas.rotate(-90f, 62f, cardH / 2f)
        canvas.drawText("${card.song.title} · ${card.song.artist}", 62f, cardH / 2f, vertical.apply { textAlign = Paint.Align.CENTER })
        canvas.restore()
    } else if (custom?.showBrand != false) {
        canvas.drawText(BRAND, CARD_W - CARD_MARGIN, HEADER_BASELINE, brand)
        drawLogo(canvas, context, CARD_W - CARD_MARGIN - brand.measureText(BRAND) - LOGO_GAP, HEADER_BASELINE)
    }

    // ── The passage ─────────────────────────────────────────────────────────
    // Every row that exists is drawn: the frame below is measured from
    // wherever these rows end, so there is never room to be short of and
    // nothing that has to be cut to make it.
    var y = bodyTop
    for (row in plan.rows) {
        val baseline = y + row.gapBefore - row.paint.ascent() +
            if (row.isGap) row.paint.textSize * GAP_MARKER_OFFSET else 0f
        val x = when {
            row.centered || custom?.alignment == LyricsCardAlignment.CENTER -> CARD_W / 2f
            custom?.theme == LyricsCardTheme.VERTICAL -> CARD_W - CARD_MARGIN
            custom?.alignment == LyricsCardAlignment.RIGHT -> CARD_W - CARD_MARGIN
            else -> CARD_MARGIN
        }
        if (!row.centered && custom?.alignment != null) row.paint.textAlign = when (custom.alignment) {
            LyricsCardAlignment.LEFT -> Paint.Align.LEFT
            LyricsCardAlignment.CENTER -> Paint.Align.CENTER
            LyricsCardAlignment.RIGHT -> Paint.Align.RIGHT
        }
        if (theme == LyricsCardTheme.MESSY) {
            canvas.save()
            canvas.rotate(((plan.rows.indexOf(row) % 5) - 2) * 2.5f, x, baseline)
            canvas.drawText(row.text, x, baseline, row.paint)
            canvas.restore()
        } else {
            if (theme == LyricsCardTheme.COUPLET && plan.rows.indexOf(row) < 2) {
                canvas.save()
                canvas.scale(1.12f, 1.12f, x, baseline)
                canvas.drawText(row.text, x, baseline, row.paint)
                canvas.restore()
            } else {
                canvas.drawText(row.text, x, baseline, row.paint)
            }
        }
        y += row.gapBefore + row.height
    }

    // ── Footer ──────────────────────────────────────────────────────────────
    // Which song this is — which only the backdrop had been saying until now.
    if (theme == LyricsCardTheme.QUOTE && custom != null) {
        val signature = type.body(36f, custom.textColor, font = custom.font)
        canvas.drawText("${card.song.title} · ${card.song.artist}", CARD_W / 2f, cardH - 80f, signature.apply { textAlign = Paint.Align.CENTER })
    } else if (custom?.showTrackInfo != false && theme != LyricsCardTheme.SPOTIFY && theme != LyricsCardTheme.ALBUM_ART) {
        val thumbY = cardH - FOOTER_PAD - FOOTER_THUMB
        val artRadius = when (custom?.artworkShape) {
            LyricsCardArtworkShape.SQUARE -> 0f
            LyricsCardArtworkShape.ROUNDED -> 0.25f
            LyricsCardArtworkShape.CIRCLE, null -> null
        }
        drawArtwork(canvas, cover, card.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB, custom?.artworkShape == LyricsCardArtworkShape.CIRCLE, artRadius)
        val textX = CARD_MARGIN + FOOTER_THUMB + 34f
        val room = CARD_W - CARD_MARGIN - textX
        val title = type.body(50f, custom?.textColor ?: Color.WHITE, bold = true, font = custom?.font)
        canvas.drawText(ellipsised(card.song.title, title, room), textX, thumbY + 74f, title)
        val artist = type.body(42f, custom?.textColor?.let { (it and 0x00FFFFFF) or 0x99000000.toInt() } ?: 0x99FFFFFF.toInt(), font = custom?.font)
        canvas.drawText(ellipsised(card.song.artist, artist, room), textX, thumbY + 132f, artist)
    }

    bitmap
}

// ── Fitting ────────────────────────────────────────────────────────────────

private class Row(
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    /** Drawn centred rather than off the left edge — the gap marker. */
    val centered: Boolean = false,
    val isGap: Boolean = false,
)

/** The rows at one size, with everything needed to place them. */
private class Plan(val rows: List<Row>, val ascent: Float) {
    /** Distance from the top of the first line to the bottom of the last. */
    val content: Float
        get() = ascent + rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/**
 * The largest size on [FIT_LADDER] whose rows still fit in [room], or the
 * bottom rung when none of them do.
 *
 * The bottom rung is a floor rather than a last resort: under it the type stops
 * being worth sharing, so the frame grows to hold the words instead of the
 * words shrinking to hold the frame. That the player budgets characters at the
 * pick is what keeps this branch from ever being reached in practice.
 */
private fun fittestPlan(
    type: Fonts,
    lines: List<LyricsShareLine>,
    room: Float,
    style: LyricsCardStyle?,
): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(type, lines, size, style)
        if (plan.content <= room) return plan
    }
    return planAt(type, lines, FIT_LADDER.last(), style)
}

/**
 * The rows at one [size], set in the face the lyrics panel draws with.
 *
 * SF Pro Display Heavy with the panel's tight tracking and its line advance,
 * carried over as the same ratios the panel's 34sp headline uses — so the card
 * reads as a still of that panel rather than as a different app quoting it.
 * Compose's tracking is absolute and `Paint`'s is a fraction of the size, hence
 * the divisions: -0.7 over 34 for the words, -0.7 over 20 for the smaller line
 * the panel hangs underneath them.
 */
private fun planAt(type: Fonts, lines: List<LyricsShareLine>, size: Float, style: LyricsCardStyle?): Plan {
    val scaledSize = size * (style?.fontScale ?: 1f)
    val primary = type.heading(scaledSize, style?.textColor ?: Color.WHITE, style?.font).apply { letterSpacing = TRACKING }
    val subSize = (scaledSize * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondaryColor = style?.textColor?.let { (it and 0x00FFFFFF) or 0xD9000000.toInt() } ?: SUB_COLOR
    val secondary = type.heading(subSize, secondaryColor, style?.font).apply { letterSpacing = SUB_TRACKING }

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            // The skipped lines, as "(...)" — the conventional editorial mark
            // for an omission. Hung off the left edge with the words, smaller
            // and a dim grey. Keep its row compact so the marker follows the
            // preceding lyric closely; the regular gap on the next lyric line
            // provides the separation below it.
            val marker = type.body(scaledSize * 0.65f, style?.textColor?.let { (it and 0x00FFFFFF) or 0x66000000 } ?: 0x66FFFFFF.toInt(), font = style?.font)
            val markerHeight = marker.descent() - marker.ascent()
            rows += Row("(...)", marker, markerHeight, 0f, isGap = true)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, paint: Paint, height: Float) {
            // One verse line is one paragraph: its first row carries the space
            // above it and its wraps sit tight underneath, so a phrase reads as
            // a block rather than as a list of separate sentences.
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += Row(text, paint, height * (style?.lineSpacing ?: 1f), gap)
            firstOfLine = false
        }
        wrap(line.text, primary, CARD_CONTENT_W).forEach { add(it, primary, size * LEAD) }
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            wrap(sub, secondary, CARD_CONTENT_W).forEach { add(it, secondary, subSize * SUB_LEAD) }
        }
    }
    return Plan(rows, -primary.ascent())
}

// ── Text ───────────────────────────────────────────────────────────────────

/**
 * Greedy wrap at [width], breaking wherever the line has to.
 *
 * Not a plain word-wrap: Japanese, Chinese and Korean lyrics carry no spaces at
 * all, and a wrapper that only split on spaces would hand the whole verse to
 * `drawText` and let it run off the card. `breakText` finds the character that
 * fits, and only then is the break walked back to a space — so scripts that keep
 * their words apart lose nothing, and scripts that don't still get several lines.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = paint.breakText(rest, true, width, null)
        if (fit <= 0) {
            out += rest
            break
        }
        // Walk back to the last space inside the fit, but only when something
        // still has to follow it — otherwise the fit is the whole string and
        // cutting at the space would drop the final word.
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

// ── Card geometry ──────────────────────────────────────────────────────────

private const val CARD_W = 1080

/**
 * 9:16 — a ceiling on the height, not the height itself. The card is grown to
 * its passage and only ever comes out this tall when the passage demands it.
 */
private const val MAX_CARD_H = 1920f

private const val CARD_MARGIN = 72f
private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2

private const val LOGO_GAP = 20f
private const val BRAND = "BitFuse"

/** Baseline of the mark at the top right, and just under it where the gap starts. */
private const val HEADER_BASELINE = 132f
private const val HEADER_BOTTOM = 170f

/** Air kept between the fixed furniture and the passage, both ends alike. */
private const val GAP_ABOVE_BODY = 20f
private const val GAP_BELOW_BODY = 20f

/** The sleeve square in the footer, and the air kept under it. */
private const val FOOTER_THUMB = 160f
private const val FOOTER_PAD = 96f

/** Smallest the translation line is allowed to get before it stops being legible. */
private const val SUB_FLOOR = 24f

/** Small visual correction so the omission marker sits below the line above. */
private const val GAP_MARKER_OFFSET = 0.18f

/**
 * The sizes tried, largest first.
 *
 * A ladder rather than a search: a dozen candidates is nothing to lay out, and
 * stepping by a visible amount keeps two cards with adjacent line counts from
 * coming out nearly the same size and reading as a mistake.
 *
 * The last rung doubles as the floor — see [fittestPlan] for why the frame is
 * what gives way under it rather than the type.
 */
private val FIT_LADDER = floatArrayOf(96f, 88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)

// ── The panel's type, as ratios ────────────────────────────────────────────
// Every number here is one the lyrics panel uses, divided by the size it uses
// it at so it holds at whatever size the ladder lands on.

/** Tracking of the panel's headline: -0.7sp against its 34sp. */
private const val TRACKING = -0.7f / 34f

/** The same -0.7sp, against the 20sp it sets the line underneath in. */
private const val SUB_TRACKING = -0.7f / 20f

/** Line advance: 41sp over 34sp for the words, 25sp over 20sp for the sub. */
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f

/** The sub-line against the words above it, and the white it is drawn in. */
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = 0xD9FFFFFF.toInt()

private fun drawFullCoverBackdrop(canvas: Canvas, cover: Bitmap?, height: Float) {
    if (cover != null) {
        canvas.drawBitmap(cover, null, RectF(0f, 0f, CARD_W.toFloat(), height), Paint(Paint.ANTI_ALIAS_FLAG))
    } else {
        canvas.drawColor(0xFF292044.toInt())
    }
    val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(0x44000000, 0x99000000.toInt(), 0xDD000000.toInt()),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, CARD_W.toFloat(), height, overlay)
}

private fun drawHypnoticBackdrop(canvas: Canvas, height: Float, first: Int, second: Int, gradientBase: Boolean) {
    if (gradientBase) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, CARD_W.toFloat(), height, first, second, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, CARD_W.toFloat(), height, base)
    }
    val rings = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        color = 0x44FFFFFF
    }
    val centerX = CARD_W * 0.72f
    val centerY = height * 0.53f
    for (ring in 1..9) canvas.drawCircle(centerX, centerY, ring * 112f, rings)
}

internal enum class LyricsCardBackground { ARTWORK, GRADIENT, SOLID }
internal enum class LyricsCardTheme { BITFUSE, SPOTIFY, RUSHED, VERTICAL, COUPLET, ALBUM_ART, HYPNOTIC, QUOTE, MESSY, BRAT }
internal enum class LyricsCardAlignment { LEFT, CENTER, RIGHT }
internal enum class LyricsCardFont { BITFUSE, SANS, SERIF, MONOSPACE, ROUNDED }
internal enum class LyricsCardArtworkShape { ROUNDED, SQUARE, CIRCLE }

internal data class LyricsCardStyle(
    val background: LyricsCardBackground = LyricsCardBackground.ARTWORK,
    val backgroundStart: Int = 0xFF292044.toInt(),
    val backgroundEnd: Int = 0xFF824C72.toInt(),
    val theme: LyricsCardTheme = LyricsCardTheme.BITFUSE,
    val textColor: Int = Color.WHITE,
    val font: LyricsCardFont = LyricsCardFont.BITFUSE,
    val alignment: LyricsCardAlignment = LyricsCardAlignment.LEFT,
    val fontScale: Float = 1f,
    val lineSpacing: Float = 1f,
    val cornerRadius: Float = 0f,
    val artworkShape: LyricsCardArtworkShape = LyricsCardArtworkShape.ROUNDED,
    val showBrand: Boolean = true,
    val showTrackInfo: Boolean = true,
) {
    companion object { val DEFAULT = LyricsCardStyle() }
}
