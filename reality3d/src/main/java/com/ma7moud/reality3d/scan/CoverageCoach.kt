package com.ma7moud.reality3d.scan

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

data class CoverageState(
    val covered: BooleanArray = BooleanArray(9),
    val distanceMeters: Float = 0f,
    val speedMetersPerSecond: Float = 0f,
    val depthConfidence: Float = 0f,
    val trackingGood: Boolean = false,
) {
    val coveragePercent: Int get() = ((covered.count { it } / covered.size.toFloat()) * 100f).toInt()

    fun message(): String = when {
        !trackingGood -> "Tracking lost — move slowly toward a textured area"
        speedMetersPerSecond > 0.65f -> "Too fast — slow down"
        distanceMeters > 2.2f -> "Come closer"
        distanceMeters in 0f..0.45f -> "Move back a little"
        depthConfidence < 0.28f -> "Low depth confidence — improve light and angle"
        !covered[8] && coveragePercent >= 65 -> "Capture the top of the object"
        coveragePercent >= 85 -> "Scan complete"
        else -> "Move around the object — ${coveragePercent}% coverage"
    }
}

class CoverageCoach {
    private var lastCamera: Vector3? = null
    private var lastTimeNanos: Long = 0L
    private val bins = BooleanArray(9)

    fun seed(covered: BooleanArray) {
        for (i in bins.indices) bins[i] = covered.getOrNull(i) == true
        lastCamera = null
        lastTimeNanos = 0L
    }

    fun update(camera: Vector3, target: Vector3, nowNanos: Long, depthConfidence: Float, trackingGood: Boolean): CoverageState {
        val offset = camera - target
        val distance = offset.length()
        val horizontal = sqrt(offset.x * offset.x + offset.z * offset.z)
        val elevation = atan2(offset.y, horizontal) * 180f / PI.toFloat()
        if (trackingGood && distance in 0.35f..3.0f) {
            if (elevation > 32f) {
                bins[8] = true
            } else {
                var angle = atan2(offset.x, offset.z) * 180f / PI.toFloat()
                if (angle < 0f) angle += 360f
                val bin = ((angle + 22.5f) / 45f).toInt() % 8
                bins[bin] = true
            }
        }
        val last = lastCamera
        val dt = if (lastTimeNanos == 0L) 0f else (nowNanos - lastTimeNanos) / 1_000_000_000f
        val speed = if (last == null || dt <= 1e-4f) 0f else (camera - last).length() / dt
        lastCamera = camera
        lastTimeNanos = nowNanos
        return CoverageState(bins.copyOf(), distance, speed, depthConfidence, trackingGood)
    }

    fun reset() {
        bins.fill(false)
        lastCamera = null
        lastTimeNanos = 0L
    }
}
