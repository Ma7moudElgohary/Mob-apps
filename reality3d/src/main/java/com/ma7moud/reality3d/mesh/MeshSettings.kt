package com.ma7moud.reality3d.mesh

/** Cross-section of the inflated subject. */
enum class ShapeProfile { ROUND, BOXY }

/** Grid resolution laid over the photo (cells along its longer side). */
enum class MeshDetail(val gridCells: Int) { STANDARD(144), HIGH(224) }

data class MeshSettings(
    /** Adds a back surface joined to the front along the silhouette, giving a closed, printable model. */
    val solid: Boolean = true,
    val profile: ShapeProfile = ShapeProfile.ROUND,
    /** Front-to-back thickness as a fraction of the subject's local width (1 = as thick as it is wide). */
    val thickness: Float = DEFAULT_THICKNESS,
    /** How strongly the estimated depth bends and details the model (0 = silhouette only). */
    val depthStrength: Float = 1f,
    val detail: MeshDetail = MeshDetail.STANDARD,
) {
    companion object {
        const val DEFAULT_THICKNESS = 0.8f
        const val MIN_THICKNESS = 0.05f
        const val MAX_THICKNESS = 1.2f
        const val MAX_DEPTH_STRENGTH = 2.5f
    }
}
