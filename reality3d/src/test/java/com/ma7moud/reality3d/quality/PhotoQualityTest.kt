package com.ma7moud.reality3d.quality

import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.segmentation.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PhotoQualityTest {

    private val w = 320
    private val h = 240

    /** A detailed (checkered) disc on a plain grey background, and its mask. */
    private fun scene(cx: Float = 160f, cy: Float = 120f, r: Float = 70f, light: Float = 1f): Pair<IntArray, SubjectMask> {
        val pixels = IntArray(w * h)
        val mask = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inside = hypot(x - cx, y - cy) < r
                val value = if (inside) (if ((x / 4 + y / 4) % 2 == 0) 230 else 40) else 128
                val v = (value * light).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                mask[y * w + x] = if (inside) 1f else 0f
            }
        }
        return pixels to SubjectMask(w, h, mask)
    }

    private fun blur(pixels: IntArray, r: Int): IntArray = IntArray(pixels.size) { i ->
        val x = i % w
        val y = i / w
        var sum = 0
        var n = 0
        for (dy in -r..r) for (dx in -r..r) {
            sum += pixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)] and 0xFF
            n++
        }
        val v = sum / n
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun aSharpWellFramedPhotoScoresHigh() {
        val (pixels, mask) = scene()
        val report = PhotoQuality.assess(pixels, w, h, mask)
        assertTrue(report.issues.toString(), report.issues.isEmpty())
        assertTrue("score ${report.score}", report.score >= 90)
        assertEquals("Excellent", report.grade)
    }

    @Test
    fun blurAndDarknessPullTheScoreDown() {
        val (pixels, mask) = scene()
        val blurry = PhotoQuality.assess(blur(pixels, 4), w, h, mask)
        assertTrue("score ${blurry.score}", blurry.score < 60)
        assertTrue(blurry.issues.any { it.startsWith("The photo looks blurry") })
        val (dark, darkMask) = scene(light = 0.2f)
        val dim = PhotoQuality.assess(dark, w, h, darkMask)
        assertTrue(dim.issues.any { it.startsWith("The photo is dark") })
        assertTrue("score ${dim.score}", dim.score < 70)
    }

    @Test
    fun framingProblemsAreNamed() {
        val (small, smallMask) = scene(r = 12f)
        assertTrue(PhotoQuality.assess(small, w, h, smallMask).issues.any { it.startsWith("The object is small") })
        val (cut, cutMask) = scene(cy = 225f, r = 60f)
        assertTrue(PhotoQuality.assess(cut, w, h, cutMask).issues.any { it.startsWith("Part of the object runs off") })
        val (pixels, _) = scene()
        val unseparated = PhotoQuality.assess(pixels, w, h, null)
        assertTrue(unseparated.issues.any { it.startsWith("The object couldn't be separated") })
        assertTrue(unseparated.score < 80)
    }

    @Test
    fun shinyAndSeeThroughObjectsCostPoints() {
        val (pixels, mask) = scene()
        val plain = PhotoQuality.assess(pixels, w, h, mask).score
        val glass = PhotoQuality.assess(pixels, w, h, mask, ObjectInsight(reflective = true, transparent = true))
        assertEquals(plain - 20, glass.score)
        assertEquals(2, glass.issues.size)
    }
}
