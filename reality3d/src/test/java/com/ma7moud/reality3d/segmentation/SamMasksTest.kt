package com.ma7moud.reality3d.segmentation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SamMasksTest {

    private val size = Sam.MASK

    /** Logits of +8 inside [inside] and -8 outside, like a confident decoder mask. */
    private fun logits(inside: (x: Int, y: Int) -> Boolean) =
        FloatArray(size * size) { i -> if (inside(i % size, i / size)) 8f else -8f }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = logits { x, y -> x in x0 until x1 && y in y0 until y1 }

    @Test
    fun thePhotoBecomesNormalisedColourPlanes() {
        val n = Sam.INPUT * Sam.INPUT
        val argb = IntArray(n) { 0xFF000000.toInt() }
        argb[5] = 0xFFFF8000.toInt()
        val input = Sam.encoderInput(argb)
        assertEquals(3 * n, input.size)
        assertEquals((1f - 0.485f) / 0.229f, input[5], 1e-5f)
        assertEquals((128 / 255f - 0.456f) / 0.224f, input[n + 5], 1e-5f)
        assertEquals((0f - 0.406f) / 0.225f, input[2 * n + 5], 1e-5f)
        assertEquals((0f - 0.485f) / 0.229f, input[0], 1e-5f)
    }

    @Test
    fun aPointBecomesItsPositionalEncodingPlusThePaddingPoint() {
        val posmat = FloatArray(256).also {
            it[0] = 0.25f
            it[128] = -0.5f
            it[3] = 1.5f
        }
        val positive = FloatArray(256) { it * 0.01f }
        val padding = FloatArray(256) { -it.toFloat() }
        val bytes = ByteBuffer.allocate(768 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            (posmat + positive + padding).forEach { putFloat(it) }
        }.array()
        val constants = Sam.PromptConstants.read(bytes)
        assertArrayEquals(posmat, constants.posmat, 0f)

        val prompt = Sam.prompt(0.25f, 0.75f, constants)
        val cx = 2.0 * ((256 + 0.5) / 1024) - 1
        val cy = 2.0 * ((768 + 0.5) / 1024) - 1
        val c0 = 2 * PI * (cx * 0.25 + cy * -0.5)
        assertEquals(sin(c0).toFloat() + 0f, prompt[0], 1e-5f)
        assertEquals(cos(c0).toFloat() + positive[128], prompt[128], 1e-5f)
        val c3 = 2 * PI * (cx * 1.5)
        assertEquals(sin(c3).toFloat() + positive[3], prompt[3], 1e-5f)
        // No weights: sin 0 and cos 0.
        assertEquals(positive[7], prompt[7], 1e-6f)
        assertEquals(1f + positive[135], prompt[135], 1e-6f)
        assertArrayEquals(padding, prompt.copyOfRange(256, 512), 0f)
    }

    @Test
    fun theDecodersThreeMasksAreSplitWithTheirScores() {
        val masks = FloatArray(3 * size * size) { i -> if (i / (size * size) == 1) 3f else -3f }
        val candidates = Sam.candidates(masks, floatArrayOf(0.1f, 0.9f, 0.5f))
        assertEquals(3, candidates.size)
        assertEquals(0f, candidates[0].area, 0f)
        assertEquals(1f, candidates[1].area, 0f)
        assertEquals(0.9f, candidates[1].iou, 0f)
        assertEquals(1f, candidates[1].stability, 0f)
    }

    @Test
    fun masksSpanningThePhotoAreBackground() {
        // A band from the left edge to the right edge: a table or a wall.
        assertTrue(Sam.isBackground(Sam.Candidate(box(0, 150, size, 220), 1f).mask))
        assertTrue(Sam.isBackground(Sam.Candidate(box(100, 0, 140, size), 1f).mask))
        // An object in the middle, or cut off at the bottom, is not.
        assertFalse(Sam.isBackground(Sam.Candidate(box(80, 80, 170, 170), 1f).mask))
        assertFalse(Sam.isBackground(Sam.Candidate(box(60, 120, 200, size), 1f).mask))
    }

    @Test
    fun objectsAreWholeSeparateAndNotTheBackground() {
        val big = Sam.Candidate(box(20, 60, 120, 200), 0.95f)
        val part = Sam.Candidate(box(30, 70, 70, 110), 0.9f)
        val small = Sam.Candidate(box(160, 100, 220, 160), 0.8f)
        val table = Sam.Candidate(box(0, 200, size, size), 0.99f)
        val speck = Sam.Candidate(box(5, 5, 8, 8), 0.99f)
        val unsure = Sam.Candidate(box(150, 20, 200, 60), 0.4f)
        val plausible = listOf(part, table, small, speck, big, unsure).filter(Sam::plausible)
        assertEquals(listOf(part, small, big), plausible)
        val objects = Sam.objects(plausible)
        // Largest first; the part inside the big one is dropped.
        assertEquals(listOf(big, small), objects)
    }

    @Test
    fun theMainObjectFillsTheMiddle() {
        val corner = Sam.Candidate(box(0, 0, 110, 110), 0.9f)
        val middle = Sam.Candidate(box(100, 100, 160, 160), 0.9f)
        assertEquals(1, Sam.mainObject(listOf(corner, middle)))
        assertEquals(-1, Sam.mainObject(emptyList()))
    }

    @Test
    fun aTapGetsTheWholeObjectNotAPart() {
        val part = Sam.Candidate(box(100, 100, 120, 120), 0.95f)
        val whole = Sam.Candidate(box(80, 80, 170, 170), 0.8f)
        val background = Sam.Candidate(box(0, 0, size, size), 0.99f)
        assertSame(whole, Sam.forTap(listOf(part, whole, background)))
        assertNull(Sam.forTap(listOf(background)))
        // A larger mask that is a guess (its edge moves with the threshold) is passed over, whatever the decoder thinks of it.
        val guess = Sam.Candidate(FloatArray(size * size) { i -> if (i % size in 40 until 220 && i / size in 40 until 220) 0.5f else -0.5f }, 0.99f)
        assertTrue(guess.stability < 0.5f)
        assertSame(whole, Sam.forTap(listOf(guess, whole)))
    }

    @Test
    fun anObjectLandsOnThePhotoWithItsSmallHolesFilled() {
        // A ring: a square with a small hole (like the gap between laces) that should be filled.
        val ring = logits { x, y -> x in 64 until 128 && y in 32 until 96 && !(x in 94 until 98 && y in 62 until 66) }
        val subject = Sam.subject(Sam.Candidate(ring, 0.9f), width = 512, height = 384)
        // The mask covers x 64..128 of 256 (128..256 of 512) and y 32..96 of 256 (48..144 of 384), plus a margin.
        assertTrue(subject.left in 120..128)
        assertTrue(subject.top in 40..48)
        assertTrue(subject.left + subject.width in 256..264)
        assertTrue(subject.top + subject.height in 144..152)
        assertTrue(subject.at(150, 60) > 0.99f)
        assertTrue("the hole is filled", subject.at(96 * 2, 64 * 384 / 256) > 0.9f)
        assertTrue(subject.at(126, 96) < 0.01f)
        assertEquals(0f, subject.at(10, 10), 0f)
    }
}
