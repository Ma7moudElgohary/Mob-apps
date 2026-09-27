package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.abs
import kotlin.math.max

object MeshBuilder {
    fun fromDepth(
        depth: DepthMap,
        mask: SubjectMask? = null,
        imageAspect: Float = 1f,
        columns: Int = 96,
        rows: Int = 96,
        depthScale: Float = 0.62f,
        maskThreshold: Float = 0.5f,
        maxDepthJump: Float = 0.20f,
    ): DepthMesh {
        val positions = FloatArray(columns * rows * 3)
        val tex = FloatArray(columns * rows * 2)
        val sampledDepth = FloatArray(columns * rows)
        val sampledMask = FloatArray(columns * rows) { 1f }

        var minCol = columns - 1
        var maxCol = 0
        var minRow = rows - 1
        var maxRow = 0
        var foregroundCount = 0
        var depthSum = 0f

        for (row in 0 until rows) {
            val v = row.toFloat() / (rows - 1)
            val depthY = (v * (depth.height - 1)).toInt()
            val maskY = mask?.let { (v * (it.height - 1)).toInt() } ?: 0
            for (col in 0 until columns) {
                val u = col.toFloat() / (columns - 1)
                val depthX = (u * (depth.width - 1)).toInt()
                val index = row * columns + col
                val d = depth[depthX, depthY]
                sampledDepth[index] = d

                val confidence = mask?.let {
                    val maskX = (u * (it.width - 1)).toInt()
                    it[maskX, maskY]
                } ?: 1f
                sampledMask[index] = confidence

                if (confidence >= maskThreshold) {
                    foregroundCount++
                    depthSum += d
                    if (col < minCol) minCol = col
                    if (col > maxCol) maxCol = col
                    if (row < minRow) minRow = row
                    if (row > maxRow) maxRow = row
                }
            }
        }

        if (foregroundCount == 0) {
            minCol = 0
            maxCol = columns - 1
            minRow = 0
            maxRow = rows - 1
            foregroundCount = sampledDepth.size
            depthSum = sampledDepth.sum()
            sampledMask.fill(1f)
        }

        val meanDepth = depthSum / foregroundCount
        val bboxWidth = max(1, maxCol - minCol)
        val bboxHeight = max(1, maxRow - minRow)
        val centerCol = (minCol + maxCol) * 0.5f
        val centerRow = (minRow + maxRow) * 0.5f
        val normalizedAspect = imageAspect.coerceIn(0.45f, 2.4f)
        val xSpan = 1.75f * normalizedAspect
        val ySpan = 1.75f
        val cropScale = 0.88f / max(
            (bboxWidth.toFloat() / (columns - 1)) * normalizedAspect,
            bboxHeight.toFloat() / (rows - 1),
        ).coerceAtLeast(0.25f)

        var p = 0
        var t = 0
        for (row in 0 until rows) {
            val v = row.toFloat() / (rows - 1)
            for (col in 0 until columns) {
                val u = col.toFloat() / (columns - 1)
                val index = row * columns + col
                val centeredU = (col - centerCol) / (columns - 1)
                val centeredV = (row - centerRow) / (rows - 1)
                positions[p++] = centeredU * xSpan * cropScale
                positions[p++] = -centeredV * ySpan * cropScale
                positions[p++] = (sampledDepth[index] - meanDepth) * depthScale
                tex[t++] = u
                tex[t++] = 1f - v
            }
        }

        val indexBuffer = ArrayList<Int>((columns - 1) * (rows - 1) * 6)
        fun usable(a: Int, b: Int, c: Int): Boolean {
            if (sampledMask[a] < maskThreshold || sampledMask[b] < maskThreshold || sampledMask[c] < maskThreshold) return false
            val da = sampledDepth[a]
            val db = sampledDepth[b]
            val dc = sampledDepth[c]
            return abs(da - db) <= maxDepthJump && abs(da - dc) <= maxDepthJump && abs(db - dc) <= maxDepthJump
        }

        for (row in 0 until rows - 1) {
            for (col in 0 until columns - 1) {
                val a = row * columns + col
                val b = a + 1
                val c = a + columns
                val d = c + 1
                if (usable(a, c, b)) {
                    indexBuffer.add(a); indexBuffer.add(c); indexBuffer.add(b)
                }
                if (usable(b, c, d)) {
                    indexBuffer.add(b); indexBuffer.add(c); indexBuffer.add(d)
                }
            }
        }

        return DepthMesh(
            positions = positions,
            texCoords = tex,
            indices = indexBuffer.toIntArray(),
            columns = columns,
            rows = rows,
        )
    }
}
