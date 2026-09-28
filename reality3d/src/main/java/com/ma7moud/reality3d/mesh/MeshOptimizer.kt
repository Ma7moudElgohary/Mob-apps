package com.ma7moud.reality3d.mesh

import kotlin.math.cbrt
import kotlin.math.max

data class GameReadyMeshes(
    val lod0: DepthMesh,
    val lod1: DepthMesh,
    val lod2: DepthMesh,
    val collision: DepthMesh,
)

object MeshOptimizer {
    fun gameReady(mesh: DepthMesh, lod0Triangles: Int = 25_000): GameReadyMeshes {
        val lod0 = simplify(mesh, lod0Triangles)
        val lod1 = simplify(lod0, minOf(10_000, max(500, lod0Triangles / 2)))
        val lod2 = simplify(lod1, minOf(5_000, max(200, lod0Triangles / 5)))
        val collision = simplify(lod2, minOf(800, max(80, lod0Triangles / 25)))
        return GameReadyMeshes(lod0, lod1, lod2, collision)
    }

    fun simplify(mesh: DepthMesh, targetTriangles: Int): DepthMesh {
        if (mesh.triangleCount <= targetTriangles || targetTriangles < 4) return mesh
        val targetVertices = max(8, targetTriangles / 2)
        var resolution = max(3, cbrt(targetVertices.toDouble()).toInt())
        var best = mesh
        repeat(6) {
            val candidate = cluster(mesh, resolution)
            best = candidate
            if (candidate.triangleCount <= targetTriangles) return candidate
            resolution = max(2, (resolution * 0.78f).toInt())
        }
        return best
    }

    private data class Acc(
        var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
        var u: Float = 0f, var v: Float = 0f,
        var r: Float = 0f, var g: Float = 0f, var b: Float = 0f, var a: Float = 0f,
        var count: Int = 0,
    )

    private fun cluster(mesh: DepthMesh, resolution: Int): DepthMesh {
        val bounds = MeshMath.bounds(mesh)
        val sx = (bounds[3] - bounds[0]).coerceAtLeast(1e-6f)
        val sy = (bounds[4] - bounds[1]).coerceAtLeast(1e-6f)
        val sz = (bounds[5] - bounds[2]).coerceAtLeast(1e-6f)
        val accumulators = LinkedHashMap<Long, Acc>()
        val oldToKey = LongArray(mesh.vertexCount)
        for (vertex in 0 until mesh.vertexCount) {
            val p = vertex * 3
            val gx = (((mesh.positions[p] - bounds[0]) / sx) * resolution).toInt().coerceIn(0, resolution)
            val gy = (((mesh.positions[p + 1] - bounds[1]) / sy) * resolution).toInt().coerceIn(0, resolution)
            val gz = (((mesh.positions[p + 2] - bounds[2]) / sz) * resolution).toInt().coerceIn(0, resolution)
            val key = (gx.toLong() shl 42) xor (gy.toLong() shl 21) xor gz.toLong()
            oldToKey[vertex] = key
            val acc = accumulators.getOrPut(key) { Acc() }
            acc.x += mesh.positions[p]; acc.y += mesh.positions[p + 1]; acc.z += mesh.positions[p + 2]
            if (mesh.texCoords.size >= vertex * 2 + 2) {
                acc.u += mesh.texCoords[vertex * 2]; acc.v += mesh.texCoords[vertex * 2 + 1]
            }
            mesh.colors?.takeIf { it.size >= vertex * 4 + 4 }?.let {
                acc.r += it[vertex * 4]; acc.g += it[vertex * 4 + 1]; acc.b += it[vertex * 4 + 2]; acc.a += it[vertex * 4 + 3]
            }
            acc.count++
        }
        val keyToNew = HashMap<Long, Int>()
        val positions = FloatArray(accumulators.size * 3)
        val tex = FloatArray(accumulators.size * 2)
        val colors = if (mesh.colors != null) FloatArray(accumulators.size * 4) else null
        var n = 0
        accumulators.forEach { (key, acc) ->
            keyToNew[key] = n
            val c = acc.count.coerceAtLeast(1).toFloat()
            positions[n * 3] = acc.x / c; positions[n * 3 + 1] = acc.y / c; positions[n * 3 + 2] = acc.z / c
            tex[n * 2] = acc.u / c; tex[n * 2 + 1] = acc.v / c
            colors?.let {
                it[n * 4] = acc.r / c; it[n * 4 + 1] = acc.g / c; it[n * 4 + 2] = acc.b / c; it[n * 4 + 3] = if (acc.a == 0f) 1f else acc.a / c
            }
            n++
        }
        val remap = IntArray(mesh.vertexCount) { keyToNew.getValue(oldToKey[it]) }
        val indices = ArrayList<Int>()
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val a = remap[mesh.indices[i]]; val b = remap[mesh.indices[i + 1]]; val c = remap[mesh.indices[i + 2]]
            if (a != b && b != c && a != c) { indices.add(a); indices.add(b); indices.add(c) }
            i += 3
        }
        return MeshMath.recalculateNormals(
            DepthMesh(positions, tex, indices.toIntArray(), colors = colors, unitsToMeters = mesh.unitsToMeters),
        )
    }
}
