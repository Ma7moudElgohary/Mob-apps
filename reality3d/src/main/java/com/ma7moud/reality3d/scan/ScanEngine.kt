package com.ma7moud.reality3d.scan

import android.app.Activity
import android.content.Context
import android.view.View
import com.ma7moud.reality3d.mesh.Mesh3D
import kotlinx.coroutines.flow.StateFlow

enum class ScanPhase { STARTING, FIND_SURFACE, PLACE_BOX, READY, SCANNING, BUILDING, FAILED }

/** What the scan screen shows while the camera runs. */
class ScanStatus(
    val phase: ScanPhase = ScanPhase.STARTING,
    /** Why tracking is struggling, or null when it is fine. */
    val trackingProblem: String? = null,
    val boxSize: Float = DEFAULT_BOX_SIZE,
    /** Covered cells, see [CoverageTracker]. */
    val coverage: BooleanArray = BooleanArray(CoverageTracker.CELLS),
    val coverageFraction: Float = 0f,
    val nextStep: CoverageTracker.Step = CoverageTracker.Step.LOW_RING,
    /** Where the phone is around the object, for the coverage radar. */
    val phoneAzimuth: Float? = null,
    val phoneRing: Int? = null,
    val photos: Int = 0,
    val depthFrames: Int = 0,
    val message: String? = null,
) {
    companion object {
        const val DEFAULT_BOX_SIZE = 0.4f
        const val MIN_BOX_SIZE = 0.15f
        const val MAX_BOX_SIZE = 1.2f
    }
}

/** A finished scan: the model and the photos it was coloured from. */
class ScanCapture(val mesh: Mesh3D, val keyframes: List<Keyframe>)

/** A 360° scanning session with its own camera view. */
interface ScanEngine {
    val status: StateFlow<ScanStatus>

    /** The camera view to show full screen; taps on it place the scan box. */
    fun createView(context: Context): View

    fun setBoxSize(meters: Float)

    fun startScanning()

    /** Drops what was scanned and goes back to placing the box. */
    fun restart()

    fun resume()

    fun pause()

    suspend fun build(progress: (String) -> Unit): ScanCapture

    /** Releases the camera. The engine can't be used afterwards. */
    fun close()
}

sealed interface ScanSupport {
    data object Ready : ScanSupport
    data object Checking : ScanSupport
    data object Installing : ScanSupport
    data class Unsupported(val reason: String) : ScanSupport
}

interface ScanEngineFactory {
    /**
     * Checks that ARCore is available, asking the Play Store to install or update it when
     * [userRequestedInstall] is true. Call again when the activity resumes after an install.
     */
    fun check(activity: Activity, userRequestedInstall: Boolean): ScanSupport

    fun create(context: Context): ScanEngine
}
