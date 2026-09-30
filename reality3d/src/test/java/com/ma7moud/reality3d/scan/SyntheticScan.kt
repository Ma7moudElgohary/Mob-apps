package com.ma7moud.reality3d.scan

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A made-up scan to test the reconstruction without a phone: a ball and a block standing on a table,
 * photographed by a camera walking around them in rings, as ARCore would report it.
 */
internal object SyntheticScan {

    // The objects: a ball resting on the table and a block next to it, in meters.
    const val BALL_X = 0f
    const val BALL_Z = 0f
    const val BALL_RADIUS = 0.07f
    const val BALL_Y = BALL_RADIUS
    val BLOCK_MIN = floatArrayOf(0.07f, 0f, -0.03f)
    val BLOCK_MAX = floatArrayOf(0.13f, 0.1f, 0.03f)

    const val RED = 0xFFD03020.toInt()
    const val BLUE = 0xFF2040D0.toInt()
    const val TABLE = 0xFFE8E4DC.toInt()
    const val WALL = 0xFF707070.toInt()

    val depthIntrinsics = Intrinsics(140f, 140f, 80f, 60f, 160, 120)
    val colorIntrinsics = depthIntrinsics.scaledTo(320, 240)

    val box = ScanBox(centerX = 0.03f, bottomY = ScanBox.FLOOR_MARGIN, centerZ = 0f, size = 0.3f, floorY = 0f)

    /** Signed distance to the ball and block (negative inside). */
    fun distance(x: Float, y: Float, z: Float): Float {
        val ball = sqrt((x - BALL_X) * (x - BALL_X) + (y - BALL_Y) * (y - BALL_Y) + (z - BALL_Z) * (z - BALL_Z)) - BALL_RADIUS
        val qx = abs(x - (BLOCK_MIN[0] + BLOCK_MAX[0]) / 2) - (BLOCK_MAX[0] - BLOCK_MIN[0]) / 2
        val qy = abs(y - (BLOCK_MIN[1] + BLOCK_MAX[1]) / 2) - (BLOCK_MAX[1] - BLOCK_MIN[1]) / 2
        val qz = abs(z - (BLOCK_MIN[2] + BLOCK_MAX[2]) / 2) - (BLOCK_MAX[2] - BLOCK_MIN[2]) / 2
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f) + max(qz, 0f) * max(qz, 0f))
        val block = outside + min(max(qx, max(qy, qz)), 0f)
        return min(ball, block)
    }

    /** ARCore-style camera-to-world pose for a camera at (ex, ey, ez) looking at (tx, ty, tz). */
    fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float): CameraPose {
        var fx = tx - ex
        var fy = ty - ey
        var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl
        fy /= fl
        fz /= fl
        // Looking straight down needs another reference for "up".
        val (ux, uy, uz) = if (abs(fy) > 0.99f) Triple(0f, 0f, -1f) else Triple(0f, 1f, 0f)
        var rx = fy * uz - fz * uy
        var ry = fz * ux - fx * uz
        var rz = fx * uy - fy * ux
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rl
        ry /= rl
        rz /= rl
        val vx = ry * fz - rz * fy
        val vy = rz * fx - rx * fz
        val vz = rx * fy - ry * fx
        return CameraPose(floatArrayOf(rx, ry, rz, 0f, vx, vy, vz, 0f, -fx, -fy, -fz, 0f, ex, ey, ez, 1f))
    }

    /** Camera poses on rings around the objects, looking at their middle. */
    fun ringPoses(elevationsDeg: List<Float> = listOf(15f, 40f, 65f, 88f), perRing: Int = 24, distance: Float = 0.45f): List<CameraPose> {
        val target = floatArrayOf(0.03f, 0.06f, 0f)
        val poses = ArrayList<CameraPose>()
        for (elevation in elevationsDeg) {
            val count = if (elevation > 80f) 1 else perRing
            for (k in 0 until count) {
                val azimuth = 2 * Math.PI * k / count
                val e = Math.toRadians(elevation.toDouble())
                val ex = target[0] + (distance * cos(e) * sin(azimuth)).toFloat()
                val ey = target[1] + (distance * sin(e)).toFloat()
                val ez = target[2] + (distance * cos(e) * cos(azimuth)).toFloat()
                poses += lookAt(ex, ey, ez, target[0], target[1], target[2])
            }
        }
        return poses
    }

    /** What a ray from (ox, oy, oz) along (dx, dy, dz) hits: parameter t and colour. */
    private fun trace(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Pair<Float, Int> {
        var bestT = Float.MAX_VALUE
        var color = WALL
        // Ball.
        val lx = ox - BALL_X
        val ly = oy - BALL_Y
        val lz = oz - BALL_Z
        val a = dx * dx + dy * dy + dz * dz
        val b = 2 * (lx * dx + ly * dy + lz * dz)
        val c = lx * lx + ly * ly + lz * lz - BALL_RADIUS * BALL_RADIUS
        val disc = b * b - 4 * a * c
        if (disc >= 0f) {
            val t = (-b - sqrt(disc)) / (2 * a)
            if (t > 0f && t < bestT) {
                bestT = t
                color = RED
            }
        }
        // Block (slab test).
        var tNear = -Float.MAX_VALUE
        var tFar = Float.MAX_VALUE
        val origin = floatArrayOf(ox, oy, oz)
        val direction = floatArrayOf(dx, dy, dz)
        var hitBlock = true
        for (axis in 0 until 3) {
            if (abs(direction[axis]) < 1e-9f) {
                if (origin[axis] < BLOCK_MIN[axis] || origin[axis] > BLOCK_MAX[axis]) hitBlock = false
            } else {
                var t1 = (BLOCK_MIN[axis] - origin[axis]) / direction[axis]
                var t2 = (BLOCK_MAX[axis] - origin[axis]) / direction[axis]
                if (t1 > t2) t1 = t2.also { t2 = t1 }
                tNear = max(tNear, t1)
                tFar = min(tFar, t2)
            }
        }
        if (hitBlock && tNear <= tFar && tNear > 0f && tNear < bestT) {
            bestT = tNear
            color = BLUE
        }
        // Table (y = 0) and a far wall.
        if (dy < 0f) {
            val t = -oy / dy
            if (t > 0f && t < bestT) {
                bestT = t
                color = TABLE
            }
        }
        if (bestT == Float.MAX_VALUE) bestT = 2.5f
        return bestT to color
    }

    /** The depth map ARCore would give from [pose], with optional noise (σ in meters at 1 m, growing with distance). */
    fun depthFrame(pose: CameraPose, noise: Float = 0f, random: Random = Random(1)): DepthFrame {
        val k = depthIntrinsics
        val depth = ShortArray(k.width * k.height)
        val m = pose.matrix
        for (j in 0 until k.height) {
            for (i in 0 until k.width) {
                // Camera-space ray with z = -1, so the ray parameter is the depth along the optical axis.
                val cx = (i - k.cx) / k.fx
                val cy = -(j - k.cy) / k.fy
                val dx = m[0] * cx + m[4] * cy - m[8]
                val dy = m[1] * cx + m[5] * cy - m[9]
                val dz = m[2] * cx + m[6] * cy - m[10]
                var t = trace(m[12], m[13], m[14], dx, dy, dz).first
                if (noise > 0f) t += (random.nextGaussian() * noise * t).toFloat()
                depth[j * k.width + i] = (t * 1000f).toInt().coerceIn(1, 65535).toShort()
            }
        }
        return DepthFrame(k.width, k.height, depth, k, pose)
    }

    /** The photo from [pose]: flat colours per object. */
    fun photo(pose: CameraPose): KeyframeImage {
        val k = colorIntrinsics
        val argb = IntArray(k.width * k.height)
        val m = pose.matrix
        for (j in 0 until k.height) {
            for (i in 0 until k.width) {
                val cx = (i - k.cx) / k.fx
                val cy = -(j - k.cy) / k.fy
                argb[j * k.width + i] = trace(m[12], m[13], m[14], m[0] * cx + m[4] * cy - m[8], m[1] * cx + m[5] * cy - m[9], m[2] * cx + m[6] * cy - m[10]).second
            }
        }
        return KeyframeImage(k.width, k.height, argb, k, pose)
    }

    /** Fuses depth maps from [poses] into a volume over [box]. */
    fun fuse(poses: List<CameraPose>, noise: Float = 0f, box: ScanBox = SyntheticScan.box, resolution: Int = 64): TsdfVolume {
        val volume = TsdfVolume(box, resolution)
        val random = Random(42)
        for (pose in poses) volume.integrate(depthFrame(pose, noise, random))
        return volume
    }

    /** Directed edges without a matching reverse edge (0 for a closed, consistently wound mesh). */
    fun openEdges(indices: IntArray): Int {
        val directed = HashMap<Long, Int>()
        for (t in indices.indices step 3) {
            for (e in 0 until 3) {
                val a = indices[t + e].toLong()
                val b = indices[t + (e + 1) % 3].toLong()
                directed.merge((a shl 32) or b, 1, Int::plus)
            }
        }
        var open = 0
        for ((key, count) in directed) {
            val a = key ushr 32
            val b = key and 0xFFFFFFFFL
            if (count != 1 || directed[(b shl 32) or a] != 1) open++
        }
        return open
    }

    /** Signed volume by the divergence theorem: positive when triangles face outwards. */
    fun signedVolume(positions: FloatArray, indices: IntArray): Float {
        var volume = 0.0
        for (t in indices.indices step 3) {
            val a = indices[t] * 3
            val b = indices[t + 1] * 3
            val c = indices[t + 2] * 3
            val cx = positions[b + 1] * positions[c + 2] - positions[b + 2] * positions[c + 1]
            val cy = positions[b + 2] * positions[c] - positions[b] * positions[c + 2]
            val cz = positions[b] * positions[c + 1] - positions[b + 1] * positions[c]
            volume += (positions[a] * cx + positions[a + 1] * cy + positions[a + 2] * cz).toDouble()
        }
        return (volume / 6.0).toFloat()
    }
}
