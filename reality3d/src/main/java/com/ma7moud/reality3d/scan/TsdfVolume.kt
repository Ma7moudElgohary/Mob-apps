package com.ma7moud.reality3d.scan

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    /**
     * Fuses one usable depth map. With a [pool], z-slabs of the volume are processed in parallel.
     *
     * The volume is the final reconstruction boundary, so it defensively rejects frames that cannot
     * describe the selected object: the box centre must be well inside the depth camera view, the camera
     * must be at a sensible distance for the selected box size, and the map must contain usable depth.
     * This prevents a caller bug or a transient ARCore frame from contaminating every later surface.
     *
     * @return true when the frame was accepted and fused; false when it was rejected before fusion.
     */
    fun integrate(frame: DepthFrame, pool: ExecutorService? = null, threads: Int = 4): Boolean {
        if (!FusionFrameGate.accept(frame, box)) return false

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
        return true
    }

    /**
     * How much to trust each depth pixel. Pixels on a depth edge (an object's outline) are skipped: their
     * depth belongs half to the object and half to what is behind it, and would eat into the outline.
     * Surfaces seen at a grazing angle count less (cos² of the angle, from the depth gradient). Raw depth
     * also counts by ARCore's confidence, and its gaps are just missing samples, not edges.
     */
    internal fun pixelWeights(frame: DepthFrame): FloatArray {
        val width = frame.width
        val height = frame.height
        val depth = frame.depthMm
        val confidence = frame.confidence
        val focal = (frame.intrinsics.fx + frame.intrinsics.fy) / 2
        val weights = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val d = (depth[index].toInt() and 0xFFFF) * 0.001f
                if (d <= 0f) continue
                val trust = if (confidence == null) 1f else (confidence[index].toInt() and 0xFF) / 255f
                if (trust < MIN_CONFIDENCE) continue
                val jump = max(EDGE_JUMP, EDGE_JUMP_RATIO * d)
                var edge = false
                fun neighbour(nx: Int, ny: Int): Float {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) return d
                    val value = (depth[ny * width + nx].toInt() and 0xFFFF) * 0.001f
                    if (value <= 0f && confidence != null) return d
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
                weights[index] = trust / (1f + slope)
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

        /** Raw depth pixels below this confidence (0..1) are ignored. */
        private const val MIN_CONFIDENCE = 0.15f

        /** Weight a voxel needs (about one head-on view) before it counts as a seen surface. */
        private const val SURFACE_WEIGHT = 1f
        private const val EDGE_JUMP_RATIO = 0.03f

        /** About 3.5 mm voxels for small objects, capped at 128³ (16 MB) for large ones. */
        fun resolutionFor(size: Float): Int = (size / 0.0035f).roundToInt().coerceIn(48, 128)
    }
}

/**
 * Fast, deterministic checks that protect the TSDF from frames that cannot describe the selected object.
 * These deliberately stay conservative: borderline frames are allowed and the per-pixel confidence/edge
 * weighting in [TsdfVolume] decides how much they contribute.
 */
internal object FusionFrameGate {
    private const val IMAGE_MARGIN = 0.05f
    private const val MIN_VALID_SAMPLES = 4
    private const val MIN_VALID_FRACTION = 0.02f
    private const val SAMPLE_GRID = 20
    private const val MIN_RAW_CONFIDENCE = 38 // ~15%, matching TsdfVolume's per-pixel confidence floor.

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun accept(frame: DepthFrame, box: ScanBox): Boolean {
        if (!sensibleDistance(frame.pose, box)) return false
        if (!aimedAtBox(frame, box)) return false
        val region = projectedBoxRegion(frame, box) ?: return false
        return hasUsableDepth(frame, region)
    }

    private fun sensibleDistance(pose: CameraPose, box: ScanBox): Boolean {
        val dx = pose.x - box.centerX
        val dy = pose.y - box.centerY
        val dz = pose.z - box.centerZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val near = max(0.2f, box.size * 0.9f)
        val far = max(0.8f, box.size * 3.5f)
        return distance in near..far
    }

    private fun aimedAtBox(frame: DepthFrame, box: ScanBox): Boolean {
        val projected = FloatArray(3)
        if (!frame.pose.project(box.centerX, box.centerY, box.centerZ, frame.intrinsics, projected)) return false
        val marginX = frame.width * IMAGE_MARGIN
        val marginY = frame.height * IMAGE_MARGIN
        return projected[0] in marginX..(frame.width - marginX) &&
            projected[1] in marginY..(frame.height - marginY)
    }

    /** Bounding rectangle of the selected 3D box in this depth image, clipped to the image. */
    private fun projectedBoxRegion(frame: DepthFrame, box: ScanBox): Region? {
        val projected = FloatArray(3)
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (corner in 0 until 8) {
            val x = if (corner and 1 == 0) box.minX else box.minX + box.size
            val y = if (corner and 2 == 0) box.bottomY else box.bottomY + box.size
            val z = if (corner and 4 == 0) box.minZ else box.minZ + box.size
            if (!frame.pose.project(x, y, z, frame.intrinsics, projected)) return null
            left = min(left, projected[0])
            top = min(top, projected[1])
            right = max(right, projected[0])
            bottom = max(bottom, projected[1])
        }
        val x0 = floor(left.toDouble()).toInt().coerceIn(0, frame.width)
        val y0 = floor(top.toDouble()).toInt().coerceIn(0, frame.height)
        val x1 = ceil(right.toDouble()).toInt().coerceIn(0, frame.width)
        val y1 = ceil(bottom.toDouble()).toInt().coerceIn(0, frame.height)
        return if (x1 > x0 && y1 > y0) Region(x0, y0, x1, y1) else null
    }

    /**
     * Raw depth may be sparse, so only a small fraction is required. The important part is that those
     * samples are inside the selected object's projected box instead of anywhere in the background.
     */
    private fun hasUsableDepth(frame: DepthFrame, region: Region): Boolean {
        val stepX = max(1, region.width / SAMPLE_GRID)
        val stepY = max(1, region.height / SAMPLE_GRID)
        var good = 0
        var sampled = 0
        var y = region.top + stepY / 2
        while (y < region.bottom) {
            var x = region.left + stepX / 2
            while (x < region.right) {
                sampled++
                val index = y * frame.width + x
                val depth = frame.depthMm[index].toInt() and 0xFFFF
                if (depth in 1..4_000) {
                    val confidence = frame.confidence
                    if (confidence == null || (confidence[index].toInt() and 0xFF) >= MIN_RAW_CONFIDENCE) good++
                }
                x += stepX
            }
            y += stepY
        }
        if (sampled == 0 || good < MIN_VALID_SAMPLES) return false
        return good.toFloat() / sampled >= MIN_VALID_FRACTION
    }
}
