package com.ma7moud.reality3d.scan

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

private const val MAX_TRANSLATIONAL_SPEED_MPS = 0.65f
private const val MAX_ANGULAR_SPEED_DPS = 60f
private const val MAX_AIM_ERROR_DEGREES = 28f
private const val MIN_COVERAGE_CONFIDENCE = 0.55f
private const val MIN_DEPTH_POINTS = 32

data class CoverageState(
    val covered: BooleanArray = BooleanArray(9),
    val distanceMeters: Float = 0f,
    val speedMetersPerSecond: Float = 0f,
    val angularSpeedDegreesPerSecond: Float = 0f,
    val depthConfidence: Float = 0f,
    val depthPointCount: Int = 0,
    val aimErrorDegrees: Float = 180f,
    val trackingGood: Boolean = false,
    val frameUsable: Boolean = false,
) {
    val coveragePercent: Int get() = ((covered.count { it } / covered.size.toFloat()) * 100f).toInt()

    fun message(): String = when {
        !trackingGood -> "Tracking lost — move slowly toward a textured area"
        speedMetersPerSecond > MAX_TRANSLATIONAL_SPEED_MPS -> "Too fast — move more slowly"
        angularSpeedDegreesPerSecond > MAX_ANGULAR_SPEED_DPS -> "Rotating too fast — turn more slowly"
        distanceMeters > 2.2f -> "Come closer"
        distanceMeters in 0f..0.45f -> "Move back a little"
        aimErrorDegrees > MAX_AIM_ERROR_DEGREES -> "Aim the reticle at the object"
        depthPointCount < MIN_DEPTH_POINTS -> "Hold steady — waiting for enough object depth"
        depthConfidence < MIN_COVERAGE_CONFIDENCE -> "Low depth confidence — improve light and angle"
        !covered[8] && coveragePercent >= 65 -> "Capture the top of the object"
        coveragePercent >= 85 -> "Scan complete"
        else -> "Move around the object — ${coveragePercent}% coverage"
    }
}

class CoverageCoach {
    private data class PoseMetrics(
        val distance: Float,
        val speed: Float,
        val angularSpeed: Float,
        val aimError: Float,
        val elevation: Float,
        val offset: Vector3,
    )

    private var lastCamera: Vector3? = null
    private var lastForward: Vector3? = null
    private var lastTimeNanos: Long = 0L
    private val bins = BooleanArray(9)

    fun seed(covered: BooleanArray) {
        for (i in bins.indices) bins[i] = covered.getOrNull(i) == true
        lastCamera = null
        lastForward = null
        lastTimeNanos = 0L
    }

    /**
     * Fast pose-only gate used before TSDF fusion. It intentionally does not mutate motion
     * history; [update] commits the pose only after a fresh raw-depth frame has been evaluated.
     */
    fun shouldFuse(
        camera: Vector3,
        cameraForward: Vector3,
        target: Vector3,
        nowNanos: Long,
        trackingGood: Boolean,
    ): Boolean = poseUsable(
        measurePose(camera, cameraForward, target, nowNanos),
        trackingGood,
    )

    fun update(
        camera: Vector3,
        cameraForward: Vector3,
        target: Vector3,
        nowNanos: Long,
        depthConfidence: Float,
        depthPointCount: Int,
        trackingGood: Boolean,
    ): CoverageState {
        val forward = cameraForward.normalized()
        val metrics = measurePose(camera, forward, target, nowNanos)
        val frameUsable = poseUsable(metrics, trackingGood) &&
            depthPointCount >= MIN_DEPTH_POINTS &&
            depthConfidence >= MIN_COVERAGE_CONFIDENCE

        if (frameUsable) {
            if (metrics.elevation > 32f) {
                bins[8] = true
            } else {
                var angle = atan2(metrics.offset.x, metrics.offset.z) * 180f / PI.toFloat()
                if (angle < 0f) angle += 360f
                val bin = ((angle + 22.5f) / 45f).toInt() % 8
                bins[bin] = true
            }
        }

        lastCamera = camera
        lastForward = forward
        lastTimeNanos = nowNanos
        return CoverageState(
            covered = bins.copyOf(),
            distanceMeters = metrics.distance,
            speedMetersPerSecond = metrics.speed,
            angularSpeedDegreesPerSecond = metrics.angularSpeed,
            depthConfidence = depthConfidence,
            depthPointCount = depthPointCount,
            aimErrorDegrees = metrics.aimError,
            trackingGood = trackingGood,
            frameUsable = frameUsable,
        )
    }

    fun reset() {
        bins.fill(false)
        lastCamera = null
        lastForward = null
        lastTimeNanos = 0L
    }

    private fun measurePose(
        camera: Vector3,
        cameraForward: Vector3,
        target: Vector3,
        nowNanos: Long,
    ): PoseMetrics {
        val offset = camera - target
        val distance = offset.length()
        val horizontal = sqrt(offset.x * offset.x + offset.z * offset.z)
        val elevation = atan2(offset.y, horizontal) * 180f / PI.toFloat()
        val forward = cameraForward.normalized()
        val toTarget = (target - camera).normalized()
        val aimError = angleDegrees(forward, toTarget)

        val lastPosition = lastCamera
        val previousForward = lastForward
        val dt = if (lastTimeNanos == 0L) 0f else (nowNanos - lastTimeNanos) / 1_000_000_000f
        val speed = if (lastPosition == null || dt <= 1e-4f) {
            0f
        } else {
            (camera - lastPosition).length() / dt
        }
        val angularSpeed = if (previousForward == null || dt <= 1e-4f) {
            0f
        } else {
            angleDegrees(previousForward, forward) / dt
        }

        return PoseMetrics(distance, speed, angularSpeed, aimError, elevation, offset)
    }

    private fun poseUsable(metrics: PoseMetrics, trackingGood: Boolean): Boolean =
        trackingGood &&
            metrics.distance in 0.35f..3.0f &&
            metrics.speed <= MAX_TRANSLATIONAL_SPEED_MPS &&
            metrics.angularSpeed <= MAX_ANGULAR_SPEED_DPS &&
            metrics.aimError <= MAX_AIM_ERROR_DEGREES

    private fun angleDegrees(a: Vector3, b: Vector3): Float {
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
        return acos(dot) * 180f / PI.toFloat()
    }
}
