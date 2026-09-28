package com.ma7moud.reality3d.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colours every vertex from the scan photos: each photo that sees the vertex (checked against a small
 * depth buffer of the mesh itself) adds its colour, weighted towards photos that face the surface
 * squarely, are close, and have the vertex away from the frame's edges.
 */
object VertexColorizer {

    private const val BUFFER_WIDTH = 96
    private const val MIN_FACING = 0.15f

    /** sRGB colour (0..1) per vertex. Vertices no photo saw take their neighbours' colours. */
    fun colorize(positions: FloatArray, normals: FloatArray, indices: IntArray, keyframes: List<KeyframeImage>, tolerance: Float): FloatArray =
        colorize(positions, normals, indices, keyframes.size, { keyframes[it] }, tolerance)

    /** Like the list version, but decodes one photo at a time through [frame] to save memory. */
    fun colorize(
        positions: FloatArray,
        normals: FloatArray,
        indices: IntArray,
        frameCount: Int,
        frame: (Int) -> KeyframeImage?,
        tolerance: Float,
    ): FloatArray {
        val count = positions.size / 3
        val sum = FloatArray(count * 3)
        val weights = FloatArray(count)
        val u = FloatArray(count)
        val v = FloatArray(count)
        val depth = FloatArray(count)
        val projected = FloatArray(3)
        for (frameIndex in 0 until frameCount) {
            val frame = frame(frameIndex) ?: continue
            val width = frame.width
            val height = frame.height
            val bufferWidth = BUFFER_WIDTH
            val bufferHeight = max(1, (BUFFER_WIDTH.toFloat() * height / width).roundToInt())
            val buffer = FloatArray(bufferWidth * bufferHeight) { Float.MAX_VALUE }
            for (i in 0 until count) {
                depth[i] = -1f
                if (!frame.pose.project(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2], frame.intrinsics, projected)) continue
                val x = projected[0]
                val y = projected[1]
                if (x < 0f || y < 0f || x > width - 1f || y > height - 1f) continue
                u[i] = x
                v[i] = y
                depth[i] = projected[2]
                val bx = (x * bufferWidth / width).toInt()
                val by = (y * bufferHeight / height).toInt()
                // Splat 2×2 so the buffer has no holes between vertices.
                for (oy in 0..1) {
                    for (ox in 0..1) {
                        val sx = bx + ox
                        val sy = by + oy
                        if (sx < bufferWidth && sy < bufferHeight) {
                            val slot = sy * bufferWidth + sx
                            if (projected[2] < buffer[slot]) buffer[slot] = projected[2]
                        }
                    }
                }
            }
            val border = 0.08f * width
            for (i in 0 until count) {
                val d = depth[i]
                if (d < 0f) continue
                val bx = (u[i] * bufferWidth / width).toInt().coerceAtMost(bufferWidth - 1)
                val by = (v[i] * bufferHeight / height).toInt().coerceAtMost(bufferHeight - 1)
                if (d > buffer[by * bufferWidth + bx] + tolerance) continue
                val toCamera = frame.pose.directionFrom(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                val facing = normals[i * 3] * toCamera[0] + normals[i * 3 + 1] * toCamera[1] + normals[i * 3 + 2] * toCamera[2]
                if (facing <= MIN_FACING) continue
                val edge = min(min(u[i], width - 1f - u[i]), min(v[i], height - 1f - v[i]))
                val edgeWeight = min(1f, edge / border)
                val squared = facing * facing
                val weight = squared * squared * edgeWeight / (d * d)
                if (weight <= 0f) continue
                val color = sample(frame, u[i], v[i])
                sum[i * 3] += weight * ((color shr 16) and 0xFF)
                sum[i * 3 + 1] += weight * ((color shr 8) and 0xFF)
                sum[i * 3 + 2] += weight * (color and 0xFF)
                weights[i] += weight
            }
        }
        val colors = FloatArray(count * 3)
        val known = BooleanArray(count)
        for (i in 0 until count) {
            if (weights[i] <= 0f) continue
            known[i] = true
            for (c in 0 until 3) colors[i * 3 + c] = sum[i * 3 + c] / weights[i] / 255f
        }
        fillUnseen(colors, known, indices)
        return colors
    }

    /** Bilinearly interpolated ARGB colour. */
    private fun sample(frame: KeyframeImage, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, frame.width - 1)
        val y0 = y.toInt().coerceIn(0, frame.height - 1)
        val x1 = min(x0 + 1, frame.width - 1)
        val y1 = min(y0 + 1, frame.height - 1)
        val fx = x - x0
        val fy = y - y0
        val p00 = frame.argb[y0 * frame.width + x0]
        val p10 = frame.argb[y0 * frame.width + x1]
        val p01 = frame.argb[y1 * frame.width + x0]
        val p11 = frame.argb[y1 * frame.width + x1]
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val top = ((p00 shr shift) and 0xFF) * (1 - fx) + ((p10 shr shift) and 0xFF) * fx
            val bottom = ((p01 shr shift) and 0xFF) * (1 - fx) + ((p11 shr shift) and 0xFF) * fx
            result = result or ((top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    /** Spreads colours into vertices no photo saw (under overhangs, the base), then greys what is left. */
    private fun fillUnseen(colors: FloatArray, known: BooleanArray, indices: IntArray) {
        val count = known.size
        if (known.all { it }) return
        val adjacency = MeshOps.neighbours(count, indices)
        for (pass in 0 until 64) {
            var changed = false
            val snapshot = known.copyOf()
            for (vertex in 0 until count) {
                if (snapshot[vertex]) continue
                var r = 0f
                var g = 0f
                var b = 0f
                var n = 0
                for (k in adjacency.offsets[vertex] until adjacency.offsets[vertex + 1]) {
                    val other = adjacency.list[k]
                    if (!snapshot[other]) continue
                    r += colors[other * 3]
                    g += colors[other * 3 + 1]
                    b += colors[other * 3 + 2]
                    n++
                }
                if (n > 0) {
                    colors[vertex * 3] = r / n
                    colors[vertex * 3 + 1] = g / n
                    colors[vertex * 3 + 2] = b / n
                    known[vertex] = true
                    changed = true
                }
            }
            if (!changed) break
        }
        for (vertex in 0 until count) {
            if (known[vertex]) continue
            colors[vertex * 3] = 0.6f
            colors[vertex * 3 + 1] = 0.6f
            colors[vertex * 3 + 2] = 0.6f
        }
    }
}
