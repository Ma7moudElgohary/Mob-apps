package com.ma7moud.reality3d.ui

import com.ma7moud.reality3d.mesh.Mesh3D
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerMathTest {

    /** A 1 × 1 × 1 cube centred on the origin, 12 triangles wound outwards. */
    private val cube: Mesh3D = run {
        val p = floatArrayOf(
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
            -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
        )
        val i = intArrayOf(
            4, 5, 6, 4, 6, 7, 1, 0, 3, 1, 3, 2, 0, 4, 7, 0, 7, 3,
            5, 1, 2, 5, 2, 6, 3, 7, 6, 3, 6, 2, 0, 1, 5, 0, 5, 4,
        )
        Mesh3D(p, FloatArray(p.size), null, i, solid = true, subjectIsolated = true)
    }

    private val identity = FloatArray(16).also { for (k in 0 until 4) it[k * 5] = 1f }

    @Test
    fun picksTheNearestFaceUnderTheFinger() {
        // With an identity matrix the ray runs from z = -1 to z = +1, so it meets the z = -0.5 face first.
        val hit = MeshPicker.pick(cube, identity, 0.2f, 0.1f)!!
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, -0.5f), hit, 1e-5f)
        assertNull(MeshPicker.pick(cube, identity, 0.9f, 0f))
        assertEquals(5f, MeshPicker.distance(floatArrayOf(0f, 0f, 0f), floatArrayOf(3f, 4f, 0f)), 1e-6f)
    }

    @Test
    fun picksThroughAScaledView() {
        // The model shown at half size, so the inverse doubles: NDC 0.1 is model x = 0.2, and NDC 0.4
        // (model x = 0.8) is beside the cube.
        val inverse = identity.copyOf().also {
            it[0] = 2f
            it[5] = 2f
        }
        assertEquals(0.2f, MeshPicker.pick(cube, inverse, 0.1f, 0f)!![0], 1e-5f)
        assertNull(MeshPicker.pick(cube, inverse, 0.4f, 0f))
    }

    @Test
    fun listsEveryEdgeOnce() {
        // 12 cube edges plus a diagonal on each of the 6 faces.
        assertEquals(18 * 2, uniqueEdges(cube.indices).size)
    }

    @Test
    fun frontViewKeepsThePhotoAspect() {
        val bounds = floatArrayOf(-1f, -0.5f, 0f, 1f, 0.5f, 0.3f)
        val rect = FrontView.rect(bounds, 400, 400)
        val width = rect[2] - rect[0]
        val height = rect[3] - rect[1]
        assertEquals(2f, width / height, 1e-4f)
        assertEquals(400f / 1.08f, width, 0.01f)
        assertEquals(200f, (rect[0] + rect[2]) / 2, 1e-3f)
        assertEquals(200f, (rect[1] + rect[3]) / 2, 1e-3f)
    }

    @Test
    fun uvBoundsCoverTheTexturedPart() {
        val mesh = Mesh3D(FloatArray(9), FloatArray(9), floatArrayOf(0.2f, 0.3f, 0.7f, 0.4f, 0.5f, 0.9f), intArrayOf(0, 1, 2), false, true)
        assertArrayEquals(floatArrayOf(0.2f, 0.3f, 0.7f, 0.9f), uvBounds(mesh), 1e-6f)
        assertNull(uvBounds(cube))
    }

    @Test
    fun lengthsReadNaturally() {
        assertEquals("4.0 mm", formatLength(0.004f))
        assertEquals("12.5 cm", formatLength(0.125f))
        assertEquals("1.50 m", formatLength(1.5f))
        assertEquals("20.0 × 14.0 × 9.0 cm", formatSize(floatArrayOf(0.2f, 0.14f, 0.09f)))
    }
}
