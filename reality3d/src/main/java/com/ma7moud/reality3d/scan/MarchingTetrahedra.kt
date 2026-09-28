package com.ma7moud.reality3d.scan

/** A triangle mesh with shared vertices: xyz per vertex, three indices per triangle. */
class IndexedMesh(val positions: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/**
 * Extracts the zero level of a scalar field by marching tetrahedra. Every grid cube is split into the same
 * six tetrahedra around its main diagonal, so neighbouring cubes always agree on their shared faces: the
 * surface is closed and manifold, without the cracks and ambiguous cases of marching cubes. Negative
 * values are inside; triangles are wound counter-clockwise seen from outside.
 */
object MarchingTetrahedra {

    /** Corners as bit masks (bit 0 = +x, bit 1 = +y, bit 2 = +z): 000 → e_a → e_a + e_b → 111 per axis order. */
    private val TETRAHEDRA: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 3, 7),
        intArrayOf(0, 1, 5, 7),
        intArrayOf(0, 2, 3, 7),
        intArrayOf(0, 2, 6, 7),
        intArrayOf(0, 4, 5, 7),
        intArrayOf(0, 4, 6, 7),
    )

    /** +1 when a tetrahedron's corners are listed in right-handed order, -1 otherwise. */
    private val ORIENTATION: IntArray = IntArray(TETRAHEDRA.size) { t ->
        val c = TETRAHEDRA[t]
        fun offset(corner: Int) = intArrayOf(corner and 1, (corner shr 1) and 1, (corner shr 2) and 1)
        val a = offset(c[1])
        val b = offset(c[2])
        val d = offset(c[3])
        val det = a[0] * (b[1] * d[2] - b[2] * d[1]) - a[1] * (b[0] * d[2] - b[2] * d[0]) + a[2] * (b[0] * d[1] - b[1] * d[0])
        if (det > 0) 1 else -1
    }

    /**
     * [field] holds nx·ny·nz values indexed (z·ny + y)·nx + x; grid point (x, y, z) sits at
     * origin + (x, y, z)·[spacing].
     */
    fun extract(
        field: FloatArray,
        nx: Int,
        ny: Int,
        nz: Int,
        originX: Float,
        originY: Float,
        originZ: Float,
        spacing: Float,
    ): IndexedMesh {
        require(field.size == nx * ny * nz)
        val positions = FloatList(1 shl 16)
        val indices = IntList(1 shl 16)
        val edgeVertices = IntIntMap()
        val grid = IntArray(8)
        val value = FloatArray(8)
        val corner = FloatArray(24)

        fun vertexOnEdge(low: Int, high: Int): Int {
            // In these tetrahedra one corner's bits always contain the other's, so the edge runs from the
            // lower corner in one of seven positive directions.
            val key = grid[low] * 7 + ((low xor high) - 1)
            return edgeVertices.getOrPut(key) {
                val t = value[low] / (value[low] - value[high])
                positions.add(corner[low * 3] + t * (corner[high * 3] - corner[low * 3]))
                positions.add(corner[low * 3 + 1] + t * (corner[high * 3 + 1] - corner[low * 3 + 1]))
                positions.add(corner[low * 3 + 2] + t * (corner[high * 3 + 2] - corner[low * 3 + 2]))
                positions.size / 3 - 1
            }
        }

        fun edge(a: Int, b: Int) = if (Integer.bitCount(a) < Integer.bitCount(b)) vertexOnEdge(a, b) else vertexOnEdge(b, a)

        fun emit(p: Int, q: Int, r: Int) {
            indices.add(p)
            indices.add(q)
            indices.add(r)
        }

        // Corners of the current tetrahedron ordered lone/inside corners first, as positions in the tetrahedron.
        val order = IntArray(4)
        for (z in 0 until nz - 1) {
            for (y in 0 until ny - 1) {
                for (x in 0 until nx - 1) {
                    var insideCount = 0
                    for (c in 0 until 8) {
                        val cx = x + (c and 1)
                        val cy = y + ((c shr 1) and 1)
                        val cz = z + ((c shr 2) and 1)
                        val index = (cz * ny + cy) * nx + cx
                        grid[c] = index
                        value[c] = field[index]
                        if (value[c] < 0f) insideCount++
                    }
                    if (insideCount == 0 || insideCount == 8) continue
                    for (c in 0 until 8) {
                        corner[c * 3] = originX + (x + (c and 1)) * spacing
                        corner[c * 3 + 1] = originY + (y + ((c shr 1) and 1)) * spacing
                        corner[c * 3 + 2] = originZ + (z + ((c shr 2) and 1)) * spacing
                    }
                    for (t in TETRAHEDRA.indices) {
                        val tet = TETRAHEDRA[t]
                        var insideN = 0
                        for (k in 0 until 4) if (value[tet[k]] < 0f) insideN++
                        if (insideN == 0 || insideN == 4) continue
                        // Put the lone corner (or, for two and two, the inside pair) first.
                        var n = 0
                        if (insideN == 3) {
                            for (k in 0 until 4) if (value[tet[k]] >= 0f) order[n++] = k
                            for (k in 0 until 4) if (value[tet[k]] < 0f) order[n++] = k
                        } else {
                            for (k in 0 until 4) if (value[tet[k]] < 0f) order[n++] = k
                            for (k in 0 until 4) if (value[tet[k]] >= 0f) order[n++] = k
                        }
                        // Make (order) a right-handed listing: flip the last two when parity says otherwise.
                        if (ORIENTATION[t] * parity(order) < 0) {
                            val swap = order[2]
                            order[2] = order[3]
                            order[3] = swap
                        }
                        val p = tet[order[0]]
                        val q = tet[order[1]]
                        val r = tet[order[2]]
                        val w = tet[order[3]]
                        // In a right-handed tetrahedron (p, q, r, w), triangle (pq, pr, pw) faces away from p.
                        when (insideN) {
                            1 -> emit(edge(p, q), edge(p, r), edge(p, w))
                            3 -> emit(edge(p, q), edge(p, w), edge(p, r))
                            else -> {
                                val pr = edge(p, r)
                                val pw = edge(p, w)
                                val qw = edge(q, w)
                                val qr = edge(q, r)
                                emit(pr, pw, qw)
                                emit(pr, qw, qr)
                            }
                        }
                    }
                }
            }
        }
        return IndexedMesh(positions.toArray(), indices.toArray())
    }

    /** +1 for an even permutation of 0..3, -1 for an odd one. */
    private fun parity(order: IntArray): Int {
        var inversions = 0
        for (i in 0 until 4) for (j in i + 1 until 4) if (order[i] > order[j]) inversions++
        return if (inversions % 2 == 0) 1 else -1
    }
}
