package com.ma7moud.reality3d.scan

import kotlin.math.max

/** Clean-up steps for scanned meshes. */
internal object MeshOps {

    /** Keeps connected pieces with at least [minFraction] of the largest piece's triangles; drops unused vertices. */
    fun keepLargePieces(mesh: IndexedMesh, minFraction: Float): IndexedMesh {
        val parent = IntArray(mesh.vertexCount) { it }
        fun find(v: Int): Int {
            var root = v
            while (parent[root] != root) root = parent[root]
            var node = v
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val indices = mesh.indices
        for (t in indices.indices step 3) {
            union(indices[t], indices[t + 1])
            union(indices[t], indices[t + 2])
        }
        val trianglesPerRoot = HashMap<Int, Int>()
        for (t in indices.indices step 3) trianglesPerRoot.merge(find(indices[t]), 1, Int::plus)
        val largest = trianglesPerRoot.values.maxOrNull() ?: return mesh
        val keep = max(1, (largest * minFraction).toInt())
        val kept = IntList(indices.size)
        for (t in indices.indices step 3) {
            if ((trianglesPerRoot[find(indices[t])] ?: 0) >= keep) {
                kept.add(indices[t])
                kept.add(indices[t + 1])
                kept.add(indices[t + 2])
            }
        }
        return compact(mesh.positions, kept.toArray())
    }

    /** Removes vertices no triangle uses. */
    fun compact(positions: FloatArray, indices: IntArray): IndexedMesh {
        val remap = IntArray(positions.size / 3) { -1 }
        var next = 0
        for (index in indices) if (remap[index] < 0) remap[index] = next++
        val newPositions = FloatArray(next * 3)
        for (old in remap.indices) {
            val new = remap[old]
            if (new < 0) continue
            newPositions[new * 3] = positions[old * 3]
            newPositions[new * 3 + 1] = positions[old * 3 + 1]
            newPositions[new * 3 + 2] = positions[old * 3 + 2]
        }
        return IndexedMesh(newPositions, IntArray(indices.size) { remap[indices[it]] })
    }

    /** Neighbour lists in compressed form: vertex v's neighbours are list[offsets[v] until offsets[v + 1]]. */
    class Neighbours(val offsets: IntArray, val list: IntArray)

    fun neighbours(vertexCount: Int, indices: IntArray): Neighbours {
        val degree = IntArray(vertexCount + 1)
        for (t in indices.indices step 3) {
            for (e in 0 until 3) degree[indices[t + e]] += 2
        }
        val offsets = IntArray(vertexCount + 1)
        for (v in 0 until vertexCount) offsets[v + 1] = offsets[v] + degree[v]
        val fill = offsets.copyOf()
        val list = IntArray(offsets[vertexCount])
        for (t in indices.indices step 3) {
            for (e in 0 until 3) {
                val a = indices[t + e]
                list[fill[a]++] = indices[t + (e + 1) % 3]
                list[fill[a]++] = indices[t + (e + 2) % 3]
            }
        }
        return Neighbours(offsets, list)
    }

    /**
     * Taubin smoothing: alternating shrink and inflate steps remove voxel steps and depth noise without
     * shrinking the shape. Vertices in [keepHeight] only move sideways, so a flat base stays flat.
     */
    fun smooth(positions: FloatArray, indices: IntArray, iterations: Int, keepHeight: BooleanArray?): FloatArray {
        val count = positions.size / 3
        val adjacency = neighbours(count, indices)
        var current = positions.copyOf()
        var scratch = FloatArray(positions.size)
        repeat(iterations) {
            for (factor in floatArrayOf(0.5f, -0.53f)) {
                for (v in 0 until count) {
                    val from = adjacency.offsets[v]
                    val to = adjacency.offsets[v + 1]
                    if (to == from) {
                        scratch[v * 3] = current[v * 3]
                        scratch[v * 3 + 1] = current[v * 3 + 1]
                        scratch[v * 3 + 2] = current[v * 3 + 2]
                        continue
                    }
                    var sx = 0f
                    var sy = 0f
                    var sz = 0f
                    for (k in from until to) {
                        val u = adjacency.list[k]
                        sx += current[u * 3]
                        sy += current[u * 3 + 1]
                        sz += current[u * 3 + 2]
                    }
                    val inverse = 1f / (to - from)
                    scratch[v * 3] = current[v * 3] + factor * (sx * inverse - current[v * 3])
                    scratch[v * 3 + 1] = if (keepHeight?.get(v) == true) current[v * 3 + 1] else current[v * 3 + 1] + factor * (sy * inverse - current[v * 3 + 1])
                    scratch[v * 3 + 2] = current[v * 3 + 2] + factor * (sz * inverse - current[v * 3 + 2])
                }
                val swap = current
                current = scratch
                scratch = swap
            }
        }
        return current
    }
}
