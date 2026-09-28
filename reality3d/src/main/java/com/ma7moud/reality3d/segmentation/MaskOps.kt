package com.ma7moud.reality3d.segmentation

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MaskOps {
    enum class BrushMode { ADD, ERASE }

    fun brush(
        mask: SubjectMask,
        u: Float,
        v: Float,
        radiusNormalized: Float,
        mode: BrushMode,
    ): SubjectMask {
        val values = mask.confidence.copyOf()
        val cx = u.coerceIn(0f, 1f) * (mask.width - 1)
        val cy = v.coerceIn(0f, 1f) * (mask.height - 1)
        val radius = max(2f, radiusNormalized * min(mask.width, mask.height))
        val minX = max(0, (cx - radius).toInt())
        val maxX = min(mask.width - 1, (cx + radius).toInt())
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(mask.height - 1, (cy + radius).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - cx
                val dy = y - cy
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= radius) {
                    val strength = (1f - distance / radius).coerceIn(0f, 1f)
                    val index = y * mask.width + x
                    values[index] = when (mode) {
                        BrushMode.ADD -> max(values[index], strength)
                        BrushMode.ERASE -> min(values[index], 1f - strength)
                    }
                }
            }
        }
        return SubjectMask(mask.width, mask.height, values)
    }

    fun feather(mask: SubjectMask, radius: Int = 3): SubjectMask {
        val r = radius.coerceIn(1, 8)
        val result = FloatArray(mask.confidence.size)
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                var weighted = 0f
                var weights = 0f
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val nx = (x + dx).coerceIn(0, mask.width - 1)
                        val ny = (y + dy).coerceIn(0, mask.height - 1)
                        val distanceSquared = (dx * dx + dy * dy).toFloat()
                        val weight = exp((-distanceSquared / (2f * r * r)).toDouble()).toFloat()
                        weighted += mask[nx, ny] * weight
                        weights += weight
                    }
                }
                result[y * mask.width + x] =
                    if (weights == 0f) mask[x, y] else weighted / weights
            }
        }
        return SubjectMask(mask.width, mask.height, result)
    }

    fun autoRefine(mask: SubjectMask): SubjectMask {
        // Morphological close fills tiny mask gaps; the following open removes isolated specks.
        val closed = erode(dilate(mask, radius = 2), radius = 2)
        val opened = dilate(erode(closed, radius = 1), radius = 1)
        val feathered = feather(opened, radius = 2)
        val result = FloatArray(mask.confidence.size)
        for (i in result.indices) {
            // Retain some of the model's original confidence so thin structures are not erased.
            result[i] = max(feathered.confidence[i], mask.confidence[i] * 0.55f).coerceIn(0f, 1f)
        }
        return SubjectMask(mask.width, mask.height, result)
    }

    private fun dilate(mask: SubjectMask, radius: Int): SubjectMask {
        val values = FloatArray(mask.confidence.size)
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                var value = 0f
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, mask.width - 1)
                        val ny = (y + dy).coerceIn(0, mask.height - 1)
                        value = max(value, mask[nx, ny])
                    }
                }
                values[y * mask.width + x] = value
            }
        }
        return SubjectMask(mask.width, mask.height, values)
    }

    private fun erode(mask: SubjectMask, radius: Int): SubjectMask {
        val values = FloatArray(mask.confidence.size)
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                var value = 1f
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, mask.width - 1)
                        val ny = (y + dy).coerceIn(0, mask.height - 1)
                        value = min(value, mask[nx, ny])
                    }
                }
                values[y * mask.width + x] = value
            }
        }
        return SubjectMask(mask.width, mask.height, values)
    }
}
