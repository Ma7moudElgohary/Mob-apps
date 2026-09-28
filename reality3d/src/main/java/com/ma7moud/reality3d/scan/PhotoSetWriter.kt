package com.ma7moud.reality3d.scan

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/**
 * The scan photos with their camera positions, zipped for photogrammetry software on a computer:
 * the images, a JSON file in ARCore's convention and COLMAP text files in OpenCV's.
 */
object PhotoSetWriter {

    fun write(keyframes: List<Keyframe>): ByteArray {
        val output = ByteArrayOutputStream(keyframes.sumOf { it.jpeg.size + 128 } + 64 * 1024)
        ZipOutputStream(output).use { zip ->
            // JPEGs are stored as they are: compressing them again only costs time.
            fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            keyframes.forEachIndexed { index, frame -> entry("images/${imageName(index)}", frame.jpeg, stored = true) }
            entry("cameras.json", camerasJson(keyframes).toByteArray(Charsets.UTF_8))
            entry("colmap/cameras.txt", colmapCameras(keyframes).toByteArray(Charsets.US_ASCII))
            entry("colmap/images.txt", colmapImages(keyframes).toByteArray(Charsets.US_ASCII))
            entry("colmap/points3D.txt", "# Empty: triangulate with the known poses.\n".toByteArray(Charsets.US_ASCII))
            entry("README.txt", README.toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    fun imageName(index: Int) = String.format(Locale.ROOT, "frame_%03d.jpg", index)

    internal fun camerasJson(keyframes: List<Keyframe>): String = buildString {
        append("{\n  \"generator\": \"Reality3D\",\n  \"units\": \"meters\",\n")
        append("  \"convention\": \"cameraToWorld is a column-major 4x4 matrix (ARCore/OpenGL): +X right, +Y up, camera looks down -Z; world +Y is up\",\n")
        append("  \"frames\": [\n")
        keyframes.forEachIndexed { index, frame ->
            val k = frame.intrinsics
            append("    {\"file\": \"images/").append(imageName(index)).append("\", ")
            append("\"width\": ").append(frame.width).append(", \"height\": ").append(frame.height).append(", ")
            append("\"fx\": ").append(number(k.fx)).append(", \"fy\": ").append(number(k.fy)).append(", ")
            append("\"cx\": ").append(number(k.cx)).append(", \"cy\": ").append(number(k.cy)).append(", ")
            append("\"cameraToWorld\": [").append(frame.pose.matrix.joinToString(", ") { number(it) }).append("]}")
            append(if (index < keyframes.size - 1) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    internal fun colmapCameras(keyframes: List<Keyframe>): String = buildString {
        append("# Camera list with one line of data per camera:\n#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
        keyframes.forEachIndexed { index, frame ->
            val k = frame.intrinsics
            append(index + 1).append(" PINHOLE ").append(frame.width).append(' ').append(frame.height)
            append(' ').append(number(k.fx)).append(' ').append(number(k.fy))
            append(' ').append(number(k.cx)).append(' ').append(number(k.cy)).append('\n')
        }
    }

    internal fun colmapImages(keyframes: List<Keyframe>): String = buildString {
        append("# Image list with two lines of data per image:\n")
        append("#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n#   POINTS2D[] as (X, Y, POINT3D_ID)\n")
        keyframes.forEachIndexed { index, frame ->
            val (q, t) = worldToCameraOpenCv(frame.pose)
            append(index + 1)
            for (value in q) append(' ').append(number(value))
            for (value in t) append(' ').append(number(value))
            append(' ').append(index + 1).append(' ').append(imageName(index)).append("\n\n")
        }
    }

    /**
     * COLMAP pose: world-to-camera rotation as a unit quaternion (w, x, y, z) and translation, for an
     * OpenCV camera (+Y down, looking down +Z), i.e. the ARCore camera with its Y and Z axes flipped.
     */
    internal fun worldToCameraOpenCv(pose: CameraPose): Pair<FloatArray, FloatArray> {
        val m = pose.matrix
        // Rows of the world-to-camera rotation are the camera axes in world space (columns of the pose).
        val r = arrayOf(
            floatArrayOf(m[0], m[1], m[2]),
            floatArrayOf(-m[4], -m[5], -m[6]),
            floatArrayOf(-m[8], -m[9], -m[10]),
        )
        val t = FloatArray(3) { row -> -(r[row][0] * m[12] + r[row][1] * m[13] + r[row][2] * m[14]) }
        return quaternion(r) to t
    }

    internal fun quaternion(r: Array<FloatArray>): FloatArray {
        val trace = r[0][0] + r[1][1] + r[2][2]
        val q = when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                floatArrayOf(s / 4f, (r[2][1] - r[1][2]) / s, (r[0][2] - r[2][0]) / s, (r[1][0] - r[0][1]) / s)
            }
            r[0][0] > r[1][1] && r[0][0] > r[2][2] -> {
                val s = sqrt(1f + r[0][0] - r[1][1] - r[2][2]) * 2f
                floatArrayOf((r[2][1] - r[1][2]) / s, s / 4f, (r[0][1] + r[1][0]) / s, (r[0][2] + r[2][0]) / s)
            }
            r[1][1] > r[2][2] -> {
                val s = sqrt(1f + r[1][1] - r[0][0] - r[2][2]) * 2f
                floatArrayOf((r[0][2] - r[2][0]) / s, (r[0][1] + r[1][0]) / s, s / 4f, (r[1][2] + r[2][1]) / s)
            }
            else -> {
                val s = sqrt(1f + r[2][2] - r[0][0] - r[1][1]) * 2f
                floatArrayOf((r[1][0] - r[0][1]) / s, (r[0][2] + r[2][0]) / s, (r[1][2] + r[2][1]) / s, s / 4f)
            }
        }
        // Keep w positive so the same rotation always prints the same way.
        return if (q[0] < 0f) FloatArray(4) { -q[it] } else q
    }

    private fun number(value: Float) = String.format(Locale.ROOT, "%.6f", value)

    private val README = """
        Reality3D scan photos
        =====================

        images/        The photos taken while you walked around the object.
        cameras.json   Where each photo was taken (ARCore tracking, in meters) and the lens intrinsics.
        colmap/        The same poses as COLMAP text files (world-to-camera, OpenCV camera).

        For the most detailed model, open the images folder in photogrammetry software such as
        RealityScan, Meshroom or Agisoft Metashape. They work out the camera positions themselves.

        COLMAP can use the known poses, which also keeps the real-world scale:
          colmap feature_extractor --database_path db.db --image_path images
          colmap exhaustive_matcher --database_path db.db
          colmap point_triangulator --database_path db.db --image_path images --input_path colmap --output_path sparse
        (Import the cameras from colmap/cameras.txt into the database first, or let COLMAP estimate them.)
    """.trimIndent() + "\n"
}
