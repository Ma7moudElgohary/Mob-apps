package com.ma7moud.reality3d.segmentation

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MaskOps {
    enum class BrushMode { ADD, ERASE }

    fun brush(mask: SubjectMask, u: Float, v: Float, radiusNormalized: Float, mode: BrushMode): SubjectMask {
        val values = mask.confidence.copyOf()
        val cx = u.coerceIn(0f, 1f) * (mask.width - 1)
        val cy = v.coerceIn(0f, 1f) * (mask.height - 1)
        val radius = max(2f, radiusNormalized * min(mask.width, mask.height))
        val minX = max(0, (cx - radius).toInt())
        val maxX = min(mask.width - 1, (cx + radius).toInt())
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(mask.height - 1, (cy + radius).toInt())
        for (y in minY..maxY) for (x in minX..maxX) {
            val dx = x - cx
            val dy = y - cy
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= radius) {
                val strength = (1f - distance / radius).coerceIn(0f, 1f)
                val i = y * mask.width + x
                values[i] = when (mode) {
                    BrushMode.ADD -> max(values[i], strength)
                    BrushMode.ERASE -> min(values[i], 1f - strength)
                }
            }
        }
        return SubjectMask(mask.width, mask.height, values)
    }

    fun feather(mask: SubjectMask, radius: Int = 3): SubjectMask {
        val r = radius.coerceIn(1, 8)
        val result = FloatArray(mask.confidence.size)
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            var weighted = 0f
            var weights = 0f
            for (dy in -r..r) for (dx in -r..r) {
                val nx = (x + dx).coerceIn(0, mask.width - 1)
                val ny = (y + dy).coerceIn(0, mask.height - 1)
                val d2 = (dx * dx + dy * dy).toFloat()
                val w = exp((-d2 / (2f * r * r)).toDouble()).toFloat()
                weighted += mask[nx, ny] * w
                weights += w
            }
            result[y * mask.width + x] = if (weights == 0f) mask[x, y] else weighted / weights
        }
        return SubjectMask(mask.width, mask.height, result)
    }
}
