package com.ma7moud.reality3d.scan

/**
 * Coverage that represents a reconstruction-ready viewpoint rather than only a captured photo.
 *
 * A cell becomes covered only after Reality3D has both:
 * 1. an accepted camera keyframe for texture/photogrammetry, and
 * 2. a depth frame from that direction that actually passed the TSDF gate and was fused.
 *
 * Photo and depth may arrive in either order because depth fusion runs asynchronously.
 */
internal class QualifiedCoverageTracker {
    private val photos = BooleanArray(CoverageTracker.CELLS)
    private val depth = BooleanArray(CoverageTracker.CELLS)
    private val qualified = CoverageTracker()

    val covered: BooleanArray get() = qualified.covered
    val fraction: Float get() = qualified.fraction

    val photoCells: Int get() = photos.count { it }
    val depthCells: Int get() = depth.count { it }
    val qualifiedCells: Int get() = qualified.covered.count { it }

    fun markPhoto(cell: Int): Boolean {
        photos[cell] = true
        return qualify(cell)
    }

    fun markDepth(cell: Int): Boolean {
        depth[cell] = true
        return qualify(cell)
    }

    fun nextStep(): CoverageTracker.Step = qualified.nextStep()

    fun reset() {
        photos.fill(false)
        depth.fill(false)
        qualified.reset()
    }

    private fun qualify(cell: Int): Boolean =
        if (photos[cell] && depth[cell]) qualified.mark(cell) else false
}
