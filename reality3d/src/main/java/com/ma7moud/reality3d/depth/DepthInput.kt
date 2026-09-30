package com.ma7moud.reality3d.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where a photo goes in the depth model's fixed input: the part of the photo that is used ([cropLeft]
 * .. [cropBottom], in photo pixels) and where it lands in the input without being stretched
 * ([contentX], [contentY], [contentWidth], [contentHeight]); the rest of the input repeats its edges.
 */
class DepthInputPlan(
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val contentX: Int,
    val contentY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
) {
    val cropWidth: Int get() = cropRight - cropLeft
    val cropHeight: Int get() = cropBottom - cropTop

    companion object {
        /**
         * Frames the subject ([focus]: left, top, right, bottom in normalised photo coordinates, or null
         * for the whole photo) with a margin, grows the frame towards the input's shape where the photo
         * allows it, and fits it into [inputWidth] × [inputHeight] keeping its proportions.
         */
        fun create(photoWidth: Int, photoHeight: Int, focus: FloatArray?, inputWidth: Int, inputHeight: Int, margin: Float = 0.08f): DepthInputPlan {
            var x0: Float
            var y0: Float
            var x1: Float
            var y1: Float
            if (focus == null) {
                x0 = 0f
                y0 = 0f
                x1 = photoWidth.toFloat()
                y1 = photoHeight.toFloat()
            } else {
                x0 = focus[0].coerceIn(0f, 1f) * photoWidth
                y0 = focus[1].coerceIn(0f, 1f) * photoHeight
                x1 = focus[2].coerceIn(0f, 1f) * photoWidth
                y1 = focus[3].coerceIn(0f, 1f) * photoHeight
                val padX = max(x1 - x0, MIN_FOCUS_PX) * margin
                val padY = max(y1 - y0, MIN_FOCUS_PX) * margin
                x0 -= padX
                x1 += padX
                y0 -= padY
                y1 += padY
                // Very small subjects still get enough context around them for the model.
                val minSide = min(photoWidth, photoHeight) * MIN_CROP_FRACTION
                if (x1 - x0 < minSide) {
                    val cx = (x0 + x1) / 2
                    x0 = cx - minSide / 2
                    x1 = cx + minSide / 2
                }
                if (y1 - y0 < minSide) {
                    val cy = (y0 + y1) / 2
                    y0 = cy - minSide / 2
                    y1 = cy + minSide / 2
                }
            }
            val target = inputWidth.toFloat() / inputHeight
            if ((x1 - x0) / (y1 - y0) < target) {
                val grow = (y1 - y0) * target - (x1 - x0)
                x0 -= grow / 2
                x1 += grow / 2
            } else {
                val grow = (x1 - x0) / target - (y1 - y0)
                y0 -= grow / 2
                y1 += grow / 2
            }
            val (left, right) = fit(x0, x1, photoWidth)
            val (top, bottom) = fit(y0, y1, photoHeight)
            val cropWidth = right - left
            val cropHeight = bottom - top
            val scale = min(inputWidth.toFloat() / cropWidth, inputHeight.toFloat() / cropHeight)
            val contentWidth = (cropWidth * scale).roundToInt().coerceIn(1, inputWidth)
            val contentHeight = (cropHeight * scale).roundToInt().coerceIn(1, inputHeight)
            return DepthInputPlan(
                left, top, right, bottom,
                (inputWidth - contentWidth) / 2, (inputHeight - contentHeight) / 2, contentWidth, contentHeight,
            )
        }

        /** Slides [from]..[to] inside 0..[size] (shrinking it only if it is bigger than the photo). */
        private fun fit(from: Float, to: Float, size: Int): Pair<Int, Int> {
            var start = from
            var end = to
            if (end - start >= size) return 0 to size
            if (start < 0f) {
                end -= start
                start = 0f
            }
            if (end > size) {
                start -= end - size
                end = size.toFloat()
            }
            val first = start.toInt().coerceIn(0, size - 1)
            val last = end.roundToInt().coerceIn(first + 1, size)
            return first to last
        }

        private const val MIN_FOCUS_PX = 8f
        private const val MIN_CROP_FRACTION = 0.25f
    }
}

internal object DepthTensors {

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Packs ARGB [content] pixels ([plan]'s content size, already resized) into a normalised RGB tensor in
     * NCHW order of [inputWidth] × [inputHeight], repeating the content's edges into the margins.
     */
    fun pack(content: IntArray, plan: DepthInputPlan, inputWidth: Int, inputHeight: Int, out: FloatArray = FloatArray(3 * inputWidth * inputHeight)): FloatArray {
        require(content.size == plan.contentWidth * plan.contentHeight)
        val plane = inputWidth * inputHeight
        for (y in 0 until inputHeight) {
            val sy = (y - plan.contentY).coerceIn(0, plan.contentHeight - 1)
            for (x in 0 until inputWidth) {
                val sx = (x - plan.contentX).coerceIn(0, plan.contentWidth - 1)
                val pixel = content[sy * plan.contentWidth + sx]
                val index = y * inputWidth + x
                out[index] = (((pixel shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
                out[plane + index] = (((pixel shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                out[2 * plane + index] = ((pixel and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
        }
        return out
    }

    /**
     * The part of the model's output that shows the photo, as a [DepthMap] placed over the cropped
     * region of a [photoWidth] × [photoHeight] photo. Values that are not finite become the median.
     */
    fun unpack(output: FloatArray, plan: DepthInputPlan, inputWidth: Int, photoWidth: Int, photoHeight: Int): DepthMap {
        val values = FloatArray(plan.contentWidth * plan.contentHeight)
        for (y in 0 until plan.contentHeight) {
            System.arraycopy(output, (plan.contentY + y) * inputWidth + plan.contentX, values, y * plan.contentWidth, plan.contentWidth)
        }
        var bad = 0
        for (value in values) if (!value.isFinite()) bad++
        if (bad > 0) {
            val finite = values.filter { it.isFinite() }.sorted()
            val median = if (finite.isEmpty()) 0f else finite[finite.size / 2]
            for (i in values.indices) if (!values[i].isFinite()) values[i] = median
        }
        return DepthMap(
            plan.contentWidth, plan.contentHeight, values,
            left = plan.cropLeft.toFloat() / photoWidth,
            top = plan.cropTop.toFloat() / photoHeight,
            right = plan.cropRight.toFloat() / photoWidth,
            bottom = plan.cropBottom.toFloat() / photoHeight,
        )
    }

    /**
     * Whether an accelerator's output matches the CPU's closely enough to trust it. Relative depth only
     * matters up to scale and offset, so both maps are compared as z-scores: they must correlate and
     * differ on average by only a fraction of a standard deviation. GPUs running at reduced precision
     * give visibly wrong maps that fail this; int8 weights computed in fp32 pass.
     */
    fun agree(reference: FloatArray, candidate: FloatArray, minCorrelation: Double = 0.98, maxZError: Double = 0.2): Boolean {
        if (reference.size != candidate.size || reference.isEmpty()) return false
        for (value in candidate) if (!value.isFinite()) return false
        val step = max(1, reference.size / 20_000)
        var n = 0
        var sa = 0.0
        var sb = 0.0
        var i = 0
        while (i < reference.size) {
            val a = reference[i]
            val b = candidate[i]
            if (!a.isFinite()) return false
            sa += a
            sb += b
            n++
            i += step
        }
        val ma = sa / n
        val mb = sb / n
        var va = 0.0
        var vb = 0.0
        var cov = 0.0
        i = 0
        while (i < reference.size) {
            val da = reference[i] - ma
            val db = candidate[i] - mb
            va += da * da
            vb += db * db
            cov += da * db
            i += step
        }
        if (va <= 1e-12 || vb <= 1e-12) return false
        val sdA = sqrt(va / n)
        val sdB = sqrt(vb / n)
        var error = 0.0
        i = 0
        while (i < reference.size) {
            error += abs((reference[i] - ma) / sdA - (candidate[i] - mb) / sdB)
            i += step
        }
        return cov / sqrt(va * vb) >= minCorrelation && error / n <= maxZError
    }
}
