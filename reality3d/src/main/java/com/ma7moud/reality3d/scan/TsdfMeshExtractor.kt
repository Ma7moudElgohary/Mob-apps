package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import kotlin.math.abs

object TsdfMeshExtractor {
    private val tetrahedra = arrayOf(
        intArrayOf(0, 5, 1, 6), intArrayOf(0, 1, 2, 6), intArrayOf(0, 2, 3, 6),
        intArrayOf(0, 3, 7, 6), intArrayOf(0, 7, 4, 6), intArrayOf(0, 4, 5, 6),
    )
    private val corners = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(1, 1, 0), intArrayOf(0, 1, 0),
        intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 1), intArrayOf(0, 1, 1),
    )

    /**
     * Canonical grid-edge identity. Marching tetrahedra revisits the same zero-crossing edge
     * from adjacent tetrahedra/cells; sharing the resulting vertex keeps the extracted mesh
     * indexed instead of emitting large numbers of coincident duplicate vertices.
     */
    private data class GridEdge(
        val a: SparseTsdfVolume.Key,
        val b: SparseTsdfVolume.Key,
    )

    fun extract(volume: SparseTsdfVolume, minWeight: Float = 0.4f): DepthMesh {
        val snapshot = volume.snapshot()
        if (snapshot.isEmpty()) return DepthMesh(FloatArray(0), FloatArray(0), IntArray(0))

        val cells = HashSet<SparseTsdfVolume.Key>()
        snapshot.keys.forEach { k ->
            for (dx in -1..0) {
                for (dy in -1..0) {
                    for (dz in -1..0) {
                        cells += SparseTsdfVolume.Key(k.x + dx, k.y + dy, k.z + dz)
                    }
                }
            }
        }

        val positions = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val edgeVertices = HashMap<GridEdge, Int>()

        fun keyBefore(a: SparseTsdfVolume.Key, b: SparseTsdfVolume.Key): Boolean =
            a.x < b.x ||
                (a.x == b.x && a.y < b.y) ||
                (a.x == b.x && a.y == b.y && a.z <= b.z)

        fun canonicalEdge(a: SparseTsdfVolume.Key, b: SparseTsdfVolume.Key): GridEdge =
            if (keyBefore(a, b)) GridEdge(a, b) else GridEdge(b, a)

        fun vertex(p: Vector3, c: FloatArray): Int {
            val index = positions.size / 3
            positions += p.x
            positions += p.y
            positions += p.z
            colors += c[0]
            colors += c[1]
            colors += c[2]
            colors += 1f
            return index
        }

        fun interpolate(
            p1: Vector3,
            p2: Vector3,
            v1: Float,
            v2: Float,
            c1: FloatArray,
            c2: FloatArray,
        ): Pair<Vector3, FloatArray> {
            val denom = v1 - v2
            val t = if (abs(denom) < 1e-6f) 0.5f else (v1 / denom).coerceIn(0f, 1f)
            return (p1 + (p2 - p1) * t) to floatArrayOf(
                c1[0] + (c2[0] - c1[0]) * t,
                c1[1] + (c2[1] - c1[1]) * t,
                c1[2] + (c2[2] - c1[2]) * t,
            )
        }

        fun addTriangle(a: Int, b: Int, c: Int) {
            if (a == b || b == c || a == c) return
            indices += a
            indices += b
            indices += c
        }

        for (cell in cells) {
            val samples = arrayOfNulls<SparseTsdfVolume.Voxel>(8)
            val sampleKeys = Array(8) { i ->
                val c = corners[i]
                SparseTsdfVolume.Key(cell.x + c[0], cell.y + c[1], cell.z + c[2])
            }
            val points = Array(8) { i -> volume.keyToWorld(sampleKeys[i]) }

            var valid = true
            for (i in 0..7) {
                val voxel = snapshot[sampleKeys[i]]
                if (voxel == null || voxel.weight < minWeight) {
                    valid = false
                    break
                }
                samples[i] = voxel
            }
            if (!valid) continue

            fun edgeVertex(a: Int, b: Int): Int {
                val gridEdge = canonicalEdge(sampleKeys[a], sampleKeys[b])
                return edgeVertices.getOrPut(gridEdge) {
                    val va = samples[a]!!
                    val vb = samples[b]!!
                    val (position, color) = interpolate(
                        points[a],
                        points[b],
                        va.tsdf,
                        vb.tsdf,
                        floatArrayOf(va.r, va.g, va.b),
                        floatArrayOf(vb.r, vb.g, vb.b),
                    )
                    vertex(position, color)
                }
            }

            for (tet in tetrahedra) {
                val inside = tet.filter { samples[it]!!.tsdf < 0f }
                if (inside.isEmpty() || inside.size == 4) continue
                val outside = tet.filter { samples[it]!!.tsdf >= 0f }

                val verts = ArrayList<Int>(4)
                inside.forEach { a ->
                    outside.forEach { b -> verts += edgeVertex(a, b) }
                }

                if (verts.size == 3) {
                    addTriangle(verts[0], verts[1], verts[2])
                } else if (verts.size == 4) {
                    addTriangle(verts[0], verts[1], verts[2])
                    addTriangle(verts[0], verts[2], verts[3])
                }
            }
        }

        val vertexCount = positions.size / 3
        val mesh = DepthMesh(
            vertices = positions.toFloatArray(),
            uvs = FloatArray(vertexCount * 2),
            indices = indices.toIntArray(),
            colors = colors.toFloatArray(),
            unitsToMeters = 1f,
        )
        return MeshMath.recalculateNormals(mesh)
    }
}
