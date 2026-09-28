package com.ma7moud.reality3d.mesh

data class DepthMesh(
    val positions: FloatArray,
    val texCoords: FloatArray,
    val indices: IntArray,
    val normals: FloatArray = FloatArray(0),
    val colors: FloatArray? = null,
    val columns: Int = 0,
    val rows: Int = 0,
    val unitsToMeters: Float? = null,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val isMetric: Boolean get() = unitsToMeters != null
}
