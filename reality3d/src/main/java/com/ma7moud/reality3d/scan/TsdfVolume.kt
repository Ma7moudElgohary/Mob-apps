package com.ma7moud.reality3d.scan

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A truncated signed distance field over the scan box, fused from depth maps (KinectFusion style).
 * Positive values are empty space, negative values lie just behind a surface. Voxels further than
 * [truncation] behind the measured surface are left alone, so the inside of the object stays unobserved.
 * Depth that lands on the table only clears the space in front of it (see [ScanBox.TABLE_BAND]).
 */
class TsdfVolume(val box: ScanBox, val resolution: Int = resolutionFor(box.size)) {

    val voxelSize: Float = box.size / resolution
    val originX: Float = box.minX
    val originY: Float = box.bottomY
    val originZ: Float = box.minZ
    val truncation: Float = max(TRUNCATION_VOXELS * voxelSize, MIN_TRUNCATION)

    private val n = resolution
    private val tsdf = FloatArray(n * n * n)
    private val weight = FloatArray(n * n * n)

    @Volatile
    var frames: Int = 0
        private set

    /** Fuses one depth map. With a [pool], z-slabs of the volume are processed in parallel. */
    fun integrate(frame: DepthFrame, pool: ExecutorService? = null, threads: Int = 4) {
        val confidence = pixelWeights(frame)
        val table = tableHits(frame)
        val m = frame.pose.matrix
        val s = voxelSize
        // Camera coordinates of voxel (i, j, l) are base + i·stepX + j·stepY + l·stepZ.
        val ox = originX + s / 2 - m[12]
        val oy = originY + s / 2 - m[13]
        val oz = originZ + s / 2 - m[14]
        val geometry = floatArrayOf(
            m[0] * ox + m[1] * oy + m[2] * oz,
            m[4] * ox + m[5] * oy + m[6] * oz,
            m[8] * ox + m[9] * oy + m[10] * oz,
            s * m[0], s * m[4], s * m[8],
            s * m[1], s * m[5], s * m[9],
            s * m[2], s * m[6], s * m[10],
        )
        if (pool == null || threads <= 1) {
            integrateSlab(0, n, frame, confidence, table, geometry)
        } else {
            val chunk = (n + threads - 1) / threads
            val tasks = (0 until n step chunk).map { from ->
                Callable { integrateSlab(from, min(n, from + chunk), frame, confidence, table, geometry) }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        }
        frames++
    }

    /**
     * How much to trust each depth pixel. Pixels on a depth edge (an object's outline) are skipped: their
     * depth belongs half to the object and half to what is behind it, and would eat into the outline.
     * Surfaces seen at a grazing angle count less (cos² of the angle, from the depth gradient).
     */
    internal fun pixelWeights(frame: DepthFrame): FloatArray {
        val width = frame.width
        val height = frame.height
        val depth = frame.depthMm
        val focal = (frame.intrinsics.fx + frame.intrinsics.fy) / 2
        val weights = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val d = (depth[index].toInt() and 0xFFFF) * 0.001f
                if (d <= 0f) continue
                val jump = max(EDGE_JUMP, EDGE_JUMP_RATIO * d)
                var edge = false
                fun neighbour(nx: Int, ny: Int): Float {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) return d
                    val value = (depth[ny * width + nx].toInt() and 0xFFFF) * 0.001f
                    if (value <= 0f || kotlin.math.abs(value - d) > jump) {
                        edge = true
                        return d
                    }
                    return value
                }
                val left = neighbour(x - 1, y)
                val right = neighbour(x + 1, y)
                val up = neighbour(x, y - 1)
                val down = neighbour(x, y + 1)
                if (edge) continue
                val gx = (right - left) / 2
                val gy = (down - up) / 2
                val slope = (gx * gx + gy * gy) * (focal / d) * (focal / d)
                weights[index] = 1f / (1f + slope)
            }
        }
        return weights
    }

    /**
     * Pixels whose depth lands on the table, or null when there is no table. The table is not part of the
     * model, but the rays that reach it show which space next to and under the object is empty.
     */
    internal fun tableHits(frame: DepthFrame): BooleanArray? {
        val top = (box.floorY ?: return null) + ScanBox.TABLE_BAND
        val k = frame.intrinsics
        val m = frame.pose.matrix
        val hits = BooleanArray(frame.width * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val index = y * frame.width + x
                val d = (frame.depthMm[index].toInt() and 0xFFFF) * 0.001f
                if (d <= 0f) continue
                val cx = (x - k.cx) / k.fx * d
                val cy = (k.cy - y) / k.fy * d
                hits[index] = m[1] * cx + m[5] * cy - m[9] * d + m[13] < top
            }
        }
        return hits
    }

    private fun integrateSlab(fromZ: Int, toZ: Int, frame: DepthFrame, confidence: FloatArray, table: BooleanArray?, g: FloatArray) {
        val k = frame.intrinsics
        val fx = k.fx
        val fy = k.fy
        val ppx = k.cx
        val ppy = k.cy
        val width = frame.width
        val height = frame.height
        val depthMm = frame.depthMm
        val trunc = truncation
        for (l in fromZ until toZ) {
            for (j in 0 until n) {
                var cx = g[0] + j * g[6] + l * g[9]
                var cy = g[1] + j * g[7] + l * g[10]
                var cz = g[2] + j * g[8] + l * g[11]
                var index = (l * n + j) * n
                for (i in 0 until n) {
                    val depth = -cz
                    if (depth > MIN_DEPTH) {
                        val u = fx * cx / depth + ppx
                        val v = ppy - fy * cy / depth
                        if (u >= -0.5f && v >= -0.5f) {
                            val pu = (u + 0.5f).toInt()
                            val pv = (v + 0.5f).toInt()
                            if (pu < width && pv < height) {
                                val pixel = pv * width + pu
                                val raw = depthMm[pixel].toInt() and 0xFFFF
                                val w = confidence[pixel]
                                if (raw != 0 && raw <= MAX_DEPTH_MM && w > 0f) {
                                    val sdf = raw * 0.001f - depth
                                    // Near the table, only the empty space in front of the point counts: the
                                    // table must not become a surface, but the object's lower sides still show.
                                    if (sdf >= -trunc && (table == null || !table[pixel] || sdf > 0f)) {
                                        val w0 = weight[index]
                                        tsdf[index] = (tsdf[index] * w0 + min(1f, sdf / trunc) * w) / (w0 + w)
                                        weight[index] = min(w0 + w, MAX_WEIGHT)
                                    }
                                }
                            }
                        }
                    }
                    cx += g[3]
                    cy += g[4]
                    cz += g[5]
                    index++
                }
            }
        }
    }

    /**
     * Field values for surface extraction on an (n+2)³ grid of voxel centres, padded with empty space.
     *
     * Unobserved voxels are decided like this: anything under a seen surface (in the same vertical column)
     * is solid, because the camera never sees under an object standing on a table; anything else that
     * connects to open space through the sides or top of the box is empty; enclosed pockets are solid.
     */
    fun extractionField(): FloatArray {
        val count = n * n * n
        val solidBelowSurface = BooleanArray(count)
        for (l in 0 until n) {
            for (i in 0 until n) {
                var surfaceAbove = false
                for (j in n - 1 downTo 0) {
                    val index = index(i, j, l)
                    val w = weight[index]
                    if (w >= SURFACE_WEIGHT && tsdf[index] < 0f) surfaceAbove = true
                    if (w == 0f && surfaceAbove) solidBelowSurface[index] = true
                }
            }
        }
        val outside = BooleanArray(count)
        val queue = IntArray(count)
        var head = 0
        var tail = 0
        fun passable(index: Int) = if (weight[index] > 0f) tsdf[index] > 0f else !solidBelowSurface[index]
        fun visit(index: Int) {
            if (!outside[index] && passable(index)) {
                outside[index] = true
                queue[tail++] = index
            }
        }
        for (a in 0 until n) {
            for (b in 0 until n) {
                visit(index(0, a, b))
                visit(index(n - 1, a, b))
                visit(index(a, b, 0))
                visit(index(a, b, n - 1))
                visit(index(a, n - 1, b))
            }
        }
        while (head < tail) {
            val current = queue[head++]
            val i = current % n
            val j = (current / n) % n
            val l = current / (n * n)
            if (i > 0) visit(current - 1)
            if (i < n - 1) visit(current + 1)
            if (j > 0) visit(current - n)
            if (j < n - 1) visit(current + n)
            if (l > 0) visit(current - n * n)
            if (l < n - 1) visit(current + n * n)
        }
        val p = n + 2
        val field = FloatArray(p * p * p) { 1f }
        for (l in 0 until n) {
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val index = index(i, j, l)
                    val value = when {
                        weight[index] > 0f -> tsdf[index]
                        outside[index] -> 1f
                        else -> -1f
                    }
                    field[((l + 1) * p + (j + 1)) * p + (i + 1)] = value
                }
            }
        }
        return field
    }

    private fun index(i: Int, j: Int, l: Int) = (l * n + j) * n + i

    companion object {
        private const val TRUNCATION_VOXELS = 3f
        private const val MIN_TRUNCATION = 0.012f
        private const val MIN_DEPTH = 0.08f
        private const val MAX_DEPTH_MM = 4_000
        private const val MAX_WEIGHT = 64f
        private const val EDGE_JUMP = 0.01f

        /** Weight a voxel needs (about one head-on view) before it counts as a seen surface. */
        private const val SURFACE_WEIGHT = 1f
        private const val EDGE_JUMP_RATIO = 0.03f

        /** About 3.5 mm voxels for small objects, capped at 128³ (16 MB) for large ones. */
        fun resolutionFor(size: Float): Int = (size / 0.0035f).roundToInt().coerceIn(48, 128)
    }
}
