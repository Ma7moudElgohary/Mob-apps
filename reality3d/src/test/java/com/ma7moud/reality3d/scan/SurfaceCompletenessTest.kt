package com.ma7moud.reality3d.scan

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceCompletenessTest {

    @Test
    fun emptyVolumeHasNoSurfaceCompletenessYet() {
        assertNull(SurfaceCompleteness.measure(TsdfVolume(SyntheticScan.box, resolution = 48)))
    }

    @Test
    fun completeRingsHaveMoreMeasuredSurfaceThanOneLowRing() {
        val full = SurfaceCompleteness.measure(
            SyntheticScan.fuse(SyntheticScan.ringPoses(), resolution = 48),
        )!!
        val partial = SurfaceCompleteness.measure(
            SyntheticScan.fuse(
                SyntheticScan.ringPoses(elevationsDeg = listOf(20f), perRing = 16, distance = 0.3f),
                resolution = 48,
            ),
        )!!

        assertTrue("full=$full partial=$partial", full > partial + 0.08f)
        assertTrue("full=$full", full > 0.45f)
        assertTrue("partial=$partial", partial < 0.85f)
    }

    @Test
    fun supportBaseDoesNotCountAsMissingSurface() {
        val withTable = SurfaceCompleteness.measure(
            SyntheticScan.fuse(SyntheticScan.ringPoses(), resolution = 48),
        )!!
        val floatingBox = ScanBox(
            centerX = SyntheticScan.box.centerX,
            bottomY = SyntheticScan.box.bottomY,
            centerZ = SyntheticScan.box.centerZ,
            size = SyntheticScan.box.size,
            floorY = null,
        )
        val withoutSupportExclusion = SurfaceCompleteness.measure(
            SyntheticScan.fuse(SyntheticScan.ringPoses(), box = floatingBox, resolution = 48),
        )!!

        assertTrue(
            "support exclusion should not make completeness worse: table=$withTable floating=$withoutSupportExclusion",
            withTable >= withoutSupportExclusion - 0.03f,
        )
    }
}
