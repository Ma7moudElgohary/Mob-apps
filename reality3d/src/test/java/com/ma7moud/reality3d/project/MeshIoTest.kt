package com.ma7moud.reality3d.project

import com.ma7moud.reality3d.mesh.Mesh3D
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshIoTest {

    private val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val normals = FloatArray(12) { if (it % 3 == 2) 1f else 0f }
    private val indices = intArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2)

    @Test
    fun photoModelsKeepTheirTextureCoordinates() {
        val mesh = Mesh3D(positions, normals, FloatArray(8) { it / 8f }, indices, solid = true, subjectIsolated = false)
        val copy = MeshIo.read(MeshIo.write(mesh))
        assertArrayEquals(mesh.positions, copy.positions, 0f)
        assertArrayEquals(mesh.normals, copy.normals, 0f)
        assertArrayEquals(mesh.uvs, copy.uvs, 0f)
        assertArrayEquals(mesh.indices, copy.indices)
        assertNull(copy.colors)
        assertEquals(true, copy.solid)
        assertEquals(false, copy.subjectIsolated)
        assertEquals(false, copy.realScale)
    }

    @Test
    fun scansKeepTheirColoursAndRealScale() {
        val mesh = Mesh3D(positions, normals, null, indices, solid = true, subjectIsolated = true, colors = FloatArray(12) { 0.5f }, realScale = true)
        val copy = MeshIo.read(MeshIo.write(mesh))
        assertNull(copy.uvs)
        assertArrayEquals(mesh.colors, copy.colors, 0f)
        assertEquals(true, copy.realScale)
    }

    @Test
    fun damagedFilesAreRejected() {
        val bytes = MeshIo.write(Mesh3D(positions, normals, null, indices, solid = true, subjectIsolated = true))
        assertThrows(IllegalArgumentException::class.java) { MeshIo.read(ByteArray(8)) }
        // An index pointing past the vertices.
        bytes[bytes.size - 1] = 0x7F
        assertThrows(IllegalArgumentException::class.java) { MeshIo.read(bytes) }
    }
}
