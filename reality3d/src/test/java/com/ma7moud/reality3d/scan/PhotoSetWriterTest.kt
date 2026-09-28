package com.ma7moud.reality3d.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class PhotoSetWriterTest {

    private val intrinsics = Intrinsics(1400f, 1400f, 960f, 540f, 1920, 1080)

    @Test
    fun identityCameraIsFlippedIntoOpenCv() {
        // ARCore camera at the origin looking down -Z = OpenCV camera rotated 180° about X.
        val (q, t) = PhotoSetWriter.worldToCameraOpenCv(CameraPose(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)))
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f), q, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), t, 1e-6f)
    }

    @Test
    fun poseProjectsPointsLikeOpenCv() {
        // Whatever the pose, R·p + t must put a point seen by the camera in front of it (+Z in OpenCV).
        val pose = SyntheticScan.lookAt(0.3f, 0.2f, 0.4f, 0f, 0.05f, 0f)
        val (q, t) = PhotoSetWriter.worldToCameraOpenCv(pose)
        val (w, x, y, z) = q.toList()
        val r = arrayOf(
            floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            floatArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            floatArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
        )
        val p = floatArrayOf(0f, 0.05f, 0f)
        val c = FloatArray(3) { row -> r[row][0] * p[0] + r[row][1] * p[1] + r[row][2] * p[2] + t[row] }
        val projected = FloatArray(3)
        pose.project(p[0], p[1], p[2], intrinsics, projected)
        assertEquals(projected[2], c[2], 1e-4f)
        // OpenCV pixel = f·x/z + c, the same pixel ARCore's projection gives.
        assertEquals(projected[0], intrinsics.fx * c[0] / c[2] + intrinsics.cx, 1e-2f)
        assertEquals(projected[1], intrinsics.fy * c[1] / c[2] + intrinsics.cy, 1e-2f)
    }

    @Test
    fun zipHoldsPhotosAndPoses() {
        val frames = List(3) { index ->
            Keyframe(ByteArray(10) { (index * 10 + it).toByte() }, 1920, 1080, intrinsics, SyntheticScan.lookAt(0.4f * index, 0.3f, 0.4f, 0f, 0f, 0f))
        }
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(PhotoSetWriter.write(frames))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(
            listOf("images/frame_000.jpg", "images/frame_001.jpg", "images/frame_002.jpg", "cameras.json", "colmap/cameras.txt", "colmap/images.txt", "colmap/points3D.txt", "README.txt"),
            entries.keys.toList(),
        )
        assertArrayEquals(frames[1].jpeg, entries["images/frame_001.jpg"])
        val json = String(entries["cameras.json"]!!)
        assertEquals(3, Regex("\"cameraToWorld\"").findAll(json).count())
        assertTrue(json.contains("\"fx\": 1400.000000"))
        val images = String(entries["colmap/images.txt"]!!).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(3, images.size)
        assertTrue(images[2].endsWith(" 3 frame_002.jpg"))
        val cameras = String(entries["colmap/cameras.txt"]!!).lines().filter { it.startsWith("1 ") }
        assertEquals(listOf("1 PINHOLE 1920 1080 1400.000000 1400.000000 960.000000 540.000000"), cameras)
    }
}
