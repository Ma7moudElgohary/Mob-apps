package com.ma7moud.reality3d.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class DepthInputTest {

    private val w = 686
    private val h = 518

    @Test
    fun wholeLandscapePhotoFillsTheInputWithoutStretching() {
        val plan = DepthInputPlan.create(1600, 1200, null, w, h)
        assertEquals(0, plan.cropLeft)
        assertEquals(0, plan.cropTop)
        assertEquals(1600, plan.cropRight)
        assertEquals(1200, plan.cropBottom)
        // 4:3 is a little wider than 686 × 518: the full width is used and the proportions are kept.
        assertEquals(w, plan.contentWidth)
        assertEquals(1600f / 1200f, plan.contentWidth.toFloat() / plan.contentHeight, 0.01f)
    }

    @Test
    fun portraitPhotoKeepsItsProportionsInsteadOfBeingSquashed() {
        val plan = DepthInputPlan.create(1200, 1600, null, w, h)
        assertEquals(h, plan.contentHeight)
        assertEquals(1200f / 1600f, plan.contentWidth.toFloat() / plan.contentHeight, 0.01f)
        assertEquals((w - plan.contentWidth) / 2, plan.contentX)
    }

    @Test
    fun smallSubjectIsCroppedAndMagnified() {
        // A subject in the middle tenth of a large photo.
        val plan = DepthInputPlan.create(4000, 3000, floatArrayOf(0.45f, 0.45f, 0.55f, 0.55f), w, h)
        assertTrue(plan.cropWidth < 1500)
        val scale = plan.contentWidth.toFloat() / plan.cropWidth
        assertTrue("scale $scale", scale > 0.45f)
        // The crop keeps the subject and has the input's shape.
        assertTrue(plan.cropLeft <= 1800 && plan.cropRight >= 2200 && plan.cropTop <= 1350 && plan.cropBottom >= 1650)
        assertEquals(w.toFloat() / h, plan.cropWidth.toFloat() / plan.cropHeight, 0.02f)
    }

    @Test
    fun cropNearTheEdgeSlidesInsideThePhoto() {
        val plan = DepthInputPlan.create(1000, 800, floatArrayOf(0.9f, 0f, 1f, 0.3f), w, h)
        assertTrue(plan.cropLeft >= 0 && plan.cropTop >= 0)
        assertTrue(plan.cropRight <= 1000 && plan.cropBottom <= 800)
        assertTrue(plan.cropRight == 1000)
    }

    @Test
    fun packRepeatsEdgesAndNormalises() {
        val plan = DepthInputPlan(0, 0, 2, 2, 2, 1, 2, 2)
        val content = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt())
        val tensor = DepthTensors.pack(content, plan, 6, 4)
        val plane = 24
        // Top-left of the input repeats the content's top-left (white).
        assertEquals((1f - 0.485f) / 0.229f, tensor[0], 1e-5f)
        assertEquals((1f - 0.406f) / 0.225f, tensor[2 * plane], 1e-5f)
        // Bottom-right repeats the content's bottom-right (red): R high, G and B zero.
        val last = plane - 1
        assertEquals((1f - 0.485f) / 0.229f, tensor[last], 1e-5f)
        assertEquals((0f - 0.456f) / 0.224f, tensor[plane + last], 1e-5f)
    }

    @Test
    fun unpackPlacesTheContentOverTheCrop() {
        val plan = DepthInputPlan(100, 50, 300, 250, 1, 0, 3, 2)
        val output = FloatArray(5 * 2) { it.toFloat() }
        output[2] = Float.NaN
        val map = DepthTensors.unpack(output, plan, 5, 400, 500)
        assertEquals(3, map.width)
        assertEquals(2, map.height)
        assertEquals(0.25f, map.left, 1e-6f)
        assertEquals(0.1f, map.top, 1e-6f)
        assertEquals(0.75f, map.right, 1e-6f)
        assertEquals(0.5f, map.bottom, 1e-6f)
        assertTrue(map.values.all { it.isFinite() })
        assertEquals(1f, map[0, 0], 1e-6f)
        assertEquals(8f, map[2, 1], 1e-6f)
        // Photo coordinates map into the crop; outside it the edge repeats.
        assertEquals(map[0, 0], map.sample(0.25f, 0.1f), 1e-6f)
        assertEquals(map[0, 0], map.sample(0f, 0f), 1e-6f)
        // Three depth pixels over half the photo: six across the whole photo.
        assertEquals(6f, map.photoWidth, 1e-4f)
    }

    @Test
    fun parityCheckAcceptsSmallNoiseAndRejectsBrokenMaps() {
        val random = Random(3)
        val reference = FloatArray(40_000) { i -> (i % 200) / 200f + (i / 200) / 400f }
        val sameUpToScale = FloatArray(reference.size) { reference[it] * 3.1f + 0.7f + (random.nextGaussian() * 0.004).toFloat() }
        assertTrue(DepthTensors.agree(reference, sameUpToScale))
        val broken = FloatArray(reference.size) { if (it % 7 == 0) 5f else reference[it] }
        assertFalse(DepthTensors.agree(reference, broken))
        val flat = FloatArray(reference.size) { 1f }
        assertFalse(DepthTensors.agree(reference, flat))
        val withNan = reference.copyOf().also { it[5] = Float.NaN }
        assertFalse(DepthTensors.agree(reference, withNan))
        assertTrue(abs(reference[1] - reference[0]) > 0f)
    }
}
