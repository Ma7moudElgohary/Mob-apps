package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.quality.QualityReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLogTest {

    private var clock = 1_000_000L
    private val log = ScanLog { clock }

    private fun at(seconds: Int, status: ScanStatus) {
        clock = 1_000_000L + seconds * 1000L
        log.onStatus(status)
    }

    private fun scanning(coach: CoachTip? = null, coverage: Float = 0.4f, photos: Int = 10, depth: Int = 20, message: String? = null) =
        ScanStatus(phase = ScanPhase.SCANNING, coach = coach, coverageFraction = coverage, photos = photos, depthFrames = depth, boxSize = 0.3f, message = message)

    private fun warning(text: String) = CoachTip(text, CoachTip.Kind.WARNING)

    @Test
    fun theTimelineShowsEachStepAndHowLongItTook() {
        at(0, ScanStatus(phase = ScanPhase.STARTING))
        at(2, ScanStatus(phase = ScanPhase.FIND_SURFACE))
        at(2, ScanStatus(phase = ScanPhase.FIND_SURFACE))
        at(14, ScanStatus(phase = ScanPhase.PLACE_BOX))
        at(20, ScanStatus(phase = ScanPhase.READY))
        at(23, scanning())
        at(83, scanning(coverage = 0.72f, photos = 34, depth = 61))
        val text = log.report("HEADER")

        assertTrue(text, text.startsWith("HEADER"))
        val timeline = text.substringAfter("Timeline\n").substringBefore("\nTime in each step")
        assertEquals(
            listOf("0:00 starting the camera", "0:02 finding the surface", "0:14 waiting for a tap on the object", "0:20 box placed, waiting to start", "0:23 scanning"),
            timeline.lines().map { it.trim() },
        )
        assertTrue(text, text.contains("finding the surface 12 s"))
        assertTrue(text, text.contains("scanning 60 s"))
        assertTrue(text, text.contains("Scan: box 30 cm, 72% covered, 34 photos, 61 depth maps"))
    }

    @Test
    fun warningsAreCountedAsEpisodesWithTheTimeTheyWereShown() {
        at(0, scanning())
        at(10, scanning(warning("Too fast. Move the phone slowly.")))
        at(11, scanning(warning("Too fast. Move the phone slowly.")))
        at(14, scanning())
        at(20, scanning(warning("Too fast. Move the phone slowly.")))
        at(25, scanning(warning("Low detail here. Add light.")))
        at(26, scanning())
        // A tracking problem the coach didn't lead with still counts.
        at(30, ScanStatus(phase = ScanPhase.SCANNING, trackingProblem = "Too dark. Turn on more light."))
        at(33, scanning())
        val text = log.report("H")

        // Two episodes of 4 s and 5 s.
        assertTrue(text, text.contains("x2 · 9.0 s · Too fast. Move the phone slowly."))
        assertTrue(text, text.contains("x1 · 1.0 s · Low detail here. Add light."))
        assertTrue(text, text.contains("x1 · 3.0 s · Too dark. Turn on more light."))
        // The longest first.
        assertTrue(text.indexOf("Too fast") < text.indexOf("Too dark"))
        // Advice that isn't a warning isn't listed.
        at(40, scanning(CoachTip("Move right, around the object.", CoachTip.Kind.INFO)))
        assertFalse(log.report("H").contains("Move right"))
    }

    @Test
    fun messagesEventsAndTheBuildAreRecorded() {
        at(0, ScanStatus(phase = ScanPhase.PLACE_BOX, message = "Couldn't find anything there. Move a little and tap the object again."))
        at(1, ScanStatus(phase = ScanPhase.PLACE_BOX, message = "Couldn't find anything there. Move a little and tap the object again."))
        at(5, ScanStatus(phase = ScanPhase.PLACE_BOX))
        at(9, ScanStatus(phase = ScanPhase.PLACE_BOX, message = "Couldn't find anything there. Move a little and tap the object again."))
        at(90, scanning())
        log.event("Added more views")
        log.onBuilt(6.2f, QualityReport(71, listOf("No view from straight above, so the top may be rough.")), 41_000)
        val text = log.report("H", "ARCore camera 1920×1080, depth map 160×90")

        assertTrue(text, text.contains("ARCore camera 1920×1080, depth map 160×90"))
        // The same message published again and again is one message.
        assertTrue(text, text.contains("x2 · Couldn't find anything there."))
        assertTrue(text, text.contains("1:30 Added more views"))
        assertTrue(text, text.contains("Built in 6.2 s: 41000 triangles, quality 71/100 Good"))
        assertTrue(text, text.contains("  - No view from straight above"))

        log.onBuildFailed("OutOfMemoryError: out of memory")
        assertTrue(log.report("H").contains("Building failed: OutOfMemoryError: out of memory"))
    }

    @Test
    fun anEmptyLogStillMakesAReport() {
        val text = log.report("HEADER")
        assertTrue(text.startsWith("HEADER"))
        assertTrue(text.contains("Timeline"))
    }
}
