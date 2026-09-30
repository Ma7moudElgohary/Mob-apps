package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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

    /** Same projected box rectangle used by the gate, exposed here only to build adversarial depth maps. */
    private fun projectedRegion(frame: DepthFrame): IntArray {
        val projected = FloatArray(3)
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (corner in 0 until 8) {
            val x = if (corner and 1 == 0) box.minX else box.minX + box.size
            val y = if (corner and 2 == 0) box.bottomY else box.bottomY + box.size
            val z = if (corner and 4 == 0) box.minZ else box.minZ + box.size
            assertTrue(frame.pose.project(x, y, z, frame.intrinsics, projected))
            left = min(left, projected[0])
            top = min(top, projected[1])
            right = max(right, projected[0])
            bottom = max(bottom, projected[1])
        }
        return intArrayOf(
            floor(left.toDouble()).toInt().coerceIn(0, frame.width),
            floor(top.toDouble()).toInt().coerceIn(0, frame.height),
            ceil(right.toDouble()).toInt().coerceIn(0, frame.width),
            ceil(bottom.toDouble()).toInt().coerceIn(0, frame.height),
        )
    }

    private fun constantDepthInObjectRegion(source: DepthFrame, millimetres: Int): DepthFrame {
        val depth = ShortArray(source.depthMm.size)
        val confidence = ByteArray(source.depthMm.size)
        val region = projectedRegion(source)
        for (y in region[1] until region[3]) {
            for (x in region[0] until region[2]) {
                val index = y * source.width + x
                depth[index] = millimetres.toShort()
                confidence[index] = 220.toByte()
            }
        }
        return DepthFrame(source.width, source.height, depth, source.intrinsics, source.pose, confidence)
    }

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
    fun backgroundDepthCannotHideAnEmptyObjectRegion() {
        val source = SyntheticScan.depthFrame(aimedPose())
        // A deliberately misleading frame: valid-looking depth everywhere except where the selected
        // object box projects. The old whole-image gate accepted this because the background looked good.
        val depth = ShortArray(source.depthMm.size) { 1_000.toShort() }
        val region = projectedRegion(source)
        for (y in region[1] until region[3]) {
            for (x in region[0] until region[2]) depth[y * source.width + x] = 0
        }
        val frame = DepthFrame(source.width, source.height, depth, source.intrinsics, source.pose)
        assertFalse(FusionFrameGate.accept(frame, box))
    }

    @Test
    fun wallBehindObjectSilhouetteIsRejectedIn3d() {
        val source = SyntheticScan.depthFrame(aimedPose())
        // Every pixel in the correct 2D object region has strong depth, but at 1 m the points lie well
        // behind this 30 cm scan box. A 2D-only gate would accept this as a good object frame.
        val wall = constantDepthInObjectRegion(source, millimetres = 1_000)
        assertFalse(FusionFrameGate.accept(wall, box))
    }

    @Test
    fun foregroundDepthInFrontOfScanBoxIsRejectedIn3d() {
        val source = SyntheticScan.depthFrame(aimedPose())
        // Likewise, a near occluder over the object's silhouette must not be mistaken for object geometry.
        val occluder = constantDepthInObjectRegion(source, millimetres = 100)
        assertFalse(FusionFrameGate.accept(occluder, box))
    }

    @Test
    fun sparseDepthInsideObjectRegionIsStillAccepted() {
        val source = SyntheticScan.depthFrame(aimedPose())
        val region = projectedRegion(source)
        val depth = ShortArray(source.depthMm.size)
        val confidence = ByteArray(source.depthMm.size)
        val stepX = max(1, (region[2] - region[0]) / 20)
        val stepY = max(1, (region[3] - region[1]) / 20)
        var y = region[1] + stepY / 2
        while (y < region[3]) {
            var x = region[0] + stepX / 2
            while (x < region[2]) {
                val index = y * source.width + x
                depth[index] = source.depthMm[index]
                confidence[index] = 220.toByte()
                x += stepX
            }
            y += stepY
        }
        val sparse = DepthFrame(source.width, source.height, depth, source.intrinsics, source.pose, confidence)
        assertTrue(FusionFrameGate.accept(sparse, box))
    }

    @Test
    fun lowConfidenceObjectDepthIsRejected() {
        val source = SyntheticScan.depthFrame(aimedPose())
        val confidence = ByteArray(source.depthMm.size) { 20 }
        val frame = DepthFrame(source.width, source.height, source.depthMm.copyOf(), source.intrinsics, source.pose, confidence)
        assertFalse(FusionFrameGate.accept(frame, box))
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
