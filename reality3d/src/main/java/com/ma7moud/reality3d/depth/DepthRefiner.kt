package com.ma7moud.reality3d.depth

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Edge-aware clean-up of a depth map: a joint bilateral filter guided by the photo and the subject mask.
 *
 * Noise inside a surface is smoothed away, but depth is never averaged across the subject's outline
 * (which melts the object into the background) or across a jump in depth, and only lightly across
 * edges in the photo, so printed patterns don't turn into geometry.
 */
object DepthRefiner {

    private const val RADIUS = 3
    private const val SPATIAL_SIGMA = 2f

    /** Colour difference (0..√3 in RGB) at which a neighbour's weight falls to 1/e². */
    private const val COLOR_SIGMA = 0.16f

    /** Depth difference, as a share of the map's robust range, at which a neighbour's weight falls to 1/e². */
    private const val DEPTH_SIGMA = 0.05f

    private const val LUT_SIZE = 1024

    /**
     * [guide] holds ARGB colours and [subject] (optional) the mask confidence, both at the depth map's
     * resolution. Returns a new map over the same region.
     */
    fun refine(depth: DepthMap, guide: IntArray, subject: FloatArray?, passes: Int = 2): DepthMap {
        val w = depth.width
        val h = depth.height
        require(guide.size == w * h && (subject == null || subject.size == w * h))
        val range = max(depth.robustRange, 1e-6f)
        val red = FloatArray(w * h) { ((guide[it] shr 16) and 0xFF) / 255f }
        val green = FloatArray(w * h) { ((guide[it] shr 8) and 0xFF) / 255f }
        val blue = FloatArray(w * h) { (guide[it] and 0xFF) / 255f }
        val label = subject?.let { mask -> BooleanArray(w * h) { mask[it] >= 0.5f } }

        val spatial = FloatArray((2 * RADIUS + 1) * (2 * RADIUS + 1))
        for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
            spatial[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] = exp(-(dx * dx + dy * dy) / (2f * SPATIAL_SIGMA * SPATIAL_SIGMA))
        }
        // Range kernels as tables over squared differences, clamped at the far end.
        val colorMax = 3f
        val colorTable = FloatArray(LUT_SIZE) { exp(-(it * colorMax / (LUT_SIZE - 1)) / (2f * COLOR_SIGMA * COLOR_SIGMA)) }
        val depthMax = 16f * DEPTH_SIGMA * DEPTH_SIGMA
        val depthTable = FloatArray(LUT_SIZE) { exp(-(it * depthMax / (LUT_SIZE - 1)) / (2f * DEPTH_SIGMA * DEPTH_SIGMA)) }

        var current = depth.values
        repeat(passes) {
            val next = FloatArray(w * h)
            for (y in 0 until h) {
                val y0 = max(0, y - RADIUS)
                val y1 = min(h - 1, y + RADIUS)
                for (x in 0 until w) {
                    val c = y * w + x
                    val x0 = max(0, x - RADIUS)
                    val x1 = min(w - 1, x + RADIUS)
                    val centerDepth = current[c]
                    val centerLabel = label?.get(c)
                    var sum = 0f
                    var weights = 0f
                    for (ny in y0..y1) {
                        val rowOffset = (ny - y + RADIUS) * (2 * RADIUS + 1) - x + RADIUS
                        for (nx in x0..x1) {
                            val k = ny * w + nx
                            if (centerLabel != null && label[k] != centerLabel) continue
                            val dr = red[k] - red[c]
                            val dg = green[k] - green[c]
                            val db = blue[k] - blue[c]
                            val color = (dr * dr + dg * dg + db * db) / colorMax * (LUT_SIZE - 1)
                            val dd = (current[k] - centerDepth) / range
                            val depthIndex = dd * dd / depthMax * (LUT_SIZE - 1)
                            val weight = spatial[rowOffset + nx] *
                                colorTable[min(color.toInt(), LUT_SIZE - 1)] *
                                depthTable[min(depthIndex.toInt(), LUT_SIZE - 1)]
                            sum += current[k] * weight
                            weights += weight
                        }
                    }
                    next[c] = if (weights > 0f) sum / weights else centerDepth
                }
            }
            current = next
        }
        return DepthMap(w, h, current, depth.left, depth.top, depth.right, depth.bottom)
    }
}
