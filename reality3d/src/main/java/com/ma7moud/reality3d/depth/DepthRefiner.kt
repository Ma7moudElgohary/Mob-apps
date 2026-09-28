package com.ma7moud.reality3d.depth

import android.graphics.Bitmap
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.abs
import kotlin.math.exp

object DepthRefiner {
    fun edgeAware(
        source: DepthMap,
        bitmap: Bitmap,
        mask: SubjectMask?,
        passes: Int = 2,
    ): DepthMap {
        val scaled = Bitmap.createScaledBitmap(bitmap, source.width, source.height, true)
        val pixels = IntArray(source.width * source.height)
        scaled.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val luminance = FloatArray(pixels.size) { index ->
            val p = pixels[index]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        if (scaled !== bitmap) scaled.recycle()

        var current = source.values.copyOf()
        repeat(passes.coerceIn(1, 4)) {
            val next = current.copyOf()
            for (y in 1 until source.height - 1) {
                for (x in 1 until source.width - 1) {
                    val index = y * source.width + x
                    val maskConfidence = mask?.sampleNormalized(
                        x.toFloat() / (source.width - 1),
                        y.toFloat() / (source.height - 1),
                    ) ?: 1f
                    if (maskConfidence < 0.08f) continue
                    val centerDepth = current[index]
                    val centerLum = luminance[index]
                    var sum = 0f
                    var weightSum = 0f
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val ni = (y + dy) * source.width + (x + dx)
                            val spatial = when (abs(dx) + abs(dy)) {
                                0 -> 1f
                                1 -> 0.78f
                                2 -> 0.52f
                                else -> 0.28f
                            }
                            val colorWeight = exp((-abs(luminance[ni] - centerLum) * 16f).toDouble()).toFloat()
                            val depthWeight = exp((-abs(current[ni] - centerDepth) * 24f).toDouble()).toFloat()
                            val w = spatial * colorWeight * depthWeight
                            sum += current[ni] * w
                            weightSum += w
                        }
                    }
                    if (weightSum > 1e-5f) next[index] = sum / weightSum
                }
            }
            current = next
        }
        return DepthMap(source.width, source.height, current)
    }
}
