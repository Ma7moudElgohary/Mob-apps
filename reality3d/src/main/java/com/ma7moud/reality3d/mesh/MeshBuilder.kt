package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.depth.DepthMap

object MeshBuilder {
    fun fromDepth(depth: DepthMap, columns: Int = 72, rows: Int = 72, depthScale: Float = 0.55f): DepthMesh {
        val positions = FloatArray(columns * rows * 3)
        val tex = FloatArray(columns * rows * 2)
        val sampled = FloatArray(columns * rows)
        var mean = 0f
        for (row in 0 until rows) {
            val v = row.toFloat() / (rows - 1)
            val sy = (v * (depth.height - 1)).toInt()
            for (col in 0 until columns) {
                val u = col.toFloat() / (columns - 1)
                val sx = (u * (depth.width - 1)).toInt()
                val d = depth[sx, sy]
                sampled[row * columns + col] = d
                mean += d
            }
        }
        mean /= sampled.size
        var p = 0
        var t = 0
        for (row in 0 until rows) {
            val v = row.toFloat() / (rows - 1)
            for (col in 0 until columns) {
                val u = col.toFloat() / (columns - 1)
                positions[p++] = (u - 0.5f) * 1.7f
                positions[p++] = (0.5f - v) * 1.7f
                positions[p++] = (sampled[row * columns + col] - mean) * depthScale
                tex[t++] = u
                tex[t++] = 1f - v
            }
        }
        val indices = IntArray((columns - 1) * (rows - 1) * 6)
        var i = 0
        for (row in 0 until rows - 1) for (col in 0 until columns - 1) {
            val a = row * columns + col
            val b = a + 1
            val c = a + columns
            val d = c + 1
            indices[i++] = a; indices[i++] = c; indices[i++] = b
            indices[i++] = b; indices[i++] = c; indices[i++] = d
        }
        return DepthMesh(positions, tex, indices, columns, rows)
    }
}
