package com.ma7moud.reality3d.scan

import org.junit.Assert.assertTrue
import org.junit.Test

class SparseTsdfVolumeTest {
    @Test
    fun integratesSurfaceBand() {
        val volume = SparseTsdfVolume(voxelSizeMeters = 0.02f, truncationMeters = 0.06f)
        volume.integrateSurfacePoint(
            camera = Vector3(0f, 0f, 0f),
            surface = Vector3(0f, 0f, -1f),
            confidence = 1f,
            color = 0xFFFF0000.toInt(),
        )
        assertTrue(volume.size >= 4)
        val values = volume.snapshot().values
        assertTrue(values.any { it.tsdf > 0f })
        assertTrue(values.any { it.tsdf < 0f })
    }
}
