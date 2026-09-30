package com.ma7moud.reality3d.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class DepthRefinerTest {

    private val w = 60
    private val h = 40
    private val gray = IntArray(w * h) { 0xFF808080.toInt() }

    private fun variance(values: FloatArray, indices: IntRange): Double {
        val slice = indices.map { values[it].toDouble() }
        val mean = slice.average()
        return slice.sumOf { (it - mean) * (it - mean) } / slice.size
    }

    @Test
    fun noiseOnASurfaceIsSmoothedAway() {
        val random = Random(4)
        // A gently sloping plane with noise.
        val values = FloatArray(w * h) { (it % w) * 0.01f + 2f + (random.nextGaussian() * 0.02).toFloat() }
        val depth = DepthMap(w, h, values)
        val refined = DepthRefiner.refine(depth, gray, null)
        val residualBefore = FloatArray(w * h) { values[it] - ((it % w) * 0.01f + 2f) }
        val residualAfter = FloatArray(w * h) { refined.values[it] - ((it % w) * 0.01f + 2f) }
        val inner = (5 * w + 5) until (35 * w - 5)
        assertTrue(variance(residualAfter, inner) < variance(residualBefore, inner) * 0.25)
    }

    @Test
    fun jumpsInDepthStaySharp() {
        // Left half far (1), right half near (3), same colour: the depth kernel keeps them apart.
        val values = FloatArray(w * h) { if (it % w < w / 2) 1f else 3f }
        val refined = DepthRefiner.refine(DepthMap(w, h, values), gray, null)
        for (y in 0 until h) {
            assertEquals(1f, refined[w / 2 - 1, y], 0.02f)
            assertEquals(3f, refined[w / 2, y], 0.02f)
        }
    }

    @Test
    fun subjectAndBackgroundAreNeverMixed() {
        // The subject (a centred square) and the background have nearly the same depth and colour; only
        // the mask tells them apart, and a smeared rim must not pull the subject towards the background.
        val subject = FloatArray(w * h) { i -> if (i % w in 20 until 40 && i / w in 10 until 30) 1f else 0f }
        val values = FloatArray(w * h) { i -> if (subject[i] > 0.5f) 2.00f else 1.96f }
        val refined = DepthRefiner.refine(DepthMap(w, h, values), gray, subject)
        for (i in values.indices) {
            val expected = if (subject[i] > 0.5f) 2.00f else 1.96f
            assertEquals(expected, refined.values[i], 1e-4f)
        }
    }

    @Test
    fun colourEdgesOnlyDampTheSmoothing() {
        // A printed stripe (different colour) on a flat surface with noise: the stripe must not appear as
        // a step, and the noise under it is still reduced.
        val random = Random(9)
        val guide = IntArray(w * h) { i -> if (i % w in 28 until 32) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        val values = FloatArray(w * h) { 2f + (random.nextGaussian() * 0.01).toFloat() }
        val refined = DepthRefiner.refine(DepthMap(w, h, values), guide, null)
        val stripe = (0 until w * h).filter { it % w in 28 until 32 }.map { refined.values[it] }.average()
        val around = (0 until w * h).filter { it % w in 20 until 26 }.map { refined.values[it] }.average()
        assertTrue(abs(stripe - around) < 0.01)
    }

    @Test
    fun regionIsKept() {
        val depth = DepthMap(w, h, FloatArray(w * h) { 1f }, 0.1f, 0.2f, 0.7f, 0.9f)
        val refined = DepthRefiner.refine(depth, gray, null)
        assertEquals(0.1f, refined.left, 0f)
        assertEquals(0.9f, refined.bottom, 0f)
    }
}
