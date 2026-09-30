package com.ma7moud.reality3d.scan

import kotlin.math.abs

/**
 * Estimates how much of the reconstructed surface is backed by real TSDF measurements instead of inferred fill.
 *
 * [TsdfVolume.extractionField] stores measured TSDF samples as continuous values around zero, while inferred
 * solid/empty voxels are exactly -1/+1. Surface crossings anchored by at least one non-saturated endpoint are
 * therefore directly supported by depth. The support-plane band is ignored because a tabletop object's underside
 * is intentionally completed rather than photographed.
 */
internal object SurfaceCompleteness {

    /** Enough real surface for an early local preview without relying almost entirely on inferred fill. */
    const val MIN_BUILD = 0.25f

    /** Measured-surface level required before directional coverage can call a scan "enough". */
    const val GOOD = 0.45f

    private const val MEASURED_FIELD_LIMIT = 0.95f
    private const val MIN_SURFACE_CROSSINGS = 24

    fun measure(volume: TsdfVolume): Float? {
        val n = volume.resolution
        val p = n + 2
        val field = volume.extractionField()
        val spacing = volume.voxelSize
        val firstY = volume.originY - spacing / 2f
        val supportTop = volume.box.floorY?.plus(ScanBox.TABLE_BAND)
        var measured = 0
        var total = 0

        fun value(x: Int, y: Int, z: Int): Float = field[(z * p + y) * p + x]

        fun count(a: Float, b: Float, yA: Float, yB: Float) {
            if ((a < 0f) == (b < 0f)) return
            val denominator = a - b
            val t = if (abs(denominator) > 1e-6f) a / denominator else 0.5f
            val crossingY = yA + (yB - yA) * t.coerceIn(0f, 1f)
            if (supportTop != null && crossingY <= supportTop) return
            total++
            if (abs(a) < MEASURED_FIELD_LIMIT || abs(b) < MEASURED_FIELD_LIMIT) measured++
        }

        for (z in 0 until p) {
            for (y in 0 until p) {
                val worldY = firstY + y * spacing
                for (x in 0 until p) {
                    val a = value(x, y, z)
                    if (x + 1 < p) count(a, value(x + 1, y, z), worldY, worldY)
                    if (y + 1 < p) count(a, value(x, y + 1, z), worldY, worldY + spacing)
                    if (z + 1 < p) count(a, value(x, y, z + 1), worldY, worldY)
                }
            }
        }
        return if (total < MIN_SURFACE_CROSSINGS) null else measured.toFloat() / total
    }
}
