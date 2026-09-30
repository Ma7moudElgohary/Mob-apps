package com.ma7moud.reality3d.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class InflationTest {

    @Test
    fun stripGetsACircularCrossSection() {
        val halfWidth = 10
        val cols = 2 * halfWidth + 1
        val rows = 401
        val interior = BooleanArray(cols * rows) { i ->
            val x = i % cols
            val y = i / cols
            x in 1 until cols - 1 && y in 1 until rows - 1
        }
        val heights = Inflation.heights(interior, cols, rows)
        val middle = rows / 2 * cols
        for (x in 0 until cols) {
            val expected = sqrt((x * (2 * halfWidth - x)).toFloat())
            assertEquals("x=$x", expected, heights[middle + x], 0.05f * halfWidth)
        }
    }

    @Test
    fun diskBulgesMostInTheMiddle() {
        val size = 71
        val radius = 30f
        val interior = BooleanArray(size * size) { hypot(it % size - 35f, it / size - 35f) < radius }
        val heights = Inflation.heights(interior, size, size)
        val center = heights[35 * size + 35]
        assertEquals(radius / sqrt(2f), center, 0.05f * radius)
        assertEquals(0f, heights[0], 0f)
        // Symmetric.
        assertEquals(heights[35 * size + 20], heights[35 * size + 50], 1e-2f)
        assertEquals(heights[20 * size + 35], heights[50 * size + 35], 1e-2f)
    }

    @Test
    fun emptyInteriorIsFlat() {
        val heights = Inflation.heights(BooleanArray(100), 10, 10)
        assertTrue(heights.all { it == 0f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun interiorOnTheBorderIsRejected() {
        Inflation.heights(BooleanArray(100) { it == 0 }, 10, 10)
    }
}
