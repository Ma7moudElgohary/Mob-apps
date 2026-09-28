package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.segmentation.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureBakerTest {

    private val w = 64
    private val h = 48
    private val red = 0xFFD02020.toInt()
    private val blue = 0xFF2030D0.toInt()

    // Subject: a square in the middle, red; background blue.
    private val inside = { x: Int, y: Int -> x in 20 until 44 && y in 12 until 36 }
    private val photo = IntArray(w * h) { if (inside(it % w, it / w)) red else blue }
    private val mask = SubjectMask(w, h, FloatArray(w * h) { if (inside(it % w, it / w)) 1f else 0f })

    @Test
    fun backgroundAroundTheSubjectTakesTheSubjectsColours() {
        val baked = TextureBaker.bake(photo, w, h, mask)
        for (y in 0 until h) for (x in 0 until w) {
            val pixel = baked[y * w + x]
            val alpha = pixel ushr 24
            if (inside(x, y)) {
                assertEquals(red, pixel)
            } else {
                assertEquals(0, alpha)
                // No trace of the blue background anywhere: it is all filled from the subject.
                assertEquals(0xD02020, pixel and 0xFFFFFF)
            }
        }
    }

    @Test
    fun twoColouredSubjectSpreadsEachColourToItsSide() {
        val twoTone = IntArray(w * h) { i ->
            val x = i % w
            when {
                !inside(x, i / w) -> 0xFF00FF00.toInt()
                x < 32 -> red
                else -> blue
            }
        }
        val baked = TextureBaker.bake(twoTone, w, h, mask)
        // Just outside the left edge the padding is red, just outside the right edge blue.
        assertEquals(0xD02020, baked[24 * w + 19] and 0xFFFFFF)
        assertEquals(0x2030D0, baked[24 * w + 44] and 0xFFFFFF)
        // Green background never survives.
        assertTrue(baked.none { (it and 0xFFFFFF) == 0x00FF00 })
    }

    @Test
    fun withoutAMaskThePhotoIsKeptOpaque() {
        val baked = TextureBaker.bake(photo, w, h, null)
        for (i in photo.indices) assertEquals(photo[i] or (0xFF shl 24), baked[i])
    }

    @Test
    fun cropKeepsTheSubjectAndRemapsTextureCoordinates() {
        val region = TextureBaker.subjectRegion(mask, margin = 0f)
        assertEquals(20f / w, region[0], 1e-6f)
        assertEquals(12f / h, region[1], 1e-6f)
        assertEquals(44f / w, region[2], 1e-6f)
        assertEquals(36f / h, region[3], 1e-6f)
        val mesh = Mesh3D(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            uvs = floatArrayOf(region[0], region[1], region[2], region[1], region[0], region[3]),
            indices = intArrayOf(0, 1, 2),
            solid = false,
            subjectIsolated = true,
        )
        val cropped = TextureBaker.cropUvs(mesh, region).uvs!!
        assertEquals(listOf(0f, 0f, 1f, 0f, 0f, 1f), cropped.map { (it * 1e5f).toInt() / 1e5f })
        assertEquals(listOf(0f, 0f, 1f, 1f), TextureBaker.subjectRegion(null).toList())
    }
}
