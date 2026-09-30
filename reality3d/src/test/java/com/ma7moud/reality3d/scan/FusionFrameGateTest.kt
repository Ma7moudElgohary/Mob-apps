package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionFrameGateTest {

    private val box = SyntheticScan.box

    private fun aimedPose(distance: Float = 0.45f): CameraPose =
        SyntheticScan.lookAt(
            box.centerX,
            box.centerY,
            box.centerZ + distance,
            box.centerX,
            box.centerY,
            box.centerZ,
        )

    @Test
    fun aimedFrameAtUsefulDistanceIsAccepted() {
        val frame = SyntheticScan.depthFrame(aimedPose())
        assertTrue(FusionFrameGate.accept(frame, box))
    }

    @Test
    fun cameraLookingAwayIsRejected() {
        val pose = SyntheticScan.lookAt(
            box.centerX,
            box.centerY,
            box.centerZ + 0.45f,
            box.centerX,
            box.centerY,
            box.centerZ + 1.0f,
        )
        val frame = SyntheticScan.depthFrame(pose)
        assertFalse(FusionFrameGate.accept(frame, box))
    }

    @Test
    fun implausibleScanDistancesAreRejected() {
        val tooClose = SyntheticScan.depthFrame(aimedPose(distance = 0.10f))
        val tooFar = SyntheticScan.depthFrame(aimedPose(distance = 1.30f))
        assertFalse(FusionFrameGate.accept(tooClose, box))
        assertFalse(FusionFrameGate.accept(tooFar, box))
    }

    @Test
    fun emptyDepthMapIsRejected() {
        val pose = aimedPose()
        val source = SyntheticScan.depthFrame(pose)
        val empty = DepthFrame(
            source.width,
            source.height,
            ShortArray(source.width * source.height),
            source.intrinsics,
            pose,
        )
        assertFalse(FusionFrameGate.accept(empty, box))
    }

    @Test
    fun rejectedFrameNeverChangesTsdfFrameCount() {
        val volume = TsdfVolume(box, resolution = 48)
        val good = SyntheticScan.depthFrame(aimedPose())
        val awayPose = SyntheticScan.lookAt(
            box.centerX,
            box.centerY,
            box.centerZ + 0.45f,
            box.centerX,
            box.centerY,
            box.centerZ + 1.0f,
        )
        val bad = SyntheticScan.depthFrame(awayPose)

        assertFalse(volume.integrate(bad))
        assertEquals(0, volume.frames)
        assertTrue(volume.integrate(good))
        assertEquals(1, volume.frames)
    }
}
