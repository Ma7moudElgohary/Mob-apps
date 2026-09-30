package com.ma7moud.reality3d.quality

import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How well one photo will turn into a 3D model: sharp, well exposed, the object well framed and cleanly
 * separated from its background. Works on the photo scaled to about [ANALYSIS_SIZE] pixels.
 */
object PhotoQuality {

    /** The long side photos are scaled to before measuring, so the numbers don't depend on resolution. */
    const val ANALYSIS_SIZE = 512

    class Measures(
        /** Variance of the Laplacian over the object: low when blurry. */
        val sharpness: Float,
        /** Mean brightness of the object, 0..1. */
        val brightness: Float,
        /** Share of the object's pixels that are black or blown out. */
        val clipped: Float,
        /** Share of the photo the object covers; null without a mask. */
        val coverage: Float?,
        /** Share of the photo's edge the object touches, i.e. is cut off at. */
        val cutOff: Float,
        /** Undecided mask pixels per object pixel: high when the outline is fuzzy; null without a mask. */
        val uncertain: Float?,
    )

    fun measure(argb: IntArray, width: Int, height: Int, mask: SubjectMask?): Measures {
        require(argb.size == width * height)
        val gray = FloatArray(argb.size) { i ->
            val c = argb[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        val inside = if (mask == null) null else FloatArray(argb.size) { i -> mask.sample(sampleU(i % width, width), sampleV(i / width, height)) }

        // Sharpness over the object's box (the middle of the photo without a mask).
        val box = mask?.bounds()
        val x0 = if (box != null) (box[0] * width).toInt() else width / 5
        val y0 = if (box != null) (box[1] * height).toInt() else height / 5
        val x1 = if (box != null) (box[2] * width).toInt() else width * 4 / 5
        val y1 = if (box != null) (box[3] * height).toInt() else height * 4 / 5
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in max(1, y0) until min(height - 1, y1)) {
            for (x in max(1, x0) until min(width - 1, x1)) {
                val i = y * width + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val sharpness = if (n == 0) 0f else (sumSq / n - (sum / n) * (sum / n)).toFloat()

        var brightSum = 0.0
        var clipped = 0
        var counted = 0
        var subject = 0
        var undecided = 0
        for (i in argb.indices) {
            val c = inside?.get(i)
            if (c != null) {
                if (c > 0.15f && c < 0.85f) undecided++
                if (c >= 0.5f) subject++
            }
            if (c != null && c < 0.5f) continue
            val g = gray[i] / 255f
            brightSum += g
            if (g < 0.02f || g > 0.98f) clipped++
            counted++
        }
        var edge = 0
        var touching = 0
        if (inside != null) {
            for (x in 0 until width) {
                edge += 2
                if (inside[x] >= 0.5f) touching++
                if (inside[(height - 1) * width + x] >= 0.5f) touching++
            }
            for (y in 1 until height - 1) {
                edge += 2
                if (inside[y * width] >= 0.5f) touching++
                if (inside[y * width + width - 1] >= 0.5f) touching++
            }
        }
        return Measures(
            sharpness = sharpness,
            brightness = if (counted == 0) 0f else (brightSum / counted).toFloat(),
            clipped = if (counted == 0) 0f else clipped.toFloat() / counted,
            coverage = if (inside == null) null else subject.toFloat() / argb.size,
            cutOff = if (edge == 0) 0f else touching.toFloat() / edge,
            uncertain = if (inside == null) null else undecided.toFloat() / max(1, subject),
        )
    }

    fun assess(argb: IntArray, width: Int, height: Int, mask: SubjectMask?, insight: ObjectInsight? = null): QualityReport =
        assess(measure(argb, width, height, mask), insight)

    fun assess(m: Measures, insight: ObjectInsight? = null): QualityReport {
        val issues = ArrayList<String>()
        val sharp = ((ln(max(m.sharpness, 1f)) - ln(BLURRY)) / (ln(SHARP) - ln(BLURRY))).coerceIn(0f, 1f)
        if (sharp < 0.5f) issues += "The photo looks blurry. Hold the phone steady and tap the object to focus."

        val exposure = (1f - max(0f, 0.3f - m.brightness) / 0.15f - max(0f, m.brightness - 0.82f) / 0.15f - max(0f, m.clipped - 0.05f) * 4f)
            .coerceIn(0f, 1f)
        if (m.brightness < 0.3f) issues += "The photo is dark. Add light or move near a window."
        if (m.brightness > 0.82f || m.clipped > 0.08f) issues += "Parts of the object are washed out or black. Avoid direct sun and flash."

        val coverage = m.coverage
        var framing = 1f
        if (coverage != null) {
            framing -= max(0f, 0.08f - coverage) / 0.06f + max(0f, coverage - 0.7f) / 0.25f
            if (coverage < 0.08f) issues += "The object is small in the photo. Move closer so it fills more of the frame."
            if (coverage > 0.7f) issues += "The object fills almost the whole photo. Step back so all of it fits with a little space around."
        }
        if (m.cutOff > CUT_OFF) {
            framing -= 0.5f
            issues += "Part of the object runs off the edge of the photo, so that side will be cut flat."
        }
        framing = framing.coerceIn(0f, 1f)

        val uncertain = m.uncertain
        val separation = when {
            uncertain == null -> 0f
            else -> (1f - max(0f, uncertain - 0.08f) / 0.2f).coerceIn(0f, 1f)
        }
        if (uncertain == null) {
            issues += "The object couldn't be separated from the background. A plain background that contrasts with it helps."
        } else if (uncertain > 0.15f) {
            issues += "The object's outline is uncertain. A plain background that contrasts with it helps."
        }

        // Blur and bad light spoil everything else (depth, outline, texture), so they scale the score down.
        val gate = (0.55f + 0.45f * sharp) * (0.7f + 0.3f * exposure)
        var score = (30f * sharp + 20f * exposure + 25f * framing + 25f * separation) * gate
        if (insight?.reflective == true) {
            score -= 8f
            issues += "It's shiny: reflections can bend the estimated depth."
        }
        if (insight?.transparent == true) {
            score -= 12f
            issues += "It's see-through: depth can't be measured through it."
        }
        return QualityReport(score.roundToInt().coerceIn(0, 100), issues)
    }

    private fun sampleU(x: Int, width: Int) = x.toFloat() / max(1, width - 1)

    private fun sampleV(y: Int, height: Int) = y.toFloat() / max(1, height - 1)

    // Laplacian variance at ANALYSIS_SIZE: below BLURRY a photo is clearly soft, above SHARP it is crisp.
    private const val BLURRY = 25f
    private const val SHARP = 250f
    private const val CUT_OFF = 0.02f
}
