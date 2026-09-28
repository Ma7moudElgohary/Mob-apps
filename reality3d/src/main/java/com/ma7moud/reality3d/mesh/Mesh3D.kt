package com.ma7moud.reality3d.mesh

import kotlin.math.max

/**
 * A triangle mesh with +Y up and +Z towards the front of the object.
 *
 * Models made from one photo are centred on the origin with the subject's longest side 1 unit long and
 * are textured with the photo ([uvs]). Scans are in real-world meters ([realScale]) with their base at
 * y = 0 and carry a colour per vertex ([colors]).
 */
class Mesh3D(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    /** Unit normal per vertex. */
    val normals: FloatArray,
    /** Photo coordinates per vertex, glTF convention: (0, 0) is the photo's top-left corner. */
    val uvs: FloatArray?,
    /** Counter-clockwise triangles (seen from outside). */
    val indices: IntArray,
    /** True when the mesh is closed (every edge shared by exactly two triangles). */
    val solid: Boolean,
    /** False when no subject could be separated and the whole photo was used. */
    val subjectIsolated: Boolean,
    /** sRGB colour (0..1) per vertex, for scans. */
    val colors: FloatArray? = null,
    /** True when positions are real-world meters rather than a normalised size. */
    val realScale: Boolean = false,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** Axis-aligned bounds as [minX, minY, minZ, maxX, maxY, maxZ]. */
    val bounds: FloatArray by lazy {
        val b = floatArrayOf(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )
        for (i in positions.indices) {
            val axis = i % 3
            val value = positions[i]
            if (value < b[axis]) b[axis] = value
            if (value > b[axis + 3]) b[axis + 3] = value
        }
        if (positions.isEmpty()) b.fill(0f)
        b
    }

    val longestSide: Float get() = bounds.let { max(it[3] - it[0], max(it[4] - it[1], it[5] - it[2])) }

    /** Width (x), height (y) and depth (z) of the bounding box. */
    val size: FloatArray get() = bounds.let { floatArrayOf(it[3] - it[0], it[4] - it[1], it[5] - it[2]) }
}
