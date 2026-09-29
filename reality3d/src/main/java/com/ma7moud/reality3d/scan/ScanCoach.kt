package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.quality.QualityReport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A short instruction for the person scanning. */
data class CoachTip(val text: String, val kind: Kind) {
    enum class Kind { INFO, WARNING, DONE }
}

/** What the coach looks at on each frame. */
class CoachInput(
    val trackingProblem: String?,
    /** ARCore lost the object's anchor, so frames aren't trusted until it is found again. */
    val anchorLost: Boolean,
    val boxInView: Boolean,
    /** From the phone to the middle of the box, in meters; null until measured. */
    val distance: Float?,
    val boxSize: Float,
    /** Phone speed in m/s and turn rate in degrees per second. */
    val speed: Float,
    val turnRate: Float,
    /** Share of the object's area with confident depth (0..1), or null before any depth arrived. */
    val depthQuality: Float?,
    val coverage: BooleanArray,
    val phoneAzimuth: Float?,
    val phoneRing: Int?,
)

/** Turns what the phone sees into one instruction at a time, the most pressing first. */
object ScanCoach {

    /** Coverage at which the scan counts as complete. */
    const val COMPLETE = 0.85f
    const val MAX_SPEED = 0.35f
    const val MAX_TURN = 45f
    const val LOW_DEPTH = 0.25f
    private const val RING_TARGET = 0.75f

    /**
     * The walks around the object that are enough for a good model: at its height and from about 45° above. The
     * high walk and the view from above only clean up the top, so they are offered, not asked for.
     */
    const val MAIN_LAPS = 2

    /** Both main laps are covered: a model built now is good. */
    fun isEnough(coverage: BooleanArray): Boolean = (0 until MAIN_LAPS).all { ringFraction(coverage, it) >= RING_TARGET }

    /** How far the walk has got, for a progress bar: 0 to 1 over the main laps. */
    fun progress(coverage: BooleanArray): Float =
        (0 until MAIN_LAPS).sumOf { (ringFraction(coverage, it) / RING_TARGET).coerceAtMost(1f).toDouble() }.toFloat() / MAIN_LAPS

    /** The lap being walked, from 1: the first main lap not yet covered, or [MAIN_LAPS] + 1 for the optional ones. */
    fun currentLap(coverage: BooleanArray): Int =
        (0 until MAIN_LAPS).firstOrNull { ringFraction(coverage, it) < RING_TARGET }?.plus(1) ?: (MAIN_LAPS + 1)

    /** Sides of lap [lap] (from 1) covered so far, and how many it has. */
    fun sidesCovered(coverage: BooleanArray, lap: Int): Pair<Int, Int> {
        val ring = (lap - 1).coerceIn(0, CoverageTracker.RINGS - 1)
        return (0 until CoverageTracker.SEGMENTS).count { coverage[ring * CoverageTracker.SEGMENTS + it] } to CoverageTracker.SEGMENTS
    }

    /** Closer than this, depth gets unreliable and the object overflows the frame. */
    fun nearLimit(boxSize: Float): Float = max(0.2f, boxSize * 0.9f)

    /** Further than this, depth gets coarse compared with the object. */
    fun farLimit(boxSize: Float): Float = max(0.8f, boxSize * 3.5f)

    fun advise(input: CoachInput): CoachTip {
        input.trackingProblem?.let { return CoachTip(it, CoachTip.Kind.WARNING) }
        if (input.anchorLost) return warning("Hold still for a moment while the phone finds the object again.")
        if (!input.boxInView) return warning("Point the camera at the object.")
        if (input.speed > MAX_SPEED || input.turnRate > MAX_TURN) return warning("Too fast. Move the phone slowly.")
        val distance = input.distance
        if (distance != null && distance < nearLimit(input.boxSize)) return warning("Too close. Step back a little.")
        if (distance != null && distance > farLimit(input.boxSize)) return warning("Come closer to the object.")
        val quality = input.depthQuality
        if (quality != null && quality < LOW_DEPTH) {
            return warning("Low detail here. Add light, or put a patterned cloth under the object.")
        }
        val covered = input.coverage.count { it }.toFloat() / input.coverage.size
        if (covered >= COMPLETE) {
            val text = if (input.coverage[CoverageTracker.TOP_CELL]) {
                "Scan complete. Tap Build model."
            } else {
                "Scan complete. Tap Build model, or first add a view from straight above for a cleaner top."
            }
            return CoachTip(text, CoachTip.Kind.DONE)
        }
        if (isEnough(input.coverage)) {
            return CoachTip("That's enough. Tap Build model, or go around once more with the phone higher for a cleaner top.", CoachTip.Kind.DONE)
        }
        return CoachTip(direction(input), CoachTip.Kind.INFO)
    }

    /** Where to go next: the ring that needs views, then left or right to the nearest gap in it. */
    private fun direction(input: CoachInput): String {
        val coverage = input.coverage
        val target = (0 until CoverageTracker.RINGS).firstOrNull { ringFraction(coverage, it) < RING_TARGET }
            ?: if (!coverage[CoverageTracker.TOP_CELL]) CoverageTracker.TOP_RING else mostMissingRing(coverage)
        if (target == CoverageTracker.TOP_RING) return "Top view missing. Hold the phone above the object, looking straight down."
        val ring = input.phoneRing
        val azimuth = input.phoneAzimuth
        if (ring == null || azimuth == null) return ringHint(target)
        if (ring != target) {
            return if (target > ring) "Raise the phone and look down at the object." else "Lower the phone to about the object's height."
        }
        val segment = (azimuth / (360f / CoverageTracker.SEGMENTS)).toInt().coerceIn(0, CoverageTracker.SEGMENTS - 1)
        if (!coverage[ring * CoverageTracker.SEGMENTS + segment]) return "Good. Hold steady for a moment."
        // Walking to your right, as you face the object, raises the azimuth.
        for (step in 1..CoverageTracker.SEGMENTS / 2) {
            if (!coverage[ring * CoverageTracker.SEGMENTS + (segment + step) % CoverageTracker.SEGMENTS]) return "Move right, around the object."
            if (!coverage[ring * CoverageTracker.SEGMENTS + (segment - step + CoverageTracker.SEGMENTS) % CoverageTracker.SEGMENTS]) {
                return "Move left, around the object."
            }
        }
        return ringHint(target)
    }

    private fun ringHint(ring: Int): String = when (ring) {
        0 -> "Walk slowly around the object, holding the phone at about its height."
        1 -> "Hold the phone higher and go around again, looking down at about 45°."
        else -> "Hold the phone high and go around once more."
    }

    internal fun ringFraction(coverage: BooleanArray, ring: Int): Float {
        if (ring == CoverageTracker.TOP_RING) return if (coverage[CoverageTracker.TOP_CELL]) 1f else 0f
        var count = 0
        for (segment in 0 until CoverageTracker.SEGMENTS) if (coverage[ring * CoverageTracker.SEGMENTS + segment]) count++
        return count.toFloat() / CoverageTracker.SEGMENTS
    }

    private fun mostMissingRing(coverage: BooleanArray): Int = (0 until CoverageTracker.RINGS).minBy { ringFraction(coverage, it) }

    private fun warning(text: String) = CoachTip(text, CoachTip.Kind.WARNING)
}

/** How complete and trustworthy a finished scan is, and what would improve it. */
object ScanQuality {

    fun assess(coverage: BooleanArray, photos: Int, depthFrames: Int, depthQuality: Float?): QualityReport {
        val covered = coverage.count { it }.toFloat() / coverage.size
        val issues = ArrayList<String>()
        val ringNames = listOf("low", "45°", "high")
        for (ring in 0 until CoverageTracker.RINGS) {
            val fraction = ScanCoach.ringFraction(coverage, ring)
            if (fraction < 0.75f) issues += "Some ${ringNames[ring]} views are missing (${(fraction * 100).roundToInt()}% of that ring)."
        }
        if (!coverage[CoverageTracker.TOP_CELL]) issues += "No view from straight above, so the top may be rough."
        if (photos < GOOD_PHOTOS * 2 / 3) issues += "Only $photos photos, so the colours may be patchy."
        if (depthFrames < GOOD_DEPTH_FRAMES * 2 / 3) issues += "Only $depthFrames depth maps, so the shape may be rough."
        if (depthQuality != null && depthQuality < 0.4f) issues += "The depth was weak: more light or a textured surface underneath helps."
        val score = covered * 55f +
            min(1f, photos / GOOD_PHOTOS.toFloat()) * 15f +
            min(1f, depthFrames / GOOD_DEPTH_FRAMES.toFloat()) * 15f +
            (depthQuality ?: UNKNOWN_DEPTH_QUALITY).coerceIn(0f, 1f) * 15f
        return QualityReport(score.roundToInt().coerceIn(0, 100), issues)
    }

    private const val GOOD_PHOTOS = 36
    private const val GOOD_DEPTH_FRAMES = 60

    /** Smoothed depth has no confidence map; count it as fair. */
    private const val UNKNOWN_DEPTH_QUALITY = 0.6f
}

/** How much of the box, as the depth camera sees it, has depth ARCore is confident about. */
internal object DepthQuality {

    /** Confidence (0..255) from which a depth pixel counts as reliable. */
    private const val CONFIDENT = 128

    /**
     * The share of pixels inside the box's outline on the depth image that have confident depth; null when
     * the frame has no confidence map or the box is out of view.
     */
    fun measure(frame: DepthFrame, box: ScanBox): Float? {
        val confidence = frame.confidence ?: return null
        val corner = FloatArray(3)
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (k in 0 until 8) {
            val x = if (k and 1 == 0) box.minX else box.minX + box.size
            val y = if (k and 2 == 0) box.bottomY else box.bottomY + box.size
            val z = if (k and 4 == 0) box.minZ else box.minZ + box.size
            if (!frame.pose.project(x, y, z, frame.intrinsics, corner)) return null
            left = min(left, corner[0])
            top = min(top, corner[1])
            right = max(right, corner[0])
            bottom = max(bottom, corner[1])
        }
        val x0 = left.toInt().coerceIn(0, frame.width)
        val y0 = top.toInt().coerceIn(0, frame.height)
        val x1 = right.toInt().coerceIn(0, frame.width)
        val y1 = bottom.toInt().coerceIn(0, frame.height)
        if (x1 <= x0 || y1 <= y0) return null
        var good = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = y * frame.width + x
                if (frame.depthMm[i].toInt() != 0 && (confidence[i].toInt() and 0xFF) >= CONFIDENT) good++
            }
        }
        return good.toFloat() / ((x1 - x0) * (y1 - y0))
    }
}
