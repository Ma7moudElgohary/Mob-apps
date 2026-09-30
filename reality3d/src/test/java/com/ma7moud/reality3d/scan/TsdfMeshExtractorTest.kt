package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsdfMeshExtractorTest {
    @Test
    fun reusesVerticesAcrossTetrahedraForPlanarCrossing() {
        val volume = SparseTsdfVolume(voxelSizeMeters = 1f, truncationMeters = 1f)
        val snapshot = buildMap {
            for (x in 0..1) {
                for (y in 0..1) {
                    for (z in 0..1) {
                        put(
                            SparseTsdfVolume.Key(x, y, z),
                            SparseTsdfVolume.Voxel(
                                tsdf = if (x == 0) -0.5f else 0.5f,
                                weight = 1f,
                                r = x.toFloat(),
                                g = y.toFloat(),
                                b = z.toFloat(),
                            ),
                        )
                    }
                }
            }
        }
        volume.load(snapshot)

        val mesh = TsdfMeshExtractor.extract(volume, minWeight = 0.1f)
        val vertexCount = mesh.vertices.size / 3

        assertEquals(8, mesh.indices.size / 3)
        assertEquals(9, vertexCount)
        assertTrue(mesh.indices.all { it in 0 until vertexCount })
        assertTrue(vertexCount < mesh.indices.size)

        for (i in 0 until vertexCount) {
            assertEquals(0.5f, mesh.vertices[i * 3], 1e-5f)
        }
    }
}
