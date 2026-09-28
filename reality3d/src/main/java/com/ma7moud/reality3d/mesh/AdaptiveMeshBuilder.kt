package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.abs
import kotlin.math.max

object AdaptiveMeshBuilder {
    fun fromDepth(
        depth: DepthMap,
        mask: SubjectMask,
        imageAspect: Float,
        depthScale: Float = 0.72f,
        maskThreshold: Float = 0.48f,
        depthSplitThreshold: Float = 0.035f,
        maxDepthJump: Float = 0.18f,
        minCellPixels: Int = 5,
    ): DepthMesh {
        val positions = ArrayList<Float>()
        val texCoords = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val vertexMap = HashMap<Long, Int>()

        val maskBounds = mask.bounds(maskThreshold)
        val centerU = (maskBounds[0] + maskBounds[2]) * 0.5f / (mask.width - 1).coerceAtLeast(1)
        val centerV = (maskBounds[1] + maskBounds[3]) * 0.5f / (mask.height - 1).coerceAtLeast(1)
        val boxW = ((maskBounds[2] - maskBounds[0]).toFloat() / mask.width.coerceAtLeast(1)).coerceAtLeast(0.05f)
        val boxH = ((maskBounds[3] - maskBounds[1]).toFloat() / mask.height.coerceAtLeast(1)).coerceAtLeast(0.05f)
        val aspect = imageAspect.coerceIn(0.4f, 2.6f)
        val fit = 1.72f / max(boxW * aspect, boxH)

        var sum = 0f
        var count = 0
        for (y in 0 until depth.height step 4) {
            for (x in 0 until depth.width step 4) {
                val u = x.toFloat() / (depth.width - 1)
                val v = y.toFloat() / (depth.height - 1)
                if (mask.sampleNormalized(u, v) >= maskThreshold) {
                    sum += depth[x, y]
                    count++
                }
            }
        }
        val meanDepth = if (count == 0) 0.5f else sum / count

        fun confidence(x: Int, y: Int): Float = mask.sampleNormalized(
            x.toFloat() / (depth.width - 1),
            y.toFloat() / (depth.height - 1),
        )

        fun vertex(x: Int, y: Int): Int {
            val key = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
            return vertexMap.getOrPut(key) {
                val u = x.toFloat() / (depth.width - 1)
                val v = y.toFloat() / (depth.height - 1)
                val px = (u - centerU) * aspect * fit
                val py = -(v - centerV) * fit
                val pz = (depth[x, y] - meanDepth) * depthScale
                val index = positions.size / 3
                positions.add(px)
                positions.add(py)
                positions.add(pz)
                texCoords.add(u)
                texCoords.add(1f - v)
                index
            }
        }

        fun addTriangle(ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int) {
            val ca = confidence(ax, ay)
            val cb = confidence(bx, by)
            val cc = confidence(cx, cy)
            if (ca < maskThreshold || cb < maskThreshold || cc < maskThreshold) return
            val da = depth[ax, ay]
            val db = depth[bx, by]
            val dc = depth[cx, cy]
            if (
                abs(da - db) > maxDepthJump ||
                abs(da - dc) > maxDepthJump ||
                abs(db - dc) > maxDepthJump
            ) return
            indices.add(vertex(ax, ay))
            indices.add(vertex(bx, by))
            indices.add(vertex(cx, cy))
        }

        fun emitCell(x0: Int, y0: Int, x1: Int, y1: Int) {
            val d00 = depth[x0, y0]
            val d10 = depth[x1, y0]
            val d01 = depth[x0, y1]
            val d11 = depth[x1, y1]
            if (abs(d00 - d11) < abs(d10 - d01)) {
                addTriangle(x0, y0, x0, y1, x1, y1)
                addTriangle(x0, y0, x1, y1, x1, y0)
            } else {
                addTriangle(x0, y0, x0, y1, x1, y0)
                addTriangle(x1, y0, x0, y1, x1, y1)
            }
        }

        fun split(x0: Int, y0: Int, x1: Int, y1: Int, level: Int) {
            if (x1 <= x0 || y1 <= y0) return
            val mx = (x0 + x1) / 2
            val my = (y0 + y1) / 2
            val samples = intArrayOf(x0, y0, x1, y0, x0, y1, x1, y1, mx, my)
            var minDepth = Float.POSITIVE_INFINITY
            var maxDepth = Float.NEGATIVE_INFINITY
            var foreground = 0
            var background = 0
            var sampleIndex = 0
            while (sampleIndex < samples.size) {
                val x = samples[sampleIndex]
                val y = samples[sampleIndex + 1]
                val c = confidence(x, y)
                if (c >= maskThreshold) foreground++ else background++
                val d = depth[x, y]
                minDepth = minOf(minDepth, d)
                maxDepth = maxOf(maxDepth, d)
                sampleIndex += 2
            }
            if (foreground == 0) return
            val shouldSplit = level < 9 &&
                (x1 - x0 > minCellPixels || y1 - y0 > minCellPixels) &&
                (background > 0 || maxDepth - minDepth > depthSplitThreshold)
            if (shouldSplit && mx > x0 && mx < x1 && my > y0 && my < y1) {
                split(x0, y0, mx, my, level + 1)
                split(mx, y0, x1, my, level + 1)
                split(x0, my, mx, y1, level + 1)
                split(mx, my, x1, y1, level + 1)
            } else {
                emitCell(x0, y0, x1, y1)
            }
        }

        split(0, 0, depth.width - 1, depth.height - 1, 0)
        val mesh = DepthMesh(
            positions = positions.toFloatArray(),
            texCoords = texCoords.toFloatArray(),
            indices = indices.toIntArray(),
        )
        return MeshCleanup.clean(mesh)
    }
}
