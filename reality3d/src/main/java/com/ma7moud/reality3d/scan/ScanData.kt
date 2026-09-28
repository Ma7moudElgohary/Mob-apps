package com.ma7moud.reality3d.scan

import kotlin.math.sqrt

/** Pinhole intrinsics in pixels for an image of [width] × [height], row 0 at the top. */
class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int) {
    fun scaledTo(newWidth: Int, newHeight: Int): Intrinsics {
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, newWidth, newHeight)
    }
}

/**
 * A camera pose as ARCore reports it: a column-major 4×4 camera-to-world matrix for an OpenGL-style
 * camera whose +X and +Y follow the image's right and up, looking down -Z. World +Y is up; units are meters.
 */
class CameraPose(val matrix: FloatArray) {
    init {
        require(matrix.size == 16)
    }

    val x: Float get() = matrix[12]
    val y: Float get() = matrix[13]
    val z: Float get() = matrix[14]

    /**
     * Projects a world point into an image with [intrinsics]. Writes (column, row, depth in front of the
     * camera) into [out] and returns false when the point is behind the camera.
     */
    fun project(px: Float, py: Float, pz: Float, intrinsics: Intrinsics, out: FloatArray): Boolean {
        val dx = px - matrix[12]
        val dy = py - matrix[13]
        val dz = pz - matrix[14]
        val cx = matrix[0] * dx + matrix[1] * dy + matrix[2] * dz
        val cy = matrix[4] * dx + matrix[5] * dy + matrix[6] * dz
        val depth = -(matrix[8] * dx + matrix[9] * dy + matrix[10] * dz)
        if (depth <= 1e-6f) return false
        out[0] = intrinsics.fx * cx / depth + intrinsics.cx
        out[1] = intrinsics.cy - intrinsics.fy * cy / depth
        out[2] = depth
        return true
    }

    /** Unit vector from [px], [py], [pz] towards the camera. */
    fun directionFrom(px: Float, py: Float, pz: Float): FloatArray {
        val dx = x - px
        val dy = y - py
        val dz = z - pz
        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-9f)
        return floatArrayOf(dx / length, dy / length, dz / length)
    }
}

/**
 * One ARCore depth map: millimetres per pixel (0 = unknown), with the camera it was seen from. Raw depth
 * comes with ARCore's [confidence] per pixel (0..255) and is sparse; smoothed depth has none.
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val intrinsics: Intrinsics,
    val pose: CameraPose,
    val confidence: ByteArray? = null,
) {
    init {
        require(depthMm.size == width * height)
        require(confidence == null || confidence.size == width * height)
    }
}

/** A photo kept during the scan, for colouring the model and for photogrammetry export. */
class Keyframe(val jpeg: ByteArray, val width: Int, val height: Int, val intrinsics: Intrinsics, val pose: CameraPose)

/** A decoded keyframe photo in ARGB pixels, with intrinsics matching its size. */
class KeyframeImage(val width: Int, val height: Int, val argb: IntArray, val intrinsics: Intrinsics, val pose: CameraPose)

/**
 * The region being scanned: a cube standing on the supporting surface.
 * [floorY] is the table height, or null when no table was found.
 */
class ScanBox(val centerX: Float, val bottomY: Float, val centerZ: Float, val size: Float, val floorY: Float?) {
    val minX: Float get() = centerX - size / 2
    val minZ: Float get() = centerZ - size / 2
    val centerY: Float get() = bottomY + size / 2

    companion object {
        /** The box starts this far above the table, so the table itself is never part of the model. */
        const val FLOOR_MARGIN = 0.004f

        /**
         * Depth points less than this above the table count as the table (its plane is only known to
         * about a centimetre): they clear the space in front of them but add no surface.
         */
        const val TABLE_BAND = 0.015f

        /**
         * A box for an object whose front surface was tapped at ([hitX], [hitY], [hitZ]) from a camera at
         * ([cameraX], _, [cameraZ]); the box is pushed back so the object's middle sits near its centre.
         */
        fun around(hitX: Float, hitY: Float, hitZ: Float, cameraX: Float, cameraZ: Float, size: Float, floorY: Float?): ScanBox {
            var dx = hitX - cameraX
            var dz = hitZ - cameraZ
            val length = sqrt(dx * dx + dz * dz)
            if (length > 1e-4f) {
                dx /= length
                dz /= length
            } else {
                dx = 0f
                dz = 0f
            }
            val push = size * 0.3f
            val bottom = if (floorY != null) floorY + FLOOR_MARGIN else hitY - size / 2
            return ScanBox(hitX + dx * push, bottom, hitZ + dz * push, size, floorY)
        }
    }
}
