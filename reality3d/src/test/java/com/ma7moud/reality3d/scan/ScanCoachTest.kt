package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        surfaceCompleteness: Float? = 0.8f,
        coverage: BooleanArray = BooleanArray(CoverageTracker.CELLS),
        azimuth: Float? = 15f,
        ring: Int? = 0,
    ) = CoachInput(
        trackingProblem,
        anchorLost,
        boxInView,
        distance,
        0.4f,
        speed,
        turnRate,
        depthQuality,
        surfaceCompleteness,
        coverage,
        azimuth,
        ring,
    )

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
        // Both main laps three-quarters done: enough to build, with the high lap and the top on offer.
        val twoLaps = covered(*IntArray(2 * CoverageTracker.SEGMENTS) { it }.filter { it % CoverageTracker.SEGMENTS < 9 }.toIntArray())
        val enough = ScanCoach.advise(input(coverage = twoLaps, ring = 1))
        assertEquals(CoachTip.Kind.DONE, enough.kind)
        assertTrue(enough.text, enough.text.startsWith("That's enough. Tap Build model"))
        assertTrue(enough.text.contains("phone higher"))
        // Only the first lap and part of the second: the second is still to do.
        val oneAndABit = covered(*IntArray(CoverageTracker.SEGMENTS + 4) { it })
        assertFalse(ScanCoach.advise(input(coverage = oneAndABit, ring = 1)).kind == CoachTip.Kind.DONE)
        // The top is only asked for once the high ring is done too, and it can't hold a scan back from building.
        val ringsDone = covered(*IntArray(3 * CoverageTracker.SEGMENTS) { it }.filter { it % CoverageTracker.SEGMENTS < 9 }.toIntArray())
        assertEquals(CoachTip.Kind.DONE, ScanCoach.advise(input(coverage = ringsDone, ring = 1)).kind)
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
    fun measuredSurfaceIsRequiredBeforeViewsCanFinishTheScan() {
        val twoLaps = covered(*IntArray(2 * CoverageTracker.SEGMENTS) { it }.filter { it % CoverageTracker.SEGMENTS < 9 }.toIntArray())
        assertTrue(ScanCoach.isEnough(twoLaps))
        assertFalse(ScanCoach.isEnough(twoLaps, null))
        assertFalse(ScanCoach.isEnough(twoLaps, SurfaceCompleteness.GOOD - 0.01f))
        assertTrue(ScanCoach.isEnough(twoLaps, SurfaceCompleteness.GOOD))

        val missingShape = ScanCoach.advise(
            input(coverage = twoLaps, ring = 1, surfaceCompleteness = SurfaceCompleteness.GOOD - 0.08f),
        )
        assertEquals(CoachTip.Kind.INFO, missingShape.kind)
        assertTrue(missingShape.text.contains("shape still has gaps"))
        assertTrue(missingShape.text.contains("measured"))

        val ready = ScanCoach.advise(input(coverage = twoLaps, ring = 1, surfaceCompleteness = SurfaceCompleteness.GOOD))
        assertEquals(CoachTip.Kind.DONE, ready.kind)
    }

    @Test
    fun twoLapsAreEnoughAndTheProgressFollowsThem() {
        val none = BooleanArray(CoverageTracker.CELLS)
        assertFalse(ScanCoach.isEnough(none))
        assertEquals(0f, ScanCoach.progress(none), 0f)
        assertEquals(1, ScanCoach.currentLap(none))
        assertEquals(0 to CoverageTracker.SEGMENTS, ScanCoach.sidesCovered(none, 1))

        // The first lap three-quarters round: its half of the bar is full and the second lap begins.
        val firstLap = covered(*IntArray(9) { it })
        assertEquals(0.5f, ScanCoach.progress(firstLap), 1e-6f)
        assertEquals(2, ScanCoach.currentLap(firstLap))
        assertEquals(9 to 12, ScanCoach.sidesCovered(firstLap, 1))
        assertFalse(ScanCoach.isEnough(firstLap))

        // A little short of three-quarters doesn't fill it.
        val nearly = covered(*IntArray(8) { it })
        assertTrue(ScanCoach.progress(nearly) < 0.5f)
        assertEquals(1, ScanCoach.currentLap(nearly))

        val secondLap = covered(*IntArray(9) { it }, *IntArray(9) { CoverageTracker.SEGMENTS + it })
        assertTrue(ScanCoach.isEnough(secondLap))
        assertEquals(1f, ScanCoach.progress(secondLap), 1e-6f)
        // Past the main laps the extra ones are counted after them, and the counts follow that lap's ring.
        assertEquals(ScanCoach.MAIN_LAPS + 1, ScanCoach.currentLap(secondLap))
        assertEquals(0 to 12, ScanCoach.sidesCovered(secondLap, 3))
        // Progress doesn't go beyond the bar when a lap is covered fully.
        assertEquals(1f, ScanCoach.progress(BooleanArray(CoverageTracker.CELLS) { true }), 1e-6f)
    }

    @Test
    fun qualityRewardsFullCoverageAndGoodDepth() {
        val full = BooleanArray(CoverageTracker.CELLS) { true }
        val great = ScanQuality.assess(full, photos = 40, depthFrames = 70, depthQuality = 0.9f, surfaceCompleteness = 0.9f)
        assertTrue(great.score >= 90)
        assertEquals("Excellent", great.grade)
        assertTrue(great.issues.isEmpty())

        val half = BooleanArray(CoverageTracker.CELLS) { it < CoverageTracker.SEGMENTS + 4 }
        val weak = ScanQuality.assess(half, photos = 10, depthFrames = 20, depthQuality = 0.3f, surfaceCompleteness = 0.2f)
        assertTrue(weak.score < 50)
        assertEquals("Poor", weak.grade)
        assertTrue(weak.issues.any { it.startsWith("Some 45° views are missing") })
        assertTrue(weak.issues.any { it.startsWith("No view from straight above") })
        assertTrue(weak.issues.any { it.startsWith("Only 10 photos") })
        assertTrue(weak.issues.any { it.startsWith("The depth was weak") })
        assertTrue(weak.issues.any { it.contains("reconstructed surface") })
    }

    @Test
    fun depthQualityCountsOnlyConfident3dConsistentPixels() {
        val pose = SyntheticScan.lookAt(0f, 0.1f, 0.6f, 0f, 0.1f, 0f)
        val intrinsics = Intrinsics(80f, 80f, 40f, 30f, 80, 60)
        // A plane through the box centre: pixels in the projected box are geometrically valid object depth.
        val depth = ShortArray(80 * 60) { 600 }
        val confident = ByteArray(80 * 60) { if (it % 2 == 0) 255.toByte() else 10 }
        val box = ScanBox(0f, 0f, 0f, 0.2f, 0f)
        val quality = DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, pose, confident), box)!!
        assertEquals(0.5f, quality, 0.08f)
        assertNull(DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, pose), box))
        // Behind the camera: no answer.
        val away = SyntheticScan.lookAt(0f, 0.1f, 0.6f, 0f, 0.1f, 2f)
        assertNull(DepthQuality.measure(DepthFrame(80, 60, depth, intrinsics, away, confident), box))
    }

    @Test
    fun depthQualityDoesNotRewardConfidentWallOrForegroundOccluder() {
        val pose = SyntheticScan.lookAt(0f, 0.1f, 0.6f, 0f, 0.1f, 0f)
        val intrinsics = Intrinsics(80f, 80f, 40f, 30f, 80, 60)
        val confidence = ByteArray(80 * 60) { 255.toByte() }
        val box = ScanBox(0f, 0f, 0f, 0.2f, 0f)

        // Both maps have perfect confidence across the 2D silhouette, but neither surface is in the scan box.
        val wall = DepthFrame(80, 60, ShortArray(80 * 60) { 1_200 }, intrinsics, pose, confidence)
        val foreground = DepthFrame(80, 60, ShortArray(80 * 60) { 200 }, intrinsics, pose, confidence)
        assertEquals(0f, DepthQuality.measure(wall, box)!!, 0f)
        assertEquals(0f, DepthQuality.measure(foreground, box)!!, 0f)
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
