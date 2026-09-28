package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoachTest {

    private fun input(
        trackingProblem: String? = null,
        anchorLost: Boolean = false,
        boxInView: Boolean = true,
        distance: Float? = 0.6f,
        speed: Float = 0.1f,
        turnRate: Float = 10f,
        depthQuality: Float? = 0.8f,
        coverage: BooleanArray = BooleanArray(CoverageTracker.CELLS),
        azimuth: Float? = 15f,
        ring: Int? = 0,
    ) = CoachInput(trackingProblem, anchorLost, boxInView, distance, 0.4f, speed, turnRate, depthQuality, coverage, azimuth, ring)

    private fun covered(vararg cells: Int) = BooleanArray(CoverageTracker.CELLS).also { c -> cells.forEach { c[it] = true } }

    @Test
    fun theMostPressingProblemComesFirst() {
        assertEquals("Too dark", ScanCoach.advise(input(trackingProblem = "Too dark", speed = 9f)).text)
        assertEquals(CoachTip.Kind.WARNING, ScanCoach.advise(input(anchorLost = true)).kind)
        assertEquals("Point the camera at the object.", ScanCoach.advise(input(boxInView = false, speed = 9f)).text)
        assertEquals("Too fast. Move the phone slowly.", ScanCoach.advise(input(speed = 0.6f)).text)
        assertEquals("Too fast. Move the phone slowly.", ScanCoach.advise(input(turnRate = 80f)).text)
        assertEquals("Too close. Step back a little.", ScanCoach.advise(input(distance = 0.2f)).text)
        assertEquals("Come closer to the object.", ScanCoach.advise(input(distance = 2f)).text)
        // Before the first frame the distance isn't known: no warning about it.
        assertEquals(CoachTip.Kind.INFO, ScanCoach.advise(input(distance = null)).kind)
        assertTrue(ScanCoach.advise(input(depthQuality = 0.1f)).text.startsWith("Low detail"))
    }

    @Test
    fun steersTowardsTheNearestGap() {
        // At segment 0 of the low ring, already photographed; segment 1 (to the right) is missing.
        val tip = ScanCoach.advise(input(coverage = covered(0, 11, 10), azimuth = 15f, ring = 0))
        assertEquals("Move right, around the object.", tip.text)
        // Segments 0 and 1 done, 11 missing: go left.
        assertEquals("Move left, around the object.", ScanCoach.advise(input(coverage = covered(0, 1, 2), azimuth = 15f, ring = 0)).text)
        // Standing where no photo has been taken yet.
        assertEquals("Good. Hold steady for a moment.", ScanCoach.advise(input(coverage = covered(), azimuth = 15f, ring = 0)).text)
    }

    @Test
    fun movesUpTheRingsThenAsksForTheTop() {
        val lowDone = covered(*IntArray(10) { it })
        assertEquals("Raise the phone and look down at the object.", ScanCoach.advise(input(coverage = lowDone, ring = 0)).text)
        // Every ring three-quarters done (73% in all) but nothing from above.
        val ringsDone = covered(*IntArray(3 * CoverageTracker.SEGMENTS) { it }.filter { it % CoverageTracker.SEGMENTS < 9 }.toIntArray())
        assertTrue(ScanCoach.advise(input(coverage = ringsDone, ring = 1)).text.startsWith("Top view missing"))
        // Complete without the top: done, with a nudge towards it.
        val ringsFull = covered(*IntArray(3 * CoverageTracker.SEGMENTS) { it })
        val almost = ScanCoach.advise(input(coverage = ringsFull, ring = 1))
        assertEquals(CoachTip.Kind.DONE, almost.kind)
        assertTrue(almost.text.contains("straight above"))
        val complete = covered(*IntArray(CoverageTracker.CELLS) { it })
        val done = ScanCoach.advise(input(coverage = complete))
        assertEquals(CoachTip.Kind.DONE, done.kind)
        assertEquals("Scan complete. Tap Build model.", done.text)
    }

    @Test
    fun qualityRewardsFullCoverageAndGoodDepth() {
        val full = BooleanArray(CoverageTracker.CELLS) { true }
        val great = ScanQuality.assess(full, photos = 40, depthFrames = 70, depthQuality = 0.9f)
        assertTrue(great.score >= 95)
        assertEquals("Excellent", great.grade)
        assertTrue(great.issues.isEmpty())

        val half = BooleanArray(CoverageTracker.CELLS) { it < CoverageTracker.SEGMENTS + 4 }
        val weak = ScanQuality.assess(half, photos = 10, depthFrames = 20, depthQuality = 0.3f)
        assertTrue(weak.score < 50)
        assertEquals("Poor", weak.grade)
        assertTrue(weak.issues.any { it.startsWith("Some 45° views are missing") })
        assertTrue(weak.issues.any { it.startsWith("No view from straight above") })
        assertTrue(weak.issues.any { it.startsWith("Only 10 photos") })
        assertTrue(weak.issues.any { it.startsWith("The depth was weak") })
    }

    @Test
    fun depthQualityCountsConfidentPixelsOnTheObject() {
        val pose = SyntheticScan.lookAt(0f, 0.2f, 0.6f, 0f, 0.1f, 0f)
        val intrinsics = Intrinsics(80f, 80f, 40f, 30f, 80, 60)
        val depth = ShortArray(80 * 60) { 500 }
        val confident = ByteArray(80 * 60) { if (it % 2 == 0) 255.toByte() else 10 }
        val box = ScanBox(0f, 0f, 0f, 0.2f, 0f)
        val quality = DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, pose, confident), box)!!
        assertEquals(0.5f, quality, 0.05f)
        assertNull(DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, pose), box))
        // Behind the camera: no answer.
        val away = SyntheticScan.lookAt(0f, 0.2f, 0.6f, 0f, 0.2f, 2f)
        assertNull(DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, away, confident), box))
    }

    @Test
    fun lowConfidenceRawDepthCountsLessAndGapsArentEdges() {
        val pose = SyntheticScan.lookAt(0f, 0.1f, 0.5f, 0f, 0.1f, 0f)
        val intrinsics = Intrinsics(80f, 80f, 40f, 30f, 80, 60)
        // Sparse raw depth: every other pixel missing, the rest flat at 50 cm.
        val depth = ShortArray(80 * 60) { if (it % 2 == 0) 500 else 0 }
        val volume = TsdfVolume(ScanBox(0f, 0f, 0f, 0.2f, null), resolution = 16)
        val sure = volume.pixelWeights(DepthFrame(80, 60, depth, intrinsics, pose, ByteArray(80 * 60) { 255.toByte() }))
        val unsure = volume.pixelWeights(DepthFrame(80, 60, depth, intrinsics, pose, ByteArray(80 * 60) { 100 }))
        val tooUnsure = volume.pixelWeights(DepthFrame(80, 60, depth, intrinsics, pose, ByteArray(80 * 60) { 20 }))
        val middle = 30 * 80 + 40
        assertEquals(1f, sure[middle], 1e-3f)
        assertEquals(100f / 255f, unsure[middle], 1e-3f)
        assertEquals(0f, tooUnsure[middle], 0f)
        // Smoothed depth with the same gaps: every pixel next to a gap is treated as an edge.
        assertEquals(0f, volume.pixelWeights(DepthFrame(80, 60, depth, intrinsics, pose))[middle], 0f)
    }
}
