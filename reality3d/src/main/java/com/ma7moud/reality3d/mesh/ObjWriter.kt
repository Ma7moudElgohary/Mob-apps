package com.ma7moud.reality3d.mesh

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Wavefront OBJ, zipped into one file for Blender, SketchUp, Unreal and the like: with an MTL and the
 * photo as texture for single-photo models, or with a colour per vertex (`v x y z r g b`) for scans.
 */
object ObjWriter {

    fun writeZip(mesh: Mesh3D, jpeg: ByteArray?, baseName: String, longestSideMeters: Float = 0.2f): ByteArray {
        val textured = jpeg != null && mesh.uvs != null
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            val material = if (textured) "$baseName.mtl" else null
            entry("$baseName.obj", objText(mesh, material, longestSideMeters).toByteArray(Charsets.US_ASCII))
            if (textured) {
                entry("$baseName.mtl", mtlText("$baseName.jpg").toByteArray(Charsets.US_ASCII))
                entry("$baseName.jpg", jpeg)
            }
        }
        return output.toByteArray()
    }

    internal fun mtlText(textureName: String): String =
        "newmtl photo\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1\nillum 1\nmap_Kd $textureName\n"

    internal fun objText(mesh: Mesh3D, mtlName: String?, longestSideMeters: Float): String {
        val scale = if (mesh.realScale) 1f else longestSideMeters / max(mesh.longestSide, 1e-6f)
        val uvs = mesh.uvs?.takeIf { mtlName != null }
        val colors = mesh.colors
        val text = StringBuilder(mesh.vertexCount * 110 + mesh.triangleCount * 40)
        text.append(if (mesh.realScale) "# Reality3D scan, meters\n" else "# Reality3D single-photo 3D model\n")
        if (mtlName != null) text.append("mtllib ").append(mtlName).append('\n')
        text.append("o Reality3D\n")
        for (i in 0 until mesh.vertexCount) {
            text.append("v ").fixed(mesh.positions[i * 3] * scale).append(' ')
                .fixed(mesh.positions[i * 3 + 1] * scale).append(' ')
                .fixed(mesh.positions[i * 3 + 2] * scale)
            if (colors != null) {
                text.append(' ').fixed(colors[i * 3]).append(' ').fixed(colors[i * 3 + 1]).append(' ').fixed(colors[i * 3 + 2])
            }
            text.append('\n')
        }
        if (uvs != null) {
            // OBJ texture coordinates start at the bottom-left of the image.
            for (i in 0 until mesh.vertexCount) {
                text.append("vt ").fixed(uvs[i * 2]).append(' ').fixed(1f - uvs[i * 2 + 1]).append('\n')
            }
        }
        for (i in 0 until mesh.vertexCount) {
            text.append("vn ").fixed(mesh.normals[i * 3]).append(' ')
                .fixed(mesh.normals[i * 3 + 1]).append(' ')
                .fixed(mesh.normals[i * 3 + 2]).append('\n')
        }
        if (mtlName != null) text.append("usemtl photo\n")
        text.append("s 1\n")
        for (t in 0 until mesh.triangleCount) {
            text.append('f')
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] + 1
                text.append(' ').append(v).append('/')
                if (uvs != null) text.append(v)
                text.append('/').append(v)
            }
            text.append('\n')
        }
        return text.toString()
    }

    /** Locale-independent fixed-point number with five decimals. */
    private fun StringBuilder.fixed(value: Float): StringBuilder {
        if (!value.isFinite()) return append('0')
        val scaled = (abs(value.toDouble()) * 100_000.0).roundToLong()
        if (value < 0f && scaled != 0L) append('-')
        append(scaled / 100_000).append('.')
        val fraction = (scaled % 100_000).toString()
        repeat(5 - fraction.length) { append('0') }
        return append(fraction)
    }
}
