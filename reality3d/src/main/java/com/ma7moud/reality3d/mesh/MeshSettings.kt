package com.ma7moud.reality3d.mesh

/** Cross-section of the inflated subject. */
enum class ShapeProfile { ROUND, BOXY }

/**
 * How finely the model follows the photo. Both levels start from the same fine grid; standard then
 * simplifies it to about the triangle count of a [budgetCells]-cell grid, keeping triangles where the
 * shape needs them rather than spreading them evenly.
 */
enum class MeshDetail(val gridCells: Int, val budgetCells: Int?) {
    STANDARD(224, 144),
    HIGH(224, null),
}

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
