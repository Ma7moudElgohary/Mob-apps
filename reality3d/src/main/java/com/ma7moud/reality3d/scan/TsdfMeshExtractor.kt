package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath

object TsdfMeshExtractor {
    private val tetrahedra = arrayOf(
        intArrayOf(0, 5, 1, 6), intArrayOf(0, 1, 2, 6), intArrayOf(0, 2, 3, 6),
        intArrayOf(0, 3, 7, 6), intArrayOf(0, 7, 4, 6), intArrayOf(0, 4, 5, 6),
    )
    private val corners = arrayOf(
        intArrayOf(0,0,0), intArrayOf(1,0,0), intArrayOf(1,1,0), intArrayOf(0,1,0),
        intArrayOf(0,0,1), intArrayOf(1,0,1), intArrayOf(1,1,1), intArrayOf(0,1,1),
    )

    fun extract(volume: SparseTsdfVolume, minWeight: Float = 0.4f): DepthMesh {
        val snapshot = volume.snapshot()
        if (snapshot.isEmpty()) return DepthMesh(FloatArray(0), FloatArray(0), IntArray(0))
        val cells = HashSet<SparseTsdfVolume.Key>()
        snapshot.keys.forEach { k ->
            for (dx in -1..0) for (dy in -1..0) for (dz in -1..0) cells += SparseTsdfVolume.Key(k.x + dx, k.y + dy, k.z + dz)
        }
        val positions = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun vertex(p: Vector3, c: FloatArray): Int {
            val index = positions.size / 3
            positions += p.x; positions += p.y; positions += p.z
            colors += c[0]; colors += c[1]; colors += c[2]; colors += 1f
            return index
        }

        fun interpolate(p1: Vector3, p2: Vector3, v1: Float, v2: Float, c1: FloatArray, c2: FloatArray): Pair<Vector3, FloatArray> {
            val denom = v1 - v2
            val t = if (kotlin.math.abs(denom) < 1e-6f) 0.5f else (v1 / denom).coerceIn(0f, 1f)
            return (p1 + (p2 - p1) * t) to floatArrayOf(
                c1[0] + (c2[0] - c1[0]) * t,
                c1[1] + (c2[1] - c1[1]) * t,
                c1[2] + (c2[2] - c1[2]) * t,
            )
        }

        for (cell in cells) {
            val samples = arrayOfNulls<SparseTsdfVolume.Voxel>(8)
            val points = Array(8) { i ->
                val c = corners[i]
                volume.keyToWorld(SparseTsdfVolume.Key(cell.x + c[0], cell.y + c[1], cell.z + c[2]))
            }
            var valid = true
            for (i in 0..7) {
                val c = corners[i]
                val v = snapshot[SparseTsdfVolume.Key(cell.x + c[0], cell.y + c[1], cell.z + c[2])]
                if (v == null || v.weight < minWeight) { valid = false; break }
                samples[i] = v
            }
            if (!valid) continue
            for (tet in tetrahedra) {
                val inside = tet.filter { samples[it]!!.tsdf < 0f }
                if (inside.isEmpty() || inside.size == 4) continue
                val outside = tet.filter { samples[it]!!.tsdf >= 0f }
                fun edge(a: Int, b: Int): Pair<Vector3, FloatArray> {
                    val va = samples[a]!!; val vb = samples[b]!!
                    return interpolate(points[a], points[b], va.tsdf, vb.tsdf, floatArrayOf(va.r,va.g,va.b), floatArrayOf(vb.r,vb.g,vb.b))
                }
                val verts = ArrayList<Pair<Vector3, FloatArray>>()
                inside.forEach { a -> outside.forEach { b -> verts += edge(a,b) } }
                if (verts.size == 3) {
                    val a = vertex(verts[0].first, verts[0].second); val b = vertex(verts[1].first, verts[1].second); val c = vertex(verts[2].first, verts[2].second)
                    indices += a; indices += b; indices += c
                } else if (verts.size == 4) {
                    val a = vertex(verts[0].first, verts[0].second); val b = vertex(verts[1].first, verts[1].second); val c = vertex(verts[2].first, verts[2].second); val d = vertex(verts[3].first, verts[3].second)
                    indices += a; indices += b; indices += c; indices += a; indices += c; indices += d
                }
            }
        }
        val mesh = DepthMesh(positions.toFloatArray(), FloatArray((positions.size / 3) * 2), indices.toIntArray(), colors = colors.toFloatArray(), unitsToMeters = 1f)
        return MeshMath.recalculateNormals(mesh)
    }
}
