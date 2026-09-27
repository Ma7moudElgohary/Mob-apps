package com.ma7moud.reality3d.mesh

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Balloon" heights for a silhouette on a vertex grid.
 *
 * Solves the Poisson equation ∇²u = -1 on the interior vertices (u = 0 everywhere else) and returns
 * h = sqrt(2u). For a strip of half-width w this is exactly a circular cross-section of radius w,
 * so every part of the subject gets a rounded thickness that matches how wide it is.
 */
internal object Inflation {

    private const val TOLERANCE = 1e-3f

    /** Heights in grid cells for every vertex; zero outside [interior]. */
    fun heights(interior: BooleanArray, cols: Int, rows: Int): FloatArray {
        require(interior.size == cols * rows)
        var minX = cols
        var maxX = -1
        var minY = rows
        var maxY = -1
        var redCount = 0
        var blackCount = 0
        for (i in interior.indices) {
            if (!interior[i]) continue
            val x = i % cols
            val y = i / cols
            // Interior vertices always have four in-grid neighbours; the solver relies on that.
            require(x in 1 until cols - 1 && y in 1 until rows - 1) { "Interior vertex on the grid border" }
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if ((x + y) % 2 == 0) redCount++ else blackCount++
        }
        val u = FloatArray(interior.size)
        if (maxX < 0) return u

        // Red-black ordering lets successive over-relaxation update in place.
        val red = IntArray(redCount)
        val black = IntArray(blackCount)
        var r = 0
        var b = 0
        for (i in interior.indices) {
            if (!interior[i]) continue
            if ((i % cols + i / cols) % 2 == 0) red[r++] = i else black[b++] = i
        }

        val span = max(maxX - minX, maxY - minY) + 2
        val omega = (2.0 / (1.0 + sin(PI / span))).toFloat()
        val maxIterations = 8 * span + 50
        for (iteration in 0 until maxIterations) {
            val change = max(relax(u, red, cols, omega), relax(u, black, cols, omega))
            if (change < TOLERANCE) break
        }
        for (i in u.indices) u[i] = sqrt(2f * max(0f, u[i]))
        return u
    }

    private fun relax(u: FloatArray, vertices: IntArray, cols: Int, omega: Float): Float {
        var maxChange = 0f
        for (i in vertices) {
            val target = (u[i - 1] + u[i + 1] + u[i - cols] + u[i + cols] + 1f) * 0.25f
            val delta = omega * (target - u[i])
            u[i] += delta
            val magnitude = abs(delta)
            if (magnitude > maxChange) maxChange = magnitude
        }
        return maxChange
    }
}
