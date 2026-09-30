package com.ma7moud.reality3d.mesh

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Convex hulls for game collision. Game engines want a few dozen hull vertices at most, so the hull is
 * built from the model's extreme points in evenly spread directions: every one of them lies on the true
 * hull, and together they approximate it closely.
 */
object ConvexHull {

    class Hull(val positions: FloatArray, val indices: IntArray) {
        val vertexCount: Int get() = positions.size / 3
        val triangleCount: Int get() = indices.size / 3
    }

    /** The hull of [points] (x, y, z each) with at most about [maxVertices] vertices, wound outwards. */
    fun of(points: FloatArray, maxVertices: Int = 64): Hull {
        val count = points.size / 3
        require(count >= 4) { "a hull needs at least four points" }
        val candidates = extremePoints(points, max(8, maxVertices))
        return build(points, candidates) ?: build(points, (0 until count).toList().toIntArray())
            ?: error("the points are flat, so they have no volume to enclose")
    }

    /** The point furthest along each of [directions] evenly spread directions (a Fibonacci sphere). */
    private fun extremePoints(points: FloatArray, directions: Int): IntArray {
        val chosen = LinkedHashSet<Int>()
        val golden = PI * (3 - sqrt(5.0))
        for (k in 0 until directions) {
            val y = 1 - 2 * (k + 0.5) / directions
            val r = sqrt(1 - y * y)
            val dx = (cos(golden * k) * r).toFloat()
            val dy = y.toFloat()
            val dz = (sin(golden * k) * r).toFloat()
            var best = 0
            var bestDot = Float.NEGATIVE_INFINITY
            for (i in 0 until points.size / 3) {
                val dot = points[i * 3] * dx + points[i * 3 + 1] * dy + points[i * 3 + 2] * dz
                if (dot > bestDot) {
                    bestDot = dot
                    best = i
                }
            }
            chosen += best
        }
        return chosen.toIntArray()
    }

    private class Face(val a: Int, val b: Int, val c: Int, val nx: Double, val ny: Double, val nz: Double, val d: Double) {
        fun distance(p: FloatArray, i: Int) = nx * p[i * 3] + ny * p[i * 3 + 1] + nz * p[i * 3 + 2] + d
    }

    /** Incremental hull of the [use]d points; null if they are (nearly) coplanar. */
    private fun build(p: FloatArray, use: IntArray): Hull? {
        if (use.size < 4) return null
        var extent = 0.0
        for (i in use) for (k in 0 until 3) extent = max(extent, abs(p[i * 3 + k].toDouble()))
        val eps = max(extent, 1e-9) * 1e-7

        // A starting tetrahedron from well-separated points.
        val a = use.minBy { p[it * 3] }
        val b = use.maxBy { distanceSq(p, a, it) }
        if (distanceSq(p, a, b) <= eps * eps) return null
        val c = use.maxBy { lineDistanceSq(p, a, b, it) }
        if (lineDistanceSq(p, a, b, c) <= eps * eps) return null
        val plane = face(p, a, b, c) ?: return null
        val d = use.maxBy { abs(plane.distance(p, it)) }
        if (abs(plane.distance(p, d)) <= eps) return null

        val faces = ArrayList<Face>()
        fun add(x: Int, y: Int, z: Int) {
            // Wind each face so the tetrahedron's fourth point is behind it.
            val f = face(p, x, y, z) ?: return
            faces += f
        }
        val centroid = FloatArray(3) { (p[a * 3 + it] + p[b * 3 + it] + p[c * 3 + it] + p[d * 3 + it]) / 4 }
        for ((x, y, z) in listOf(Triple(a, b, c), Triple(a, b, d), Triple(a, c, d), Triple(b, c, d))) {
            val f = face(p, x, y, z) ?: return null
            val inside = f.nx * centroid[0] + f.ny * centroid[1] + f.nz * centroid[2] + f.d
            if (inside > 0) add(x, z, y) else add(x, y, z)
        }
        for (i in use) {
            if (i == a || i == b || i == c || i == d) continue
            val visible = faces.filter { it.distance(p, i) > eps }
            if (visible.isEmpty()) continue
            // The horizon: edges of visible faces whose twin belongs to a face that stays.
            val edges = HashMap<Long, Pair<Int, Int>>()
            for (f in visible) {
                for ((u, v) in listOf(f.a to f.b, f.b to f.c, f.c to f.a)) {
                    val twin = key(v, u)
                    if (edges.remove(twin) == null) edges[key(u, v)] = u to v
                }
            }
            faces.removeAll(visible.toSet())
            for ((u, v) in edges.values) add(u, v, i)
        }
        // Compact to the vertices actually used.
        val remap = HashMap<Int, Int>()
        val positions = ArrayList<Float>()
        val indices = IntArray(faces.size * 3)
        var n = 0
        for (f in faces) {
            for (v in intArrayOf(f.a, f.b, f.c)) {
                indices[n++] = remap.getOrPut(v) {
                    positions += p[v * 3]
                    positions += p[v * 3 + 1]
                    positions += p[v * 3 + 2]
                    remap.size
                }
            }
        }
        return Hull(positions.toFloatArray(), indices)
    }

    private fun key(u: Int, v: Int): Long = (u.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)

    private fun face(p: FloatArray, a: Int, b: Int, c: Int): Face? {
        val abx = (p[b * 3] - p[a * 3]).toDouble()
        val aby = (p[b * 3 + 1] - p[a * 3 + 1]).toDouble()
        val abz = (p[b * 3 + 2] - p[a * 3 + 2]).toDouble()
        val acx = (p[c * 3] - p[a * 3]).toDouble()
        val acy = (p[c * 3 + 1] - p[a * 3 + 1]).toDouble()
        val acz = (p[c * 3 + 2] - p[a * 3 + 2]).toDouble()
        var nx = aby * acz - abz * acy
        var ny = abz * acx - abx * acz
        var nz = abx * acy - aby * acx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length <= 1e-30) return null
        nx /= length
        ny /= length
        nz /= length
        return Face(a, b, c, nx, ny, nz, -(nx * p[a * 3] + ny * p[a * 3 + 1] + nz * p[a * 3 + 2]))
    }

    private fun distanceSq(p: FloatArray, a: Int, b: Int): Double {
        var sum = 0.0
        for (k in 0 until 3) {
            val d = (p[b * 3 + k] - p[a * 3 + k]).toDouble()
            sum += d * d
        }
        return sum
    }

    private fun lineDistanceSq(p: FloatArray, a: Int, b: Int, c: Int): Double {
        val abx = (p[b * 3] - p[a * 3]).toDouble()
        val aby = (p[b * 3 + 1] - p[a * 3 + 1]).toDouble()
        val abz = (p[b * 3 + 2] - p[a * 3 + 2]).toDouble()
        val acx = (p[c * 3] - p[a * 3]).toDouble()
        val acy = (p[c * 3 + 1] - p[a * 3 + 1]).toDouble()
        val acz = (p[c * 3 + 2] - p[a * 3 + 2]).toDouble()
        val cx = aby * acz - abz * acy
        val cy = abz * acx - abx * acz
        val cz = abx * acy - aby * acx
        val ab2 = abx * abx + aby * aby + abz * abz
        return if (ab2 <= 0) 0.0 else (cx * cx + cy * cy + cz * cz) / ab2
    }
}
