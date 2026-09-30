package com.ma7moud.reality3d.mesh

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Quadric-error mesh simplification (Garland & Heckbert): repeatedly collapses the edge whose removal
 * changes the surface least, until the mesh is down to the requested number of triangles.
 *
 * Collapses that would tear or fold the surface are skipped, so a closed mesh stays closed and
 * manifold; the outline of an open mesh is held in place. Per-vertex attributes (texture coordinates,
 * colours) are interpolated along each collapsed edge.
 */
object MeshSimplifier {

    class Result(val positions: FloatArray, val indices: IntArray, val attributes: FloatArray?, val attributeSize: Int) {
        val triangleCount: Int get() = indices.size / 3
    }

    /** Simplifies [mesh] to about [targetTriangles], keeping its texture coordinates and colours. */
    fun simplify(mesh: Mesh3D, targetTriangles: Int): Mesh3D {
        if (mesh.triangleCount <= targetTriangles) return mesh
        val uvs = mesh.uvs
        val colors = mesh.colors
        val size = (if (uvs != null) 2 else 0) + (if (colors != null) 3 else 0)
        val attributes = if (size == 0) null else FloatArray(mesh.vertexCount * size).also { out ->
            for (v in 0 until mesh.vertexCount) {
                var k = v * size
                if (uvs != null) {
                    out[k++] = uvs[v * 2]
                    out[k++] = uvs[v * 2 + 1]
                }
                if (colors != null) {
                    out[k++] = colors[v * 3]
                    out[k++] = colors[v * 3 + 1]
                    out[k] = colors[v * 3 + 2]
                }
            }
        }
        val result = simplify(mesh.positions, mesh.indices, targetTriangles, attributes, size)
        val count = result.positions.size / 3
        val outUvs = if (uvs != null) FloatArray(count * 2) else null
        val outColors = if (colors != null) FloatArray(count * 3) else null
        val a = result.attributes
        if (a != null) {
            for (v in 0 until count) {
                var k = v * size
                if (outUvs != null) {
                    outUvs[v * 2] = a[k++]
                    outUvs[v * 2 + 1] = a[k++]
                }
                if (outColors != null) {
                    outColors[v * 3] = a[k++]
                    outColors[v * 3 + 1] = a[k++]
                    outColors[v * 3 + 2] = a[k]
                }
            }
        }
        return Mesh3D(
            positions = result.positions,
            normals = MeshBuilder.vertexNormals(result.positions, result.indices),
            uvs = outUvs,
            indices = result.indices,
            solid = mesh.solid,
            subjectIsolated = mesh.subjectIsolated,
            colors = outColors,
            realScale = mesh.realScale,
        )
    }

    fun simplify(positions: FloatArray, indices: IntArray, targetTriangles: Int, attributes: FloatArray? = null, attributeSize: Int = 0): Result {
        require(attributes == null || attributes.size == positions.size / 3 * attributeSize)
        if (indices.size / 3 <= targetTriangles) return Result(positions, indices, attributes, attributeSize)
        return Collapser(positions, indices, attributes, attributeSize).run(max(targetTriangles, 1))
    }

    private const val BOUNDARY_WEIGHT = 100.0
    private const val MIN_NORMAL_COSINE = 0.2
    private const val MAX_PASSES = 4

    private class Collapser(sourcePositions: FloatArray, sourceIndices: IntArray, sourceAttributes: FloatArray?, val attributeSize: Int) {
        val vertexCount = sourcePositions.size / 3
        val triangleCount = sourceIndices.size / 3

        // Work in a unit-sized frame so quadrics are well conditioned whatever the model's units.
        val offset = DoubleArray(3)
        val unit: Double
        val p: DoubleArray
        val attr: FloatArray? = sourceAttributes?.copyOf()
        val tri: IntArray = sourceIndices.copyOf()
        val triDead = BooleanArray(triangleCount)
        val vertexDead = BooleanArray(vertexCount)
        val version = IntArray(vertexCount)
        val boundary = BooleanArray(vertexCount)
        val q = DoubleArray(vertexCount * 10)
        var vertexTriangles = arrayOfNulls<IntArray>(vertexCount)
        val vertexTriangleCount = IntArray(vertexCount)
        val heap = EdgeHeap()
        var live = 0

        // Scratch for neighbour sets.
        val mark = IntArray(vertexCount)
        var markStamp = 0

        init {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var minZ = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            var maxZ = -Double.MAX_VALUE
            for (v in 0 until vertexCount) {
                val x = sourcePositions[v * 3].toDouble()
                val y = sourcePositions[v * 3 + 1].toDouble()
                val z = sourcePositions[v * 3 + 2].toDouble()
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                minZ = min(minZ, z); maxZ = max(maxZ, z)
            }
            offset[0] = (minX + maxX) / 2
            offset[1] = (minY + maxY) / 2
            offset[2] = (minZ + maxZ) / 2
            unit = max(max(maxX - minX, maxY - minY), max(maxZ - minZ, 1e-12))
            p = DoubleArray(vertexCount * 3) { (sourcePositions[it] - offset[it % 3]) / unit }
        }

        fun run(target: Int): Result {
            buildAdjacency()
            buildQuadrics()
            // A collapse refused now (it would pinch or fold the surface) may be fine once its surroundings
            // have changed, so the remaining edges get a few more passes.
            for (pass in 0 until MAX_PASSES) {
                if (live <= target) break
                val before = live
                pushAllEdges()
                while (live > target && !heap.isEmpty()) {
                    heap.pop()
                    val a = heap.poppedA
                    val b = heap.poppedB
                    if (vertexDead[a] || vertexDead[b] || version[a] != heap.poppedVersionA || version[b] != heap.poppedVersionB) continue
                    collapse(a, b)
                }
                heap.clear()
                if (live == before) break
            }
            return compact()
        }

        private fun buildAdjacency() {
            val degree = IntArray(vertexCount)
            for (t in 0 until triangleCount) {
                val a = tri[t * 3]
                val b = tri[t * 3 + 1]
                val c = tri[t * 3 + 2]
                if (a == b || b == c || a == c) {
                    triDead[t] = true
                    continue
                }
                degree[a]++
                degree[b]++
                degree[c]++
                live++
            }
            for (v in 0 until vertexCount) vertexTriangles[v] = IntArray(degree[v] + 2)
            for (t in 0 until triangleCount) {
                if (triDead[t]) continue
                for (k in 0 until 3) addTriangle(tri[t * 3 + k], t)
            }
            // Boundary edges belong to exactly one triangle.
            val keys = LongArray(live * 3)
            var n = 0
            for (t in 0 until triangleCount) {
                if (triDead[t]) continue
                for (k in 0 until 3) keys[n++] = edgeKey(tri[t * 3 + k], tri[t * 3 + (k + 1) % 3])
            }
            keys.sort()
            var i = 0
            while (i < n) {
                var j = i + 1
                while (j < n && keys[j] == keys[i]) j++
                if (j - i == 1) {
                    boundary[(keys[i] ushr 32).toInt()] = true
                    boundary[(keys[i] and 0xFFFFFFFFL).toInt()] = true
                }
                i = j
            }
        }

        private fun addTriangle(v: Int, t: Int) {
            var list = vertexTriangles[v]!!
            if (vertexTriangleCount[v] == list.size) {
                list = list.copyOf(list.size * 2)
                vertexTriangles[v] = list
            }
            list[vertexTriangleCount[v]++] = t
        }

        private fun buildQuadrics() {
            val normal = DoubleArray(3)
            for (t in 0 until triangleCount) {
                if (triDead[t]) continue
                val a = tri[t * 3]
                val b = tri[t * 3 + 1]
                val c = tri[t * 3 + 2]
                val area2 = faceNormal(a, b, c, normal)
                if (area2 <= 0.0) continue
                val d = -(normal[0] * p[a * 3] + normal[1] * p[a * 3 + 1] + normal[2] * p[a * 3 + 2])
                val weight = area2 / 2
                addPlane(a, normal[0], normal[1], normal[2], d, weight)
                addPlane(b, normal[0], normal[1], normal[2], d, weight)
                addPlane(c, normal[0], normal[1], normal[2], d, weight)
                // Planes through boundary edges, square to the face, keep the outline in place.
                for (k in 0 until 3) {
                    val u = tri[t * 3 + k]
                    val v = tri[t * 3 + (k + 1) % 3]
                    if (!boundary[u] || !boundary[v] || sharedTriangles(u, v) != 1) continue
                    val ex = p[v * 3] - p[u * 3]
                    val ey = p[v * 3 + 1] - p[u * 3 + 1]
                    val ez = p[v * 3 + 2] - p[u * 3 + 2]
                    var mx = ey * normal[2] - ez * normal[1]
                    var my = ez * normal[0] - ex * normal[2]
                    var mz = ex * normal[1] - ey * normal[0]
                    val length = sqrt(mx * mx + my * my + mz * mz)
                    if (length <= 1e-18) continue
                    mx /= length
                    my /= length
                    mz /= length
                    val md = -(mx * p[u * 3] + my * p[u * 3 + 1] + mz * p[u * 3 + 2])
                    val w = BOUNDARY_WEIGHT * (ex * ex + ey * ey + ez * ez)
                    addPlane(u, mx, my, mz, md, w)
                    addPlane(v, mx, my, mz, md, w)
                }
            }
        }

        /** Unit normal of triangle abc into [out]; returns twice its area (0 if degenerate). */
        private fun faceNormal(a: Int, b: Int, c: Int, out: DoubleArray): Double {
            val abx = p[b * 3] - p[a * 3]
            val aby = p[b * 3 + 1] - p[a * 3 + 1]
            val abz = p[b * 3 + 2] - p[a * 3 + 2]
            val acx = p[c * 3] - p[a * 3]
            val acy = p[c * 3 + 1] - p[a * 3 + 1]
            val acz = p[c * 3 + 2] - p[a * 3 + 2]
            val nx = aby * acz - abz * acy
            val ny = abz * acx - abx * acz
            val nz = abx * acy - aby * acx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length <= 1e-24) return 0.0
            out[0] = nx / length
            out[1] = ny / length
            out[2] = nz / length
            return length
        }

        private fun addPlane(v: Int, a: Double, b: Double, c: Double, d: Double, w: Double) {
            val o = v * 10
            q[o] += w * a * a
            q[o + 1] += w * a * b
            q[o + 2] += w * a * c
            q[o + 3] += w * a * d
            q[o + 4] += w * b * b
            q[o + 5] += w * b * c
            q[o + 6] += w * b * d
            q[o + 7] += w * c * c
            q[o + 8] += w * c * d
            q[o + 9] += w * d * d
        }

        private fun sharedTriangles(u: Int, v: Int): Int {
            var count = 0
            val list = vertexTriangles[u]!!
            for (i in 0 until vertexTriangleCount[u]) {
                val t = list[i]
                if (triDead[t]) continue
                if (tri[t * 3] == v || tri[t * 3 + 1] == v || tri[t * 3 + 2] == v) count++
            }
            return count
        }

        private fun pushAllEdges() {
            for (t in 0 until triangleCount) {
                if (triDead[t]) continue
                for (k in 0 until 3) {
                    val u = tri[t * 3 + k]
                    val v = tri[t * 3 + (k + 1) % 3]
                    // Each interior edge appears twice (once each way); boundary edges once.
                    if (u < v || sharedTriangles(u, v) == 1) push(u, v)
                }
            }
        }

        // The collapse target of the edge being evaluated.
        private val target = DoubleArray(3)
        private var targetT = 0.0

        /** Finds where edge (a, b) should collapse to; returns the quadric error there. */
        private fun evaluate(a: Int, b: Int): Double {
            val oa = a * 10
            val ob = b * 10
            val q11 = q[oa] + q[ob]
            val q12 = q[oa + 1] + q[ob + 1]
            val q13 = q[oa + 2] + q[ob + 2]
            val q14 = q[oa + 3] + q[ob + 3]
            val q22 = q[oa + 4] + q[ob + 4]
            val q23 = q[oa + 5] + q[ob + 5]
            val q24 = q[oa + 6] + q[ob + 6]
            val q33 = q[oa + 7] + q[ob + 7]
            val q34 = q[oa + 8] + q[ob + 8]
            val q44 = q[oa + 9] + q[ob + 9]
            fun error(x: Double, y: Double, z: Double): Double =
                q11 * x * x + 2 * q12 * x * y + 2 * q13 * x * z + 2 * q14 * x +
                    q22 * y * y + 2 * q23 * y * z + 2 * q24 * y +
                    q33 * z * z + 2 * q34 * z + q44

            val ax = p[a * 3]
            val ay = p[a * 3 + 1]
            val az = p[a * 3 + 2]
            val bx = p[b * 3]
            val by = p[b * 3 + 1]
            val bz = p[b * 3 + 2]
            val ex = bx - ax
            val ey = by - ay
            val ez = bz - az
            val edgeLengthSq = ex * ex + ey * ey + ez * ez

            fun take(x: Double, y: Double, z: Double): Double {
                target[0] = x
                target[1] = y
                target[2] = z
                targetT = if (edgeLengthSq > 0) ((x - ax) * ex + (y - ay) * ey + (z - az) * ez) / edgeLengthSq else 0.0
                targetT = targetT.coerceIn(0.0, 1.0)
                return max(error(x, y, z), 0.0)
            }

            // An outline vertex stays where it is when an inner vertex collapses into it.
            if (boundary[a] != boundary[b]) {
                return if (boundary[a]) take(ax, ay, az) else take(bx, by, bz)
            }
            if (attr != null) {
                // With texture coordinates or colours the vertex stays on the edge, where interpolating
                // them is exact: the best point on the segment minimises a quadratic in t.
                val dQd = q11 * ex * ex + 2 * q12 * ex * ey + 2 * q13 * ex * ez + q22 * ey * ey + 2 * q23 * ey * ez + q33 * ez * ez
                val dQa = ex * (q11 * ax + q12 * ay + q13 * az + q14) +
                    ey * (q12 * ax + q22 * ay + q23 * az + q24) +
                    ez * (q13 * ax + q23 * ay + q33 * az + q34)
                val t = if (dQd > 1e-18) (-dQa / dQd).coerceIn(0.0, 1.0) else 0.5
                return take(ax + t * ex, ay + t * ey, az + t * ez)
            }
            val det = q11 * (q22 * q33 - q23 * q23) - q12 * (q12 * q33 - q23 * q13) + q13 * (q12 * q23 - q22 * q13)
            if (abs(det) > 1e-12) {
                val rx = -q14
                val ry = -q24
                val rz = -q34
                val x = (rx * (q22 * q33 - q23 * q23) - q12 * (ry * q33 - q23 * rz) + q13 * (ry * q23 - q22 * rz)) / det
                val y = (q11 * (ry * q33 - q23 * rz) - rx * (q12 * q33 - q23 * q13) + q13 * (q12 * rz - ry * q13)) / det
                val z = (q11 * (q22 * rz - ry * q23) - q12 * (q12 * rz - ry * q13) + rx * (q12 * q23 - q22 * q13)) / det
                val mx = x - (ax + bx) / 2
                val my = y - (ay + by) / 2
                val mz = z - (az + bz) / 2
                // A solution far from the edge would pull the surface into a spike.
                if (mx * mx + my * my + mz * mz <= edgeLengthSq) return take(x, y, z)
            }
            val ea = error(ax, ay, az)
            val eb = error(bx, by, bz)
            val em = error((ax + bx) / 2, (ay + by) / 2, (az + bz) / 2)
            return when {
                em <= ea && em <= eb -> take((ax + bx) / 2, (ay + by) / 2, (az + bz) / 2)
                ea <= eb -> take(ax, ay, az)
                else -> take(bx, by, bz)
            }
        }

        private fun push(a: Int, b: Int) {
            heap.push(evaluate(a, b), a, b, version[a], version[b])
        }

        private val before = DoubleArray(3)
        private val after = DoubleArray(3)

        private fun collapse(a: Int, b: Int) {
            val shared = sharedTriangles(a, b)
            if (shared == 0) return
            if (boundary[a] && boundary[b] && shared != 1) return
            if (!linkConditionHolds(a, b, shared)) return
            evaluate(a, b)
            if (!movePreservesFaces(a, b) || !movePreservesFaces(b, a)) return

            p[a * 3] = target[0]
            p[a * 3 + 1] = target[1]
            p[a * 3 + 2] = target[2]
            if (attr != null) {
                val t = targetT.toFloat()
                for (k in 0 until attributeSize) {
                    val ia = a * attributeSize + k
                    attr[ia] = attr[ia] * (1 - t) + attr[b * attributeSize + k] * t
                }
            }
            for (k in 0 until 10) q[a * 10 + k] += q[b * 10 + k]
            val list = vertexTriangles[b]!!
            for (i in 0 until vertexTriangleCount[b]) {
                val t = list[i]
                if (triDead[t]) continue
                val o = t * 3
                if (tri[o] == a || tri[o + 1] == a || tri[o + 2] == a) {
                    triDead[t] = true
                    live--
                } else {
                    for (k in 0 until 3) if (tri[o + k] == b) tri[o + k] = a
                    addTriangle(a, t)
                }
            }
            vertexDead[b] = true
            boundary[a] = boundary[a] || boundary[b]
            // Only edges at the merged vertex change cost; the new version retires their old entries.
            version[a]++
            compactTriangleList(a)
            markStamp++
            val around = vertexTriangles[a]!!
            for (i in 0 until vertexTriangleCount[a]) {
                val o = around[i] * 3
                for (k in 0 until 3) {
                    val n = tri[o + k]
                    if (n == a || mark[n] == markStamp) continue
                    mark[n] = markStamp
                    push(a, n)
                }
            }
        }

        private fun compactTriangleList(v: Int) {
            val list = vertexTriangles[v]!!
            var n = 0
            for (i in 0 until vertexTriangleCount[v]) {
                val t = list[i]
                if (!triDead[t]) list[n++] = t
            }
            vertexTriangleCount[v] = n
        }

        /** Vertices next to [v] in live triangles, marked with the current stamp; returns how many. */
        private fun markNeighbours(v: Int): Int {
            var count = 0
            val list = vertexTriangles[v]!!
            for (i in 0 until vertexTriangleCount[v]) {
                val t = list[i]
                if (triDead[t]) continue
                for (k in 0 until 3) {
                    val n = tri[t * 3 + k]
                    if (n != v && mark[n] != markStamp) {
                        mark[n] = markStamp
                        count++
                    }
                }
            }
            return count
        }

        /** The two ends of an edge may only share the vertices opposite it, or the surface would pinch. */
        private fun linkConditionHolds(a: Int, b: Int, shared: Int): Boolean {
            markStamp++
            markNeighbours(a)
            var common = 0
            val list = vertexTriangles[b]!!
            val stampA = markStamp
            markStamp++
            for (i in 0 until vertexTriangleCount[b]) {
                val t = list[i]
                if (triDead[t]) continue
                for (k in 0 until 3) {
                    val n = tri[t * 3 + k]
                    if (n == b || n == a) continue
                    if (mark[n] == stampA) {
                        mark[n] = markStamp
                        common++
                    }
                }
            }
            return common == shared
        }

        /** Moving [moved] to the target must not flip or flatten any triangle that stays. */
        private fun movePreservesFaces(moved: Int, other: Int): Boolean {
            val list = vertexTriangles[moved]!!
            for (i in 0 until vertexTriangleCount[moved]) {
                val t = list[i]
                if (triDead[t]) continue
                val o = t * 3
                val a = tri[o]
                val b = tri[o + 1]
                val c = tri[o + 2]
                if (a == other || b == other || c == other) continue
                val areaBefore = faceNormal(a, b, c, before)
                if (areaBefore <= 0.0) continue
                val sx = p[moved * 3]
                val sy = p[moved * 3 + 1]
                val sz = p[moved * 3 + 2]
                p[moved * 3] = target[0]
                p[moved * 3 + 1] = target[1]
                p[moved * 3 + 2] = target[2]
                val areaAfter = faceNormal(a, b, c, after)
                p[moved * 3] = sx
                p[moved * 3 + 1] = sy
                p[moved * 3 + 2] = sz
                if (areaAfter <= areaBefore * 1e-4) return false
                if (before[0] * after[0] + before[1] * after[1] + before[2] * after[2] < MIN_NORMAL_COSINE) return false
            }
            return true
        }

        private fun compact(): Result {
            val remap = IntArray(vertexCount) { -1 }
            var count = 0
            val out = IntArray(live * 3)
            var n = 0
            for (t in 0 until triangleCount) {
                if (triDead[t]) continue
                for (k in 0 until 3) {
                    val v = tri[t * 3 + k]
                    if (remap[v] < 0) remap[v] = count++
                    out[n++] = remap[v]
                }
            }
            val positions = FloatArray(count * 3)
            val attributes = if (attr != null) FloatArray(count * attributeSize) else null
            for (v in 0 until vertexCount) {
                val r = remap[v]
                if (r < 0) continue
                for (k in 0 until 3) positions[r * 3 + k] = (p[v * 3 + k] * unit + offset[k]).toFloat()
                if (attributes != null) System.arraycopy(attr!!, v * attributeSize, attributes, r * attributeSize, attributeSize)
            }
            return Result(positions, out.copyOf(n), attributes, attributeSize)
        }
    }

    private fun edgeKey(a: Int, b: Int): Long = (min(a, b).toLong() shl 32) or max(a, b).toLong()

    /** Binary min-heap of candidate collapses, with the vertex versions they were computed for. */
    private class EdgeHeap {
        private var cost = DoubleArray(1024)
        private var ea = IntArray(1024)
        private var eb = IntArray(1024)
        private var va = IntArray(1024)
        private var vb = IntArray(1024)
        private var size = 0
        var poppedA = 0
        var poppedB = 0
        var poppedVersionA = 0
        var poppedVersionB = 0

        fun isEmpty() = size == 0

        fun clear() {
            size = 0
        }

        fun push(c: Double, a: Int, b: Int, versionA: Int, versionB: Int) {
            if (size == cost.size) {
                val capacity = size * 2
                cost = cost.copyOf(capacity)
                ea = ea.copyOf(capacity)
                eb = eb.copyOf(capacity)
                va = va.copyOf(capacity)
                vb = vb.copyOf(capacity)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (cost[parent] <= c) break
                move(parent, i)
                i = parent
            }
            cost[i] = c
            ea[i] = a
            eb[i] = b
            va[i] = versionA
            vb[i] = versionB
        }

        fun pop() {
            poppedA = ea[0]
            poppedB = eb[0]
            poppedVersionA = va[0]
            poppedVersionB = vb[0]
            size--
            if (size == 0) return
            val c = cost[size]
            val a = ea[size]
            val b = eb[size]
            val xa = va[size]
            val xb = vb[size]
            var i = 0
            while (true) {
                var child = i * 2 + 1
                if (child >= size) break
                if (child + 1 < size && cost[child + 1] < cost[child]) child++
                if (cost[child] >= c) break
                move(child, i)
                i = child
            }
            cost[i] = c
            ea[i] = a
            eb[i] = b
            va[i] = xa
            vb[i] = xb
        }

        private fun move(from: Int, to: Int) {
            cost[to] = cost[from]
            ea[to] = ea[from]
            eb[to] = eb[from]
            va[to] = va[from]
            vb[to] = vb[from]
        }
    }
}
