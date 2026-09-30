package com.ma7moud.reality3d.mesh

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow

/**
 * Binary glTF 2.0 (.glb): one file with the mesh, normals, material and either the photo texture or
 * per-vertex colours. Opens in Windows 3D Viewer, Blender, online glTF viewers and most 3D apps.
 */
object GlbWriter {

    private const val MAGIC = 0x46546C67 // "glTF"
    private const val VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val FLOAT = 5126
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    /**
     * The base colour image, already encoded. With [alphaMask] its alpha channel cuts the surface out
     * (glTF alpha mode MASK at 0.5), for photos whose background is transparent.
     */
    class Texture(val bytes: ByteArray, val mimeType: String, val alphaMask: Boolean = false)

    /**
     * @param jpeg the photo as JPEG, used as the base colour texture when the mesh has UVs.
     * @param longestSideMeters size given to a normalised model's longest side (glTF units are meters);
     *   real-scale scans keep their measured size.
     */
    fun write(mesh: Mesh3D, jpeg: ByteArray?, longestSideMeters: Float = 0.2f, name: String = "Reality3D"): ByteArray =
        write(mesh, jpeg?.let { Texture(it, "image/jpeg") }, longestSideMeters, name)

    fun write(mesh: Mesh3D, texture: Texture?, longestSideMeters: Float = 0.2f, name: String = "Reality3D"): ByteArray {
        val scale = if (mesh.realScale) 1f else longestSideMeters / max(mesh.longestSide, 1e-6f)
        val uvs = mesh.uvs
        val textured = texture != null && uvs != null
        val colors = mesh.colors
        val vertices = mesh.vertexCount

        class View(val offset: Int, val length: Int, val target: Int?)
        val views = ArrayList<View>()
        var cursor = 0
        fun addView(length: Int, target: Int?): Int {
            views += View(cursor, length, target)
            cursor = align4(cursor + length)
            return views.size - 1
        }
        val positionView = addView(vertices * 12, ARRAY_BUFFER)
        val normalView = addView(vertices * 12, ARRAY_BUFFER)
        val uvView = if (textured) addView(vertices * 8, ARRAY_BUFFER) else -1
        val colorView = if (colors != null) addView(vertices * 12, ARRAY_BUFFER) else -1
        val indexView = addView(mesh.indices.size * 4, ELEMENT_ARRAY_BUFFER)
        val imageView = if (textured) addView(texture.bytes.size, null) else -1
        val binLength = cursor

        val bin = ByteBuffer.allocate(binLength).order(ByteOrder.LITTLE_ENDIAN)
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        bin.seek(views[positionView].offset)
        for (i in mesh.positions.indices) {
            val value = mesh.positions[i] * scale
            val axis = i % 3
            if (value < min[axis]) min[axis] = value
            if (value > max[axis]) max[axis] = value
            bin.putFloat(value)
        }
        bin.seek(views[normalView].offset)
        for (value in mesh.normals) bin.putFloat(value)
        if (textured) {
            bin.seek(views[uvView].offset)
            for (value in uvs) bin.putFloat(value)
        }
        if (colors != null) {
            // glTF vertex colours are linear; the scan colours come from sRGB photos.
            bin.seek(views[colorView].offset)
            for (value in colors) bin.putFloat(srgbToLinear(value))
        }
        bin.seek(views[indexView].offset)
        for (index in mesh.indices) bin.putInt(index)
        if (textured) {
            bin.seek(views[imageView].offset)
            bin.put(texture.bytes)
        }

        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Reality3D\"},")
            append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
            append("\"nodes\":[{\"mesh\":0,\"name\":").append(quote(name)).append("}],")
            append("\"meshes\":[{\"name\":").append(quote(name)).append(",\"primitives\":[{\"attributes\":{")
            append("\"POSITION\":0,\"NORMAL\":1")
            var accessor = 2
            if (textured) append(",\"TEXCOORD_0\":").append(accessor++)
            if (colors != null) append(",\"COLOR_0\":").append(accessor++)
            val indexAccessor = accessor
            append("},\"indices\":").append(indexAccessor).append(",\"material\":0,\"mode\":4}]}],")
            append("\"materials\":[{\"name\":").append(if (textured) "\"Photo\"" else "\"Scan\"").append(",\"pbrMetallicRoughness\":{")
            if (textured) append("\"baseColorTexture\":{\"index\":0},")
            append("\"metallicFactor\":0.0,\"roughnessFactor\":0.9},")
            if (textured && texture.alphaMask) append("\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5,")
            append("\"doubleSided\":").append(!mesh.solid).append("}],")
            if (textured) {
                append("\"textures\":[{\"sampler\":0,\"source\":0}],")
                append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
                append("\"images\":[{\"bufferView\":").append(imageView).append(",\"mimeType\":").append(quote(texture.mimeType)).append("}],")
            }
            append("\"accessors\":[")
            append("{\"bufferView\":").append(positionView).append(",\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC3\",")
            append("\"min\":[").append(number(min[0])).append(',').append(number(min[1])).append(',').append(number(min[2])).append("],")
            append("\"max\":[").append(number(max[0])).append(',').append(number(max[1])).append(',').append(number(max[2])).append("]},")
            append("{\"bufferView\":").append(normalView).append(",\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC3\"},")
            if (textured) append("{\"bufferView\":").append(uvView).append(",\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC2\"},")
            if (colors != null) append("{\"bufferView\":").append(colorView).append(",\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC3\"},")
            append("{\"bufferView\":").append(indexView).append(",\"componentType\":$UNSIGNED_INT,\"count\":${mesh.indices.size},\"type\":\"SCALAR\"}],")
            append("\"bufferViews\":[")
            views.forEachIndexed { index, view ->
                if (index > 0) append(',')
                append("{\"buffer\":0,\"byteOffset\":").append(view.offset).append(",\"byteLength\":").append(view.length)
                view.target?.let { append(",\"target\":").append(it) }
                append('}')
            }
            append("],\"buffers\":[{\"byteLength\":$binLength}]}")
        }
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonLength = align4(jsonBytes.size)

        val total = 12 + 8 + jsonLength + 8 + binLength
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(VERSION).putInt(total)
        out.putInt(jsonLength).putInt(CHUNK_JSON).put(jsonBytes)
        repeat(jsonLength - jsonBytes.size) { out.put(' '.code.toByte()) }
        out.putInt(binLength).putInt(CHUNK_BIN).put(bin.array())
        return out.array()
    }

    // Through Buffer: ByteBuffer.position(int) only returns ByteBuffer from Android 14 on.
    private fun ByteBuffer.seek(offset: Int) {
        (this as Buffer).position(offset)
    }

    internal fun srgbToLinear(value: Float): Float {
        val c = value.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun align4(value: Int) = (value + 3) and 3.inv()

    private fun number(value: Float): String = if (value.isFinite()) value.toString() else "0.0"

    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
