package com.ma7moud.reality3d.scan

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Which directions around the object have been photographed: three rings of twelve 30° segments
 * (low, 45° and high views) plus a cell for looking straight down.
 */
class CoverageTracker {

    val covered = BooleanArray(CELLS)

    /** Fraction of all cells covered. */
    val fraction: Float get() = covered.count { it }.toFloat() / CELLS

    fun ringFraction(ring: Int): Float {
        if (ring == TOP_RING) return if (covered[TOP_CELL]) 1f else 0f
        var count = 0
        for (segment in 0 until SEGMENTS) if (covered[ring * SEGMENTS + segment]) count++
        return count.toFloat() / SEGMENTS
    }

    /** Marks a cell; true when it was new. */
    fun mark(cell: Int): Boolean {
        if (covered[cell]) return false
        covered[cell] = true
        return true
    }

    fun reset() = covered.fill(false)

    /** What to do next, from the least covered ring upwards. */
    fun nextStep(): Step = when {
        ringFraction(0) < RING_TARGET -> Step.LOW_RING
        ringFraction(1) < RING_TARGET -> Step.MIDDLE_RING
        ringFraction(2) < RING_TARGET -> Step.HIGH_RING
        !covered[TOP_CELL] -> Step.TOP
        else -> Step.DONE
    }

    enum class Step { LOW_RING, MIDDLE_RING, HIGH_RING, TOP, DONE }

    companion object {
        const val SEGMENTS = 12
        const val RINGS = 3
        const val TOP_RING = 3
        const val TOP_CELL = RINGS * SEGMENTS
        const val CELLS = TOP_CELL + 1
        private const val RING_TARGET = 0.75f

        /** Ring (0 low … 2 high, 3 top) and azimuth of a unit direction from the object towards the camera. */
        fun cellOf(dx: Float, dy: Float, dz: Float): Int {
            val elevation = Math.toDegrees(asin(dy.coerceIn(-1f, 1f)).toDouble())
            if (elevation >= 80.0) return TOP_CELL
            val ring = when {
                elevation < 30.0 -> 0
                elevation < 55.0 -> 1
                else -> 2
            }
            return ring * SEGMENTS + segmentOf(dx, dz)
        }

        fun segmentOf(dx: Float, dz: Float): Int {
            var azimuth = Math.toDegrees(atan2(dx, dz).toDouble())
            if (azimuth < 0) azimuth += 360.0
            return (azimuth / (360.0 / SEGMENTS)).toInt() % SEGMENTS
        }

        /** Azimuth in degrees (0..360) of a direction, as drawn on the coverage radar. */
        fun azimuthDegrees(dx: Float, dz: Float): Float {
            val azimuth = Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
            return if (azimuth < 0) azimuth + 360f else azimuth
        }
    }
}

/** Keeps photos at least [minAngleDegrees] apart as seen from the object, so they cover it evenly. */
class KeyframeSelector(minAngleDegrees: Float = 9f) {

    private val cosLimit = cos(minAngleDegrees * PI / 180.0).toFloat()
    private val directions = ArrayList<FloatArray>()

    val count: Int get() = directions.size

    fun isNew(direction: FloatArray): Boolean =
        directions.none { it[0] * direction[0] + it[1] * direction[1] + it[2] * direction[2] > cosLimit }

    fun add(direction: FloatArray) {
        directions += direction.copyOf()
    }

    fun reset() = directions.clear()
}
