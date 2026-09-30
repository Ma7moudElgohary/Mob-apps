package com.ma7moud.reality3d.scan

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.quality.QualityReport
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
    /** Share of reconstructed surface directly supported by TSDF measurements, rather than inferred fill. */
    val surfaceCompleteness: Float? = null,
    val nextStep: CoverageTracker.Step = CoverageTracker.Step.LOW_RING,
    /** Where the phone is around the object, for the coverage radar. */
    val phoneAzimuth: Float? = null,
    val phoneRing: Int? = null,
    val photos: Int = 0,
    val depthFrames: Int = 0,
    val message: String? = null,
    /** What to do right now while scanning, or null to fall back to [nextStep]. */
    val coach: CoachTip? = null,
) {
    companion object {
        const val DEFAULT_BOX_SIZE = 0.4f
        const val MIN_BOX_SIZE = 0.15f
        const val MAX_BOX_SIZE = 1.2f
    }
}

/**
 * A finished scan: the model, the photos it was coloured from, and how good the capture was. A model built from
 * the photos on the computer comes with its [texture]; [sizeKnown] is false when only its shape is real.
 */
class ScanCapture(
    val mesh: Mesh3D,
    val keyframes: List<Keyframe>,
    val quality: QualityReport,
    val texture: Bitmap? = null,
    val metersPerUnit: Float = 1f,
    val sizeKnown: Boolean = true,
    /** Built from the photos by the computer's photo builder, not by this phone. */
    val fromPhotos: Boolean = false,
)

/** The photos of a scan so far and where the object stood, for building the model on a computer. */
class PhotoSet(val keyframes: List<Keyframe>, val box: ScanBox?)

/** A 360° scanning session with its own camera view. */
interface ScanEngine {
    val status: StateFlow<ScanStatus>

    /** The camera view to show full screen; taps on it place the scan box. */
    fun createView(context: Context): View

    /** What the engine measured about this phone and scan, for the scan report; empty when it has nothing to say. */
    val details: String get() = ""

    fun setBoxSize(meters: Float)

    fun startScanning()

    /** After a build, goes back to scanning with everything captured so far, to add more views. */
    fun continueScanning()

    /** Drops what was scanned and goes back to placing the box. */
    fun restart()

    fun resume()

    fun pause()

    suspend fun build(progress: (String) -> Unit): ScanCapture

    /** The photos taken so far, once the last one is stored; null when there are none. */
    suspend fun photoSet(): PhotoSet? = null

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
