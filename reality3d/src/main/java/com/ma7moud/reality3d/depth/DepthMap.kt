package com.ma7moud.reality3d.depth

import kotlin.math.floor

/**
 * Relative inverse depth (bigger = closer), row-major with the origin at the top-left.
 *
 * The map may cover only part of the photo: [left], [top], [right] and [bottom] give that region in
 * normalised photo coordinates (0..1). Samples outside it repeat the region's edge.
 */
class DepthMap(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(width > 0 && height > 0 && values.size == width * height) { "Depth map size mismatch" }
        require(right > left && bottom > top) { "Depth map region is empty" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /** Depth pixels across the whole photo, for turning a blur in depth pixels into photo units. */
    val photoWidth: Float get() = width / (right - left)
    val photoHeight: Float get() = height / (bottom - top)

    /** Bilinear sample at normalised photo coordinates (0..1 on both axes). */
    fun sample(u: Float, v: Float): Float =
        bilinear(values, width, height, (u - left) / (right - left), (v - top) / (bottom - top))

    /** Spread of the map's depth between the 2nd and 98th percentiles. */
    val robustRange: Float by lazy {
        val sorted = values.copyOf().also { it.sort() }
        sorted[(sorted.size - 1) * 98 / 100] - sorted[(sorted.size - 1) * 2 / 100]
    }
}

internal fun bilinear(values: FloatArray, width: Int, height: Int, u: Float, v: Float): Float {
    val x = u.coerceIn(0f, 1f) * (width - 1)
    val y = v.coerceIn(0f, 1f) * (height - 1)
    val x0 = floor(x).toInt().coerceIn(0, width - 1)
    val y0 = floor(y).toInt().coerceIn(0, height - 1)
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = x - x0
    val fy = y - y0
    val top = values[y0 * width + x0] * (1 - fx) + values[y0 * width + x1] * fx
    val bottom = values[y1 * width + x0] * (1 - fx) + values[y1 * width + x1] * fx
    return top * (1 - fy) + bottom * fy
}
