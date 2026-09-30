package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.hypot

internal object TestShapes {

    fun flatDepth(size: Int = 64): DepthMap = DepthMap(size, size, FloatArray(size * size) { 0.5f })

    /** Nearer (bigger) towards the right of the photo. */
    fun rampDepth(size: Int = 64): DepthMap = DepthMap(size, size, FloatArray(size * size) { (it % size).toFloat() / (size - 1) })

    fun mask(width: Int, height: Int, inside: (x: Int, y: Int) -> Boolean): SubjectMask =
        SubjectMask(width, height, FloatArray(width * height) { if (inside(it % width, it / width)) 1f else 0f })

    fun disk(width: Int, height: Int, cx: Float, cy: Float, r: Float): SubjectMask =
        mask(width, height) { x, y -> hypot(x - cx, y - cy) <= r }

    /** Checks the mesh is closed: every directed edge appears once and so does its reverse. */
    fun openEdges(mesh: Mesh3D): Int {
        val directed = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) {
            for (e in 0 until 3) {
                val a = mesh.indices[t * 3 + e].toLong()
                val b = mesh.indices[t * 3 + (e + 1) % 3].toLong()
                directed.merge((a shl 32) or b, 1, Int::plus)
            }
        }
        var open = 0
        for ((key, count) in directed) {
            val a = key ushr 32
            val b = key and 0xFFFFFFFFL
            if (count != 1 || directed[(b shl 32) or a] != 1) open++
        }
        return open
    }
}
