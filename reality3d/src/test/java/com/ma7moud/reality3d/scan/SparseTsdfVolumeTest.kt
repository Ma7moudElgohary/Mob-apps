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

    @Test
    fun saturatedWeightsRemainStableAndBounded() {
        val volume = SparseTsdfVolume(voxelSizeMeters = 0.02f, truncationMeters = 0.06f)
        repeat(160) {
            volume.integrateSurfacePoint(
                camera = Vector3(0f, 0f, 0f),
                surface = Vector3(0f, 0f, -1f),
                confidence = 1f,
                color = 0xFFFF0000.toInt(),
            )
        }

        val values = volume.snapshot().values
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it.weight in 0f..64f })
        assertTrue(values.all { it.tsdf in -1f..1f })
        assertTrue(values.all { it.r in 0f..1f && it.g in 0f..1f && it.b in 0f..1f })
    }
}
