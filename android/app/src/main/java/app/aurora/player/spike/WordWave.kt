package app.aurora.player.spike

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aurora.player.LyricWord
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Letter-wave law — exact port of the reference demo.
 *
 * Held words (at least [WAVE_MIN_MS] and, when the line carries at least 3
 * words, at least [WAVE_MEDIAN_FACTOR] x the line's median word duration —
 * the same rule as the demo) are drawn as the letter-wave effect: every
 * letter lifts, scales, glows and brightens on its own window driven by
 * the audio-clock position [nowMs].
 *
 * Short words are NOT waved: they render plain with alpha easing from
 * [DIM] to 1 over [PLAIN_FADE_MS].
 *
 * Latin is drawn letter-by-letter (translate / scale / glow), because Latin
 * glyphs do not join. Arabic is drawn slice-by-slice from pre-baked
 * bitmaps (ink + glowMin + glowMax), interpolated across the slice grid —
 * Arabic glyphs join, so letter-by-letter drawing would break them.
 *
 * No animation timer: positions come from the audio clock.
 */
object WordWaveLaw {
    const val LIFT_EM = 0.09f
    const val LATIN_SCALE = 0.03f
    const val GLOW_A = 0.55f
    const val GLOW_R_MIN = 0.10f
    const val GLOW_R_MAX = 0.26f
    const val PEAK_AT = 0.40f
    const val LETTER_MIN_MS = 800f
    const val LETTER_MAX_MS = 1800f
    const val PLAIN_FADE_MS = 250f
    const val WAVE_MIN_MS = 1400L
    const val WAVE_MEDIAN_FACTOR = 2.0f
    const val DIM = 0.35f
    const val SLICE_PX = 3f
    const val GH_EM = 1.8f
    const val BASE_EM = 1.3f
    const val PAD_RATIO = 0.4762f

    /** Single pulse: 0 -> peak at [PEAK_AT] -> 0. */
    fun bump(p: Float): Float {
        if (p <= 0f || p >= 1f) return 0f
        val q = if (p < PEAK_AT) 0.5f * p / PEAK_AT
                else 0.5f + 0.5f * (p - PEAK_AT) / (1f - PEAK_AT)
        val s = sin(Math.PI * q).toFloat()
        return s * s
    }

    /** Smoothstep 0..1. */
    fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Per-letter state: lift (em), glow alpha, glow scale, brightness, scale. */
    data class LetterState(
        var lift: Float = 0f,
        var ga: Float = 0f,
        var gm: Float = 0f,
        var lit: Float = DIM,
        var sc: Float = 1f,
    )

    /**
     * Mirrors the demo's letterState: letter i of n starts lifting
     * progressively across the word, and is fully bright at 0.5*dur.
     */
    fun letterState(t: Float, startMs: Float, holdMs: Float, i: Int, n: Int, out: LetterState) {
        val dur = (holdMs * 0.5f).coerceIn(LETTER_MIN_MS, LETTER_MAX_MS)
        val spread = (holdMs - dur).coerceAtLeast(0f)
        val ls = startMs + (if (n > 1) i * spread / (n - 1) else 0f)
        val b = bump((t - ls) / dur)
        out.lift = b * LIFT_EM
        out.ga = b * GLOW_A
        out.gm = b
        out.lit = DIM + (1f - DIM) * smooth((t - ls) / (dur * 0.5f))
        out.sc = 1f + b * LATIN_SCALE
    }

    /** Non-wave word: fade from [DIM] to 1 over [PLAIN_FADE_MS]. */
    fun plainAlpha(t: Float, startMs: Float): Float =
        DIM + (1f - DIM) * smooth((t - startMs) / PLAIN_FADE_MS)

    /** Same condition as the demo: n >= 2, dur >= WAVE_MIN_MS, >= 2.0x median. */
    fun isWaveWord(n: Int, durMs: Long, medianMs: Long?): Boolean {
        if (n < 2) return false
        if (durMs < WAVE_MIN_MS) return false
        if (medianMs == null) return true
        return durMs >= WAVE_MEDIAN_FACTOR * medianMs
    }

    /** Median of a line's word durations (null when fewer than 3 words). */
    fun median(durationsMs: List<Long>): Long? {
        if (durationsMs.size < 3) return null
        val sorted = durationsMs.sorted()
        return sorted[sorted.size / 2]
    }
}

/** Pre-baked word for slice-drawn (Arabic) lines. */
internal class WordCache(
    val ink: Bitmap,
    val glowMin: Bitmap,
    val glowMax: Bitmap,
    val width: Int,
    val height: Int,
    val sliceW: Int,
    val padPx: Float,
) {
    val sliceCount: Int get() = (width + sliceW - 1) / sliceW
    val baselineOffset: Int get() = WordWaveLaw.BASE_EM.let { (height / WordWaveLaw.GH_EM * it).roundToInt() }
}

private fun bakeBitmap(
    text: String,
    fontSizePx: Float,
    baseY: Int,
    width: Int,
    height: Int,
    padPx: Float,
    shadowBlur: Float?,
    color: Color,
): Bitmap {
    val paint = AndroidPaint().apply {
        this.textSize = fontSizePx
        this.isAntiAlias = true
        this.typeface = Typeface.DEFAULT_BOLD
        this.color = color.toArgb()
        if (shadowBlur != null && shadowBlur > 0f) {
            this.setShadowLayer(shadowBlur, 0f, 0f, color.toArgb())
        }
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawText(text, padPx, baseY.toFloat(), paint)
    return bmp
}

private fun bakeCache(word: String, fontSizePx: Float, color: Color): WordCache {
    val padPx = fontSizePx * WordWaveLaw.PAD_RATIO
    val height = ceil(fontSizePx * WordWaveLaw.GH_EM).toInt()
    val baselineOffset = ceil(fontSizePx * WordWaveLaw.BASE_EM).toInt()
    val probe = AndroidPaint().apply {
        this.textSize = fontSizePx
        this.typeface = Typeface.DEFAULT_BOLD
        this.isAntiAlias = true
    }
    val width = (probe.measureText(word) + 2 * padPx).toInt().coerceAtLeast(1)
    val ink = bakeBitmap(word, fontSizePx, baselineOffset, width, height, padPx, null, color)
    val glowMin = bakeBitmap(
        word, fontSizePx, baselineOffset, width, height, padPx,
        fontSizePx * WordWaveLaw.GLOW_R_MIN, color,
    )
    val glowMax = bakeBitmap(
        word, fontSizePx, baselineOffset, width, height, padPx,
        fontSizePx * WordWaveLaw.GLOW_R_MAX, color,
    )
    val sliceW = kotlin.math.max(2, WordWaveLaw.SLICE_PX.roundToInt())
    return WordCache(ink, glowMin, glowMax, width, height, sliceW, padPx)
}

/**
 * One lyric line: held words get the letter-wave effect (Latin per letter,
 * Arabic per slice), short words get a plain fade.
 *
 * [nowMs] is the audio-clock position. Vertically aligned: every word's
 * baseline sits at 1.3em within the 1.8em-tall canvas, so waved and plain
 * words in one line never jump.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun WordWaveLine(
    words: List<LyricWord>,
    nowMs: Long,
    isRtl: Boolean,
    fontSize: TextUnit = 20.sp,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) return

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = remember(fontSize, color) {
        TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = color)
    }
    val paint = remember { AndroidPaint().apply { isAntiAlias = true } }
    val textSizePx = with(density) { fontSize.toPx() }
    paint.textSize = textSizePx
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.color = color.toArgb()

    val height = (fontSize.value * WordWaveLaw.GH_EM).toInt()

    val spaceWidth = textMeasurer.measure(" ", style).size.width.toFloat()
    val measured = words.map { word ->
        word to textMeasurer.measure(word.text, style).size.width.toFloat()
    }
    val totalWidth = measured.sumOf { it.second.toDouble() }.toFloat() +
        spaceWidth * (measured.size - 1).coerceAtLeast(0)

    val durations = words.filter { it.endMs > it.startMs }.map { it.endMs - it.startMs }
    val medianMs = WordWaveLaw.median(durations)

    val cacheKey = words.joinToString(" ") { it.text }
    val caches = remember(cacheKey, textSizePx) { mutableMapOf<String, WordCache>() }

    val baselineY = WordWaveLaw.BASE_EM * textSizePx

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
    ) {
        var cursorX = if (isRtl) (size.width + totalWidth) / 2f
                      else (size.width - totalWidth) / 2f
        val direction = if (isRtl) -1f else 1f

        for ((_, pair) in measured.withIndex()) {
            val word = pair.first
            val width = pair.second

            val drawX = if (isRtl) cursorX - width else cursorX
            val n = word.text.length
            val wave = WordWaveLaw.isWaveWord(n, word.endMs - word.startMs, medianMs)

            if (wave) {
                if (isRtl) {
                    val cache = caches.getOrPut(word.text) {
                        bakeCache(word.text, textSizePx, color)
                    }
                    drawArabicWave(word, cache, nowMs, drawX, baselineY, paint, drawContext.canvas.nativeCanvas)
                } else {
                    drawLatinWave(word, nowMs, drawX, baselineY, textSizePx, paint, color, drawContext.canvas.nativeCanvas)
                }
            } else {
                val alpha = WordWaveLaw.plainAlpha(nowMs.toFloat(), word.startMs.toFloat())
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                paint.clearShadowLayer()
                drawContext.canvas.nativeCanvas.drawText(word.text, drawX, baselineY, paint)
                paint.alpha = 255
            }
            cursorX += direction * (width + spaceWidth)
        }
    }
}

/** Latin wave: real per-letter drawing with translate / scale / shadow. */
private fun drawLatinWave(
    word: LyricWord,
    nowMs: Long,
    x: Float,
    baselineY: Float,
    textSizePx: Float,
    paint: AndroidPaint,
    color: Color,
    canvas: AndroidCanvas,
) {
    val cells = mutableListOf(0f)
    for (i in 1..word.text.length) {
        cells.add(paint.measureText(word.text.substring(0, i)))
    }
    val n = word.text.length
    val startMs = word.startMs.toFloat()
    val hold = (word.endMs - word.startMs).toFloat()

    for (i in 0 until n) {
        val st = WordWaveLaw.LetterState()
        WordWaveLaw.letterState(nowMs.toFloat(), startMs, hold, i, n, st)

        val cx = x + (cells[i] + cells[i + 1]) / 2f
        val liftPx = st.lift * textSizePx
        val glowPx = (WordWaveLaw.GLOW_R_MIN +
            (WordWaveLaw.GLOW_R_MAX - WordWaveLaw.GLOW_R_MIN) * st.gm) * textSizePx

        canvas.save()
        canvas.translate(cx, baselineY - liftPx)
        canvas.scale(st.sc, st.sc)
        paint.alpha = (st.lit * 255f).toInt().coerceIn(0, 255)
        if (st.ga > 0.01f) {
            paint.setShadowLayer(glowPx, 0f, 0f, color.copy(alpha = st.ga).toArgb())
        } else {
            paint.clearShadowLayer()
        }
        canvas.drawText(word.text[i].toString(), -(cells[i + 1] - cells[i]) / 2f, 0f, paint)
        canvas.restore()
    }
    paint.alpha = 255
    paint.clearShadowLayer()
}

/** Arabic wave: per-slice drawing from pre-baked bitmaps. */
private fun drawArabicWave(
    word: LyricWord,
    cache: WordCache,
    nowMs: Long,
    drawX: Float,
    baselineY: Float,
    paint: AndroidPaint,
    canvas: AndroidCanvas,
) {
    val n = word.text.length
    val startMs = word.startMs.toFloat()
    val hold = (word.endMs - word.startMs).toFloat()

    val st = Array(n) { WordWaveLaw.LetterState() }
    for (i in 0 until n) {
        WordWaveLaw.letterState(nowMs.toFloat(), startMs, hold, i, n, st[i])
    }

    val slW = cache.sliceW
    val slN = cache.sliceCount
    val wordWidth = cache.width - 2 * cache.padPx.toInt()

    val liftArr = FloatArray(slN)
    val gaArr = FloatArray(slN)
    val gmArr = FloatArray(slN)
    val litArr = FloatArray(slN)

    for (k in 0 until slN) {
        val xc = (k * slW + slW / 2f) - cache.padPx
        val uRaw = ((wordWidth - xc) / wordWidth).coerceIn(0f, 1f)
        val pos = (uRaw * n - 0.5f).coerceIn(0f, (n - 1).toFloat())
        val i0 = min(n - 1, pos.toInt())
        val i1 = min(n - 1, i0 + 1)
        val f = WordWaveLaw.smooth(pos - i0)
        val A = st[i0]
        val B = st[i1]
        liftArr[k] = A.lift + (B.lift - A.lift) * f
        gaArr[k] = A.ga + (B.ga - A.ga) * f
        gmArr[k] = A.gm + (B.gm - A.gm) * f
        litArr[k] = A.lit + (B.lit - A.lit) * f
    }

    fun slice(img: Bitmap, k: Int, alpha: Float) {
        val sw = (cache.width - k * slW).coerceAtMost(slW)
        if (sw <= 0) return
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val liftPx = liftArr[k] * (cache.height / WordWaveLaw.GH_EM)
        val dstX = drawX - cache.padPx + k * slW
        val dstY = baselineY - liftPx - cache.baselineOffset
        canvas.drawBitmap(
            img,
            AndroidRect(k * slW, 0, k * slW + sw, img.height),
            AndroidRect(
                dstX.roundToInt(),
                dstY.roundToInt(),
                (dstX + sw + 0.35f).roundToInt(),
                (dstY + img.height).roundToInt(),
            ),
            paint,
        )
    }

    for (k in 0 until slN) {
        val a = gaArr[k]
        if (a < 0.01f) continue
        slice(cache.glowMin, k, a * (1f - gmArr[k]))
        slice(cache.glowMax, k, a * gmArr[k])
    }
    for (k in 0 until slN) slice(cache.ink, k, litArr[k])
    paint.alpha = 255
}
