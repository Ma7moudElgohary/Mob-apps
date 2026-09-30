package com.ma7moud.reality3d.segmentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskEditTest {

    private val w = 80
    private val h = 60

    private fun square(x0: Int, y0: Int, size: Int) = FloatArray(size * size) { 1f }.let { Subject(x0, y0, size, size, it) }

    @Test
    fun selectingOneObjectLeavesTheOthersOut() {
        val laptop = square(5, 5, 30)
        val cup = square(50, 20, 12)
        val foreground = SubjectMask(w, h, FloatArray(w * h) { i -> maxOf(laptop.at(i % w, i / w), cup.at(i % w, i / w)) })
        val segmentation = Segmentation(w, h, foreground, listOf(laptop, cup))
        val onlyCup = segmentation.maskFor(setOf(1))!!
        assertEquals(0f, onlyCup[10, 10], 0f)
        assertEquals(1f, onlyCup[55, 25], 0f)
        assertTrue(segmentation.maskFor(emptySet()) === foreground)
        assertTrue(segmentation.maskFor(setOf(0, 1)) === foreground)
        assertEquals(1, segmentation.subjectAt(55f / w, 25f / h))
        assertEquals(0, segmentation.subjectAt(10f / w, 10f / h))
        assertNull(segmentation.subjectAt(45f / w, 55f / h))
    }

    @Test
    fun brushAddsErasesAndUndoes() {
        val edit = MaskEdit(w, h, FloatArray(w * h) { 0.5f })
        edit.beginStroke()
        val dirty = edit.paint(20f, 30f, 60f, 30f, 6f, erase = false)!!
        assertTrue(dirty[0] <= 14 && dirty[2] >= 66)
        assertEquals(1f, edit.raw(30 * w + 40), 1e-6f)
        assertEquals(0f, edit.raw(5 * w + 5), 1e-6f)
        edit.beginStroke()
        edit.paint(40f, 30f, 40f, 30f, 4f, erase = true)
        assertEquals(0f, edit.raw(30 * w + 40), 1e-6f)
        assertEquals(1f, edit.raw(30 * w + 25), 1e-6f)
        assertTrue(edit.undo())
        assertEquals(1f, edit.raw(30 * w + 40), 1e-6f)
        assertTrue(edit.undo())
        assertFalse(edit.hasStrokes)
        assertFalse(edit.undo())
    }

    @Test
    fun copiesAreIndependent() {
        val edit = MaskEdit(w, h, FloatArray(w * h))
        edit.feather = 2
        edit.beginStroke()
        edit.paint(10f, 10f, 10f, 10f, 3f, erase = false)
        val copy = edit.copy()
        edit.paint(40f, 40f, 40f, 40f, 3f, erase = false)
        assertEquals(1f, copy.raw(10 * w + 10), 1e-6f)
        assertEquals(0f, copy.raw(40 * w + 40), 1e-6f)
        assertEquals(2, copy.feather)
        assertFalse(copy.canUndo)
    }

    @Test
    fun strokesSurviveAChangeOfSelection() {
        val edit = MaskEdit(w, h, FloatArray(w * h))
        edit.beginStroke()
        edit.paint(10f, 10f, 10f, 10f, 3f, erase = true)
        edit.setBase(SubjectMask(w, h, FloatArray(w * h) { 1f }))
        assertEquals(0f, edit.raw(10 * w + 10), 1e-6f)
        assertEquals(1f, edit.raw(40 * w + 40), 1e-6f)
    }

    @Test
    fun refiningSnapsTheOutlineToThePhotoAndCleansUp() {
        // The photo: a bright rectangle from x = 30 to 60. The mask: a soft edge crossing 0.5 two pixels
        // early, at x = 28, a speck far away and a pinhole inside.
        val guide = FloatArray(w * h) { i -> if (i % w in 30 until 60 && i / w in 10 until 50) 0.9f else 0.1f }
        val base = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x in 70 until 72 && y in 5 until 7 -> 1f
                x in 45 until 47 && y in 29 until 31 -> 0f
                y !in 10 until 50 || x >= 60 -> 0f
                else -> ((x - 24) / 8f).coerceIn(0f, 1f)
            }
        }
        val edit = MaskEdit(w, h, guide)
        edit.setBase(SubjectMask(w, h, base))
        edit.refineEdges = true
        val result = edit.result()
        fun crossing(y: Int): Int = (0 until w).first { result[y * w + it] >= 0.5f }
        // The left edge moved onto the photo's edge at x = 30.
        val edge = crossing(30)
        assertTrue("edge at $edge", edge in 29..31)
        assertTrue(result[6 * w + 70] < 0.5f)
        assertTrue(result[30 * w + 45] >= 0.5f)
    }

    @Test
    fun featherSoftensTheOutline() {
        val edit = MaskEdit(w, h, FloatArray(w * h))
        edit.setBase(SubjectMask(w, h, FloatArray(w * h) { i -> if (i % w < 40) 1f else 0f }))
        edit.feather = 3
        val result = edit.result()
        assertEquals(1f, result[30 * w + 10], 1e-5f)
        assertEquals(0f, result[30 * w + 70], 1e-5f)
        assertTrue(result[30 * w + 39] in 0.51f..0.99f)
        assertTrue(result[30 * w + 40] in 0.01f..0.49f)
    }

    @Test
    fun editingSizeIsCapped() {
        assertEquals(1024 to 768, MaskEdit.sizeFor(1600, 1200))
        assertEquals(400 to 300, MaskEdit.sizeFor(400, 300))
    }
}
