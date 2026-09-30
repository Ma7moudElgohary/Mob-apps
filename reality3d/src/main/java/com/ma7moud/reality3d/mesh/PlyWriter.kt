package com.ma7moud.reality3d.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Binary PLY: every vertex with its position, normal and colour, plus the triangles, for point-cloud and
 * mesh tools such as CloudCompare and MeshLab. Scans keep their size in meters; other models get
 * [longestSideMeters] on their longest side, like the GLB.
 */
object PlyWriter {

    /** An ARGB photo, row by row, for colouring textured models by their texture coordinates. */
    class Photo(val width: Int, val height: Int, val argb: IntArray)

    fun write(mesh: Mesh3D, photo: Photo? = null, longestSideMeters: Float = 0.2f): ByteArray {
        val scale = if (mesh.realScale) 1f else longestSideMeters / max(mesh.longestSide, 1e-6f)
        val vertices = mesh.vertexCount
        val triangles = mesh.triangleCount
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\n")
            append("comment Reality3D ").append(if (mesh.realScale) "scan" else "model").append(", meters\n")
            append("element vertex ").append(vertices).append('\n')
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("element face ").append(triangles).append('\n')
            append("property list uchar int vertex_indices\nend_header\n")
        }.toByteArray(Charsets.US_ASCII)
        val out = ByteBuffer.allocate(header.size + vertices * 27 + triangles * 13).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        val colors = mesh.colors
        val uvs = mesh.uvs
        for (v in 0 until vertices) {
            out.putFloat(mesh.positions[v * 3] * scale)
            out.putFloat(mesh.positions[v * 3 + 1] * scale)
            out.putFloat(mesh.positions[v * 3 + 2] * scale)
            out.putFloat(mesh.normals[v * 3])
            out.putFloat(mesh.normals[v * 3 + 1])
            out.putFloat(mesh.normals[v * 3 + 2])
            val rgb = when {
                colors != null -> (channel(colors[v * 3]) shl 16) or (channel(colors[v * 3 + 1]) shl 8) or channel(colors[v * 3 + 2])
                photo != null && uvs != null -> {
                    val x = (uvs[v * 2].coerceIn(0f, 1f) * (photo.width - 1)).roundToInt()
                    val y = (uvs[v * 2 + 1].coerceIn(0f, 1f) * (photo.height - 1)).roundToInt()
                    photo.argb[y * photo.width + x]
                }
                else -> 0xB8B8B8
            }
            out.put(((rgb shr 16) and 0xFF).toByte())
            out.put(((rgb shr 8) and 0xFF).toByte())
            out.put((rgb and 0xFF).toByte())
        }
        for (t in 0 until triangles) {
            out.put(3.toByte())
            out.putInt(mesh.indices[t * 3])
            out.putInt(mesh.indices[t * 3 + 1])
            out.putInt(mesh.indices[t * 3 + 2])
        }
        return out.array()
    }

    private fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
