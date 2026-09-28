package com.ma7moud.reality3d.preview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.scan.ScanSupport
import kotlinx.coroutines.flow.StateFlow

/** A model to stand in the room: its mesh, texture (for photo models) and real size per unit. */
class PreviewModel(val mesh: Mesh3D, val texture: Bitmap?, val metersPerUnit: Float, val name: String)

data class ArPreviewStatus(
    val phase: Phase = Phase.STARTING,
    /** Size relative to the model's real size. */
    val scale: Float = 1f,
    val trackingProblem: String? = null,
    val message: String? = null,
) {
    enum class Phase { STARTING, FINDING_SURFACE, READY_TO_PLACE, PLACED, FAILED }
}

/** The model shown in the camera picture, standing on a real surface. */
interface ArPreview {
    val status: StateFlow<ArPreviewStatus>

    /** The camera view to show full screen: tap to place, drag to turn, pinch to resize. */
    val view: View

    fun resume()

    fun pause()

    /** Back to the model's real size. */
    fun resetScale()

    /** Picks the model up so it can be placed somewhere else. */
    fun placeAgain()

    /** Releases the camera. The preview can't be used afterwards. */
    fun close()
}

interface ArPreviewFactory {
    /** Like [com.ma7moud.reality3d.scan.ScanEngineFactory.check]: whether ARCore is ready, installing it if asked. */
    fun check(activity: Activity, userRequestedInstall: Boolean): ScanSupport

    fun create(context: Context, model: PreviewModel): ArPreview
}
