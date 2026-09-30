package com.ma7moud.reality3d.scan

/** The scan as the person sees it: four steps, one instruction at a time, and how far the walk has got. */
object ScanGuide {

    const val STEPS = 4

    val STEP_TITLES = listOf("Find the object", "Tap the object", "Set the size", "Walk around it")

    /** The step (1 to [STEPS]) the scan is at; 0 when it has stopped. */
    fun step(phase: ScanPhase): Int = when (phase) {
        ScanPhase.STARTING, ScanPhase.FIND_SURFACE -> 1
        ScanPhase.PLACE_BOX -> 2
        ScanPhase.READY -> 3
        ScanPhase.SCANNING, ScanPhase.BUILDING -> 4
        ScanPhase.FAILED -> 0
    }

    /**
     * The one thing to tell the person now, in words for the screen and the voice: the coach's advice while
     * scanning, and before that what the next step is. A problem with tracking leads in every step.
     */
    fun guidance(status: ScanStatus): CoachTip {
        val problem = status.trackingProblem
        return when (status.phase) {
            ScanPhase.STARTING, ScanPhase.FIND_SURFACE ->
                if (problem != null) CoachTip(problem, CoachTip.Kind.WARNING)
                else CoachTip("Point the camera at the object and move the phone slowly. Tap the object when you see it.", CoachTip.Kind.INFO)
            ScanPhase.PLACE_BOX ->
                if (problem != null) CoachTip(problem, CoachTip.Kind.WARNING) else CoachTip("Tap the object.", CoachTip.Kind.INFO)
            ScanPhase.READY ->
                CoachTip("Pick a size so the green box is a little bigger than the object. Then tap Start scan.", CoachTip.Kind.INFO)
            ScanPhase.SCANNING -> status.coach ?: CoachTip(
                when (status.nextStep) {
                    CoverageTracker.Step.LOW_RING -> "Walk slowly around the object, holding the phone at about its height."
                    CoverageTracker.Step.MIDDLE_RING -> "Now hold the phone a bit higher, looking down at 45°, and go around again."
                    CoverageTracker.Step.HIGH_RING -> "Hold the phone high and go around once more."
                    CoverageTracker.Step.TOP -> "Finish with a view from straight above the object."
                    CoverageTracker.Step.DONE -> "Every side is covered. Tap Build model."
                },
                CoachTip.Kind.INFO,
            )
            ScanPhase.BUILDING -> CoachTip("Building your model…", CoachTip.Kind.INFO)
            ScanPhase.FAILED -> CoachTip(status.message ?: "Scanning stopped.", CoachTip.Kind.WARNING)
        }
    }
}

/**
 * Decides when to say the guidance out loud, so the person can keep their eyes on the object: an instruction is
 * said once it has held for a moment, a warning again if it lasts, and calm advice not too often.
 */
class VoiceGuide(private val say: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {

    private var candidate: String? = null
    private var candidateSince = 0L
    private var lastSpoken: String? = null
    private var lastSpokenAt = 0L
    private var spoke = false

    /** Call often (a few times a second) with the current guidance. */
    fun onGuidance(tip: CoachTip) {
        val t = now()
        if (tip.text != candidate) {
            candidate = tip.text
            candidateSince = t
        }
        if (t - candidateSince < HOLD_MS) return
        val gap = t - lastSpokenAt
        if (spoke) {
            val again = tip.text == lastSpoken
            if (again && (tip.kind == CoachTip.Kind.INFO || gap < REPEAT_MS)) return
            if (gap < if (tip.kind == CoachTip.Kind.INFO) ADVICE_GAP_MS else WARNING_GAP_MS) return
        }
        spoke = true
        lastSpoken = tip.text
        lastSpokenAt = t
        say(tip.text)
    }

    private companion object {
        /** A new instruction must hold this long before it is said, so flickering advice stays quiet. */
        const val HOLD_MS = 800L
        const val WARNING_GAP_MS = 2_500L
        const val ADVICE_GAP_MS = 6_000L

        /** A warning that lasts is said again after this long. */
        const val REPEAT_MS = 15_000L
    }
}
