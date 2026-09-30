package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGuideTest {

    private fun status(phase: ScanPhase, coach: CoachTip? = null, problem: String? = null, next: CoverageTracker.Step = CoverageTracker.Step.LOW_RING, message: String? = null) =
        ScanStatus(phase = phase, coach = coach, trackingProblem = problem, nextStep = next, message = message)

    @Test
    fun theStepsRunFromFindingTheObjectToWalkingAroundIt() {
        assertEquals(1, ScanGuide.step(ScanPhase.STARTING))
        assertEquals(1, ScanGuide.step(ScanPhase.FIND_SURFACE))
        assertEquals(2, ScanGuide.step(ScanPhase.PLACE_BOX))
        assertEquals(3, ScanGuide.step(ScanPhase.READY))
        assertEquals(4, ScanGuide.step(ScanPhase.SCANNING))
        assertEquals(4, ScanGuide.step(ScanPhase.BUILDING))
        assertEquals(0, ScanGuide.step(ScanPhase.FAILED))
        assertEquals(ScanGuide.STEPS, ScanGuide.STEP_TITLES.size)
    }

    @Test
    fun beforeScanningTheGuidanceIsTheNextStepAndTrackingProblemsComeFirst() {
        val find = ScanGuide.guidance(status(ScanPhase.FIND_SURFACE))
        assertEquals(CoachTip.Kind.INFO, find.kind)
        assertTrue(find.text, find.text.contains("Tap the object when you see it"))
        assertEquals("Too dark. Turn on more light.", ScanGuide.guidance(status(ScanPhase.FIND_SURFACE, problem = "Too dark. Turn on more light.")).text)
        assertEquals("Tap the object.", ScanGuide.guidance(status(ScanPhase.PLACE_BOX)).text)
        assertEquals(CoachTip.Kind.WARNING, ScanGuide.guidance(status(ScanPhase.PLACE_BOX, problem = "Move the phone more slowly.")).kind)
        assertTrue(ScanGuide.guidance(status(ScanPhase.READY)).text.contains("Start scan"))
        assertEquals("Building your model…", ScanGuide.guidance(status(ScanPhase.BUILDING)).text)
        val failed = ScanGuide.guidance(status(ScanPhase.FAILED, message = "The camera isn't available."))
        assertEquals(CoachTip.Kind.WARNING, failed.kind)
        assertEquals("The camera isn't available.", failed.text)
    }

    @Test
    fun whileScanningTheCoachLeadsAndTheNextLapIsTheFallback() {
        val coach = CoachTip("Too fast. Move the phone slowly.", CoachTip.Kind.WARNING)
        assertEquals(coach, ScanGuide.guidance(status(ScanPhase.SCANNING, coach = coach)))
        assertTrue(ScanGuide.guidance(status(ScanPhase.SCANNING, next = CoverageTracker.Step.LOW_RING)).text.contains("about its height"))
        assertTrue(ScanGuide.guidance(status(ScanPhase.SCANNING, next = CoverageTracker.Step.MIDDLE_RING)).text.contains("a bit higher"))
        assertTrue(ScanGuide.guidance(status(ScanPhase.SCANNING, next = CoverageTracker.Step.DONE)).text.contains("Tap Build model"))
    }

    private class Speech {
        var now = 1_000_000L
        val said = ArrayList<String>()
        val guide = VoiceGuide(say = { said += it }, now = { now })

        /** Feeds the same guidance every 300 ms for [seconds], as the screen does. */
        fun hold(tip: CoachTip, seconds: Double) {
            var t = 0
            while (t < seconds * 1000) {
                guide.onGuidance(tip)
                now += 300
                t += 300
            }
        }
    }

    private fun warning(text: String) = CoachTip(text, CoachTip.Kind.WARNING)
    private fun advice(text: String) = CoachTip(text, CoachTip.Kind.INFO)

    @Test
    fun anInstructionIsSaidOnceItHasHeldForAMoment() {
        val speech = Speech()
        speech.hold(advice("Tap the object."), 0.6)
        assertTrue("nothing yet: it might still change", speech.said.isEmpty())
        speech.hold(advice("Tap the object."), 1.5)
        assertEquals(listOf("Tap the object."), speech.said)
    }

    @Test
    fun adviceThatFlickersStaysQuiet() {
        val speech = Speech()
        repeat(10) {
            speech.hold(warning("Too fast. Move the phone slowly."), 0.6)
            speech.hold(advice("Move right, around the object."), 0.6)
        }
        assertTrue(speech.said.toString(), speech.said.isEmpty())
    }

    @Test
    fun aWarningThatLastsIsSaidAgainButCalmAdviceIsNot() {
        val speech = Speech()
        speech.hold(warning("Too close. Step back a little."), 40.0)
        // Said at once, again after 15 s, again after 30 s.
        assertEquals(3, speech.said.count { it == "Too close. Step back a little." })
        val calm = Speech()
        calm.hold(advice("Walk slowly around the object."), 40.0)
        assertEquals(listOf("Walk slowly around the object."), calm.said)
    }

    @Test
    fun differentAdviceIsSpacedOutMoreThanWarnings() {
        val speech = Speech()
        speech.hold(advice("Walk slowly around the object."), 1.5)
        speech.hold(advice("Now hold the phone a bit higher."), 1.5)
        // Too soon after the first: it waits its turn, and is said once six seconds have gone.
        assertEquals(listOf("Walk slowly around the object."), speech.said)
        speech.hold(advice("Now hold the phone a bit higher."), 5.0)
        assertEquals(listOf("Walk slowly around the object.", "Now hold the phone a bit higher."), speech.said)
        // A warning only waits two and a half seconds.
        speech.hold(warning("Too fast. Move the phone slowly."), 3.5)
        assertEquals("Too fast. Move the phone slowly.", speech.said.last())
    }
}
