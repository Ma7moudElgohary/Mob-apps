package com.ma7moud.reality3d.ui

import com.ma7moud.reality3d.mesh.Mesh3D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How the viewer colours the model. */
enum class Shading {
    /** The photo, or a scan's colours. */
    SURFACE,
    CLAY,

    /** Clay with the triangle edges drawn over it. */
    WIREFRAME,

    /** Surface directions as colours: red is +x, green +y (up), blue +z (front). */
    NORMALS,

    /** Distance from the camera: red is near, blue is far. */
    DEPTH,
}

/** Straight views of the model: yaw turns it about its vertical axis, pitch tips it towards the camera. */
enum class ViewPreset(val label: String, val yaw: Float, val pitch: Float) {
    FRONT("Front", 0f, 0f),
    BACK("Back", 180f, 0f),
    LEFT("Left", 90f, 0f),
    RIGHT("Right", -90f, 0f),
    TOP("Top", 0f, 89f),
}

data class ViewerOptions(
    val shading: Shading = Shading.SURFACE,
    val autoRotate: Boolean = false,
    /** Where the key light comes from, in degrees around the model; 0 is from the camera. */
    val lightAngle: Float = DEFAULT_LIGHT_ANGLE,
    /** Soft light from a sky above and the ground below, with reflections, instead of studio lamps. */
    val environment: Boolean = true,
) {
    companion object {
        const val DEFAULT_LIGHT_ANGLE = 27f
    }
}

/**
 * The straight-on orthographic view used to compare a model with its photo. Single-photo models map x
 * and y linearly onto the photo, so in this view the photo lines up with the model exactly.
 */
internal object FrontView {

    private const val MARGIN = 1.08f

    /** Screen pixels per model unit when the model's front fills a [width] × [height] view. */
    fun pixelsPerUnit(bounds: FloatArray, width: Int, height: Int): Float {
        val w = max(bounds[3] - bounds[0], 1e-6f)
        val h = max(bounds[4] - bounds[1], 1e-6f)
        return min(width / (w * MARGIN), height / (h * MARGIN))
    }

    /** Where the model's front bounds land in the view: left, top, right, bottom in pixels. */
    fun rect(bounds: FloatArray, width: Int, height: Int): FloatArray {
        val scale = pixelsPerUnit(bounds, width, height)
        val w = (bounds[3] - bounds[0]) * scale
        val h = (bounds[4] - bounds[1]) * scale
        return floatArrayOf((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
}

/** The part of the photo a textured model uses (left, top, right, bottom, 0..1), or null without a texture. */
internal fun uvBounds(mesh: Mesh3D): FloatArray? {
    val uvs = mesh.uvs ?: return null
    if (uvs.isEmpty()) return null
    val box = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    for (i in uvs.indices step 2) {
        box[0] = min(box[0], uvs[i])
        box[1] = min(box[1], uvs[i + 1])
        box[2] = max(box[2], uvs[i])
        box[3] = max(box[3], uvs[i + 1])
    }
    return box
}

/** Finds where a ray from the camera through a point on the screen meets the model. */
internal object MeshPicker {

    /**
     * The nearest point of [mesh] under normalised device coordinates ([ndcX], [ndcY]) (-1..1, y up),
     * given the inverse of the model-view-projection matrix (column-major, as OpenGL uses); null when
     * the ray misses the model.
     */
    fun pick(mesh: Mesh3D, inverseMvp: FloatArray, ndcX: Float, ndcY: Float): FloatArray? {
        val near = unproject(inverseMvp, ndcX, ndcY, -1f) ?: return null
        val far = unproject(inverseMvp, ndcX, ndcY, 1f) ?: return null
        val direction = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        val t = intersect(mesh.positions, mesh.indices, near, direction) ?: return null
        return floatArrayOf(near[0] + direction[0] * t, near[1] + direction[1] * t, near[2] + direction[2] * t)
    }

    private fun unproject(m: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (abs(w) < 1e-12f) return null
        return floatArrayOf(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[5] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w,
        )
    }

    /** Möller–Trumbore against every triangle, both sides: the smallest t ≥ 0 along the ray, or null. */
    internal fun intersect(positions: FloatArray, indices: IntArray, origin: FloatArray, direction: FloatArray): Float? {
        var best = Float.POSITIVE_INFINITY
        val (ox, oy, oz) = Triple(origin[0], origin[1], origin[2])
        val (dx, dy, dz) = Triple(direction[0], direction[1], direction[2])
        for (t in indices.indices step 3) {
            val a = indices[t] * 3
            val b = indices[t + 1] * 3
            val c = indices[t + 2] * 3
            val e1x = positions[b] - positions[a]
            val e1y = positions[b + 1] - positions[a + 1]
            val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]
            val e2y = positions[c + 1] - positions[a + 1]
            val e2z = positions[c + 2] - positions[a + 2]
            val px = dy * e2z - dz * e2y
            val py = dz * e2x - dx * e2z
            val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val sx = ox - positions[a]
            val sy = oy - positions[a + 1]
            val sz = oz - positions[a + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (dx * qx + dy * qy + dz * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val distance = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (distance >= 0f && distance < best) best = distance
        }
        return if (best.isFinite()) best else null
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/** A length in meters as millimetres, centimetres or meters, whichever reads best. */
internal fun formatLength(meters: Float): String = when {
    meters < 0.01f -> String.format(Locale.US, "%.1f mm", meters * 1000)
    meters < 1f -> String.format(Locale.US, "%.1f cm", meters * 100)
    else -> String.format(Locale.US, "%.2f m", meters)
}

/** The model's width × height × depth, in the unit that reads best for its longest side. */
internal fun formatSize(size: FloatArray): String {
    val longest = max(size[0], max(size[1], size[2]))
    val (factor, unit) = when {
        longest < 0.01f -> 1000f to "mm"
        longest < 1f -> 100f to "cm"
        else -> 1f to "m"
    }
    val decimals = if (unit == "m") "%.2f" else "%.1f"
    return size.joinToString(" × ") { String.format(Locale.US, decimals, it * factor) } + " $unit"
}
