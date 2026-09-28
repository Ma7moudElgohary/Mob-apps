package com.ma7moud.reality3d.quality

import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshCleanup
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.roundToInt

data class ReconstructionQuality(
    val total: Int,
    val segmentation: Int,
    val topology: Int,
    val coverage: Int,
    val depthConfidence: Int,
    val notes: List<String>,
) {
    val label: String
        get() = when {
            total >= 85 -> "Excellent"
            total >= 70 -> "Good"
            total >= 50 -> "Fair"
            else -> "Needs more capture"
        }
}

object QualityScorer {
    fun quick(mask: SubjectMask, mesh: DepthMesh): ReconstructionQuality {
        val segmentationScore = (mask.meanConfidence() * 100f).roundToInt().coerceIn(0, 100)
        val boundaryRatio = MeshCleanup.boundaryEdgeRatio(mesh)
        val topologyScore = (100f * (1f - (boundaryRatio / 0.35f).coerceIn(0f, 1f))).roundToInt()
        val densityScore = when {
            mesh.triangleCount >= 12_000 -> 100
            mesh.triangleCount >= 6_000 -> 85
            mesh.triangleCount >= 2_000 -> 70
            mesh.triangleCount >= 500 -> 55
            else -> 30
        }
        val meshScore = ((topologyScore * 0.65f) + (densityScore * 0.35f)).roundToInt()
        val total = ((segmentationScore * 0.45f) + (meshScore * 0.55f)).roundToInt()
        val notes = buildList {
            if (segmentationScore < 70) add("Refine the subject mask around thin edges and gaps.")
            if (boundaryRatio > 0.18f) add("The mesh has many open boundaries; another view or Scan 360 will improve it.")
            if (mesh.triangleCount < 2_000) add("Geometry is sparse; improve lighting and subject detail.")
            if (isEmpty()) add("Mask and mesh topology look stable for a single-view preview.")
        }
        return ReconstructionQuality(total, segmentationScore, meshScore, 0, 0, notes)
    }

    fun scan(
        mesh: DepthMesh,
        coveragePercent: Int,
        depthConfidence: Float,
    ): ReconstructionQuality {
        val coverage = coveragePercent.coerceIn(0, 100)
        val depth = (depthConfidence.coerceIn(0f, 1f) * 100f).roundToInt()
        val boundaryRatio = MeshCleanup.boundaryEdgeRatio(mesh)
        val topology = (100f * (1f - (boundaryRatio / 0.24f).coerceIn(0f, 1f))).roundToInt()
        val total = (coverage * 0.45f + depth * 0.25f + topology * 0.30f).roundToInt()
        val notes = buildList {
            if (coverage < 85) add("Capture more angles until coverage reaches at least 85%.")
            if (depth < 55) add("Depth confidence is low; slow down and improve light/texture.")
            if (topology < 65) add("The fused surface still contains gaps; revisit missing sides and the top.")
            if (isEmpty()) add("Coverage, depth confidence, and topology are ready for export.")
        }
        return ReconstructionQuality(total, 0, topology, coverage, depth, notes)
    }
}
