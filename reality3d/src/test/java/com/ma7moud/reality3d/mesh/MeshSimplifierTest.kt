package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.scan.MarchingTetrahedra
import com.ma7moud.reality3d.scan.SyntheticScan.openEdges
import com.ma7moud.reality3d.scan.SyntheticScan.signedVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class MeshSimplifierTest {

    private fun sphere(radius: Float = 0.12f): Pair<FloatArray, IntArray> {
        val n = 48
        val spacing = 0.008f
        val field = FloatArray(n * n * n) { index ->
            val x = index % n * spacing - 0.19f
            val y = index / n % n * spacing - 0.19f
            val z = index / (n * n) * spacing - 0.19f
            sqrt(x * x + y * y + z * z) - radius
        }
        val mesh = MarchingTetrahedra.extract(field, n, n, n, -0.19f, -0.19f, -0.19f, spacing)
        return mesh.positions to mesh.indices
    }

    @Test
    fun closedSphereStaysClosedAndRound() {
        val (positions, indices) = sphere()
        val before = indices.size / 3
        val start = System.nanoTime()
        val result = MeshSimplifier.simplify(positions, indices, 1500)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("SIMPLIFY sphere $before -> ${result.triangleCount} in $ms ms")
        assertTrue("got ${result.triangleCount}", result.triangleCount <= 1500)
        assertTrue(result.triangleCount > 1000)
        assertEquals(0, openEdges(result.indices))
        val expected = (4.0 / 3.0 * PI * 0.12 * 0.12 * 0.12).toFloat()
        assertEquals(expected, signedVolume(result.positions, result.indices), expected * 0.04f)
        for (v in 0 until result.positions.size / 3) {
            val r = sqrt(result.positions[v * 3] * result.positions[v * 3] + result.positions[v * 3 + 1] * result.positions[v * 3 + 1] + result.positions[v * 3 + 2] * result.positions[v * 3 + 2])
            assertEquals(0.12f, r, 0.006f)
        }
    }

    @Test
    fun openSurfaceKeepsItsOutlineAndTextureCoordinates() {
        // A 2 × 2 square with a bump in the middle, UVs following x and y.
        val side = 41
        val positions = FloatArray(side * side * 3)
        val uvs = FloatArray(side * side * 2)
        for (j in 0 until side) for (i in 0 until side) {
            val v = j * side + i
            val x = i * 2f / (side - 1) - 1f
            val y = j * 2f / (side - 1) - 1f
            positions[v * 3] = x
            positions[v * 3 + 1] = y
            positions[v * 3 + 2] = 0.4f * exp(-(x * x + y * y) * 4f)
            uvs[v * 2] = (x + 1) / 2
            uvs[v * 2 + 1] = (y + 1) / 2
        }
        val indices = ArrayList<Int>()
        for (j in 0 until side - 1) for (i in 0 until side - 1) {
            val a = j * side + i
            indices += listOf(a, a + 1, a + side + 1, a, a + side + 1, a + side)
        }
        val result = MeshSimplifier.simplify(positions, indices.toIntArray(), 400, uvs, 2)
        assertTrue(result.triangleCount <= 400)
        val out = result.positions
        val attributes = result.attributes!!
        var corners = 0
        for (v in 0 until out.size / 3) {
            val x = out[v * 3]
            val y = out[v * 3 + 1]
            // Nothing moves outside the square, and the texture coordinates still follow the position.
            assertTrue(abs(x) <= 1.0001f && abs(y) <= 1.0001f)
            assertEquals((x + 1) / 2, attributes[v * 2], 0.02f)
            assertEquals((y + 1) / 2, attributes[v * 2 + 1], 0.02f)
            if (abs(abs(x) - 1f) < 1e-4f && abs(abs(y) - 1f) < 1e-4f) corners++
        }
        assertEquals(4, corners)
        // The outline is the same square: projected area stays 4.
        var area = 0f
        for (t in result.indices.indices step 3) {
            val a = result.indices[t]
            val b = result.indices[t + 1]
            val c = result.indices[t + 2]
            area += ((out[b * 3] - out[a * 3]) * (out[c * 3 + 1] - out[a * 3 + 1]) - (out[c * 3] - out[a * 3]) * (out[b * 3 + 1] - out[a * 3 + 1])) / 2
        }
        assertEquals(4f, area, 0.02f)
    }

    @Test
    fun meshWrapperKeepsFlagsAndColours() {
        val (positions, indices) = sphere()
        val colors = FloatArray(positions.size) { 0.5f }
        val mesh = Mesh3D(positions, MeshBuilder.vertexNormals(positions, indices), null, indices, solid = true, subjectIsolated = true, colors = colors, realScale = true)
        val simple = MeshSimplifier.simplify(mesh, 800)
        assertTrue(simple.triangleCount <= 800)
        assertTrue(simple.solid && simple.realScale && simple.uvs == null)
        assertEquals(simple.vertexCount * 3, simple.colors!!.size)
        assertEquals(simple.vertexCount * 3, simple.normals.size)
        assertTrue(simple.colors!!.all { abs(it - 0.5f) < 1e-5f })
        // Asking for more triangles than there are returns the mesh unchanged.
        assertTrue(MeshSimplifier.simplify(mesh, 1_000_000) === mesh)
    }
}
