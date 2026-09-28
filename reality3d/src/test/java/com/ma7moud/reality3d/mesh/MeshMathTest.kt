package com.ma7moud.reality3d.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshMathTest {
    @Test
    fun scaleToWidthMakesMetricMeasurements() {
        val mesh = DepthMesh(
            positions = floatArrayOf(0f,0f,0f, 2f,0f,0f, 0f,1f,0f),
            texCoords = FloatArray(6),
            indices = intArrayOf(0,1,2),
        )
        val metric = MeshMath.scaleToWidth(mesh, 1.0f)
        assertEquals(1f, MeshMath.bounds(metric)[3] - MeshMath.bounds(metric)[0], 1e-5f)
        val distance = MeshMath.distanceMeters(metric, 0, 1)
        assertNotNull(distance)
        assertEquals(1f, distance!!, 1e-5f)
    }

    @Test
    fun normalsAreNormalized() {
        val mesh = DepthMesh(
            positions = floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f),
            texCoords = FloatArray(6),
            indices = intArrayOf(0,1,2),
        )
        val result = MeshMath.recalculateNormals(mesh)
        assertEquals(1f, result.normals[2], 1e-5f)
        assertEquals(1f, result.normals[5], 1e-5f)
        assertEquals(1f, result.normals[8], 1e-5f)
    }
}
