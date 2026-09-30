package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectDepthEvidenceTest {

    private val intrinsics = SyntheticScan.depthIntrinsics
    private val supportedBox = SyntheticScan.box
    private val pose = SyntheticScan.lookAt(
        supportedBox.centerX,
        0.24f,
        0.50f,
        supportedBox.centerX,
        supportedBox.centerY,
        supportedBox.centerZ,
    )

    /**
     * A synthetic depth map containing only the horizontal support plane. The ray parameter is also the
     * optical-axis depth because camera-space rays are (x, y, -1), matching ARCore depth semantics.
     */
    private fun supportPlaneFrame(floorY: Float = 0f): DepthFrame {
        val depth = ShortArray(intrinsics.width * intrinsics.height)
        val confidence = ByteArray(depth.size) { 255.toByte() }
        val m = pose.matrix
        for (y in 0 until intrinsics.height) {
            for (x in 0 until intrinsics.width) {
                val cameraX = (x - intrinsics.cx) / intrinsics.fx
                val cameraY = (intrinsics.cy - y) / intrinsics.fy
                val rayWorldY = m[1] * cameraX + m[5] * cameraY - m[9]
                if (rayWorldY >= -1e-6f) continue
                val opticalDepth = (floorY - m[13]) / rayWorldY
                if (opticalDepth > 0f && opticalDepth <= 4f) {
                    depth[y * intrinsics.width + x] = (opticalDepth * 1000f).toInt().toShort()
                }
            }
        }
        return DepthFrame(intrinsics.width, intrinsics.height, depth, intrinsics, pose, confidence)
    }

    @Test
    fun supportTableAloneCannotQualifySubjectDepth() {
        val tableOnly = supportPlaneFrame()
        assertFalse(FusionFrameGate.accept(tableOnly, supportedBox))
        assertEquals(0f, DepthQuality.measure(tableOnly, supportedBox)!!, 0f)
    }

    @Test
    fun supportTableIsStillAvailableToTsdfForFreeSpaceCarving() {
        val tableOnly = supportPlaneFrame()
        val volume = TsdfVolume(supportedBox, resolution = 32)
        val tableHits = volume.tableHits(tableOnly)!!
        assertTrue(tableHits.any { it })
        // The frame as a whole is rejected as subject evidence, so it is not fused as an object observation.
        assertFalse(volume.integrate(tableOnly))
        assertEquals(0, volume.frames)
    }

    @Test
    fun sameLowPlaneIsNotSpecialWhenNoSupportPlaneWasDetected() {
        val tableOnly = supportPlaneFrame()
        val floating = ScanBox(
            centerX = supportedBox.centerX,
            bottomY = supportedBox.floorY ?: 0f,
            centerZ = supportedBox.centerZ,
            size = supportedBox.size,
            floorY = null,
        )
        // Without a known support plane, geometry is judged only against the selected 3D scan volume.
        assertTrue(FusionFrameGate.accept(tableOnly, floating))
        assertTrue((DepthQuality.measure(tableOnly, floating) ?: 0f) > 0f)
    }
}
