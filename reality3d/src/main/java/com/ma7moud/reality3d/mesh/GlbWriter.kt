package com.ma7moud.reality3d.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

/**
 * Binary glTF 2.0 (.glb): one file with the mesh, normals, photo texture and material. Opens in
 * Windows 3D Viewer, Blender, online glTF viewers and most 3D apps.
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
     * @param jpeg the photo as JPEG, used as the base colour texture; null for an untextured model.
     * @param longestSideMeters real-world size given to the model's longest side (glTF units are meters).
     */
    fun write(mesh: Mesh3D, jpeg: ByteArray?, longestSideMeters: Float = 0.2f, name: String = "Reality3D"): ByteArray {
        val scale = longestSideMeters / max(mesh.longestSide, 1e-6f)
        val vertices = mesh.vertexCount
        val positionsOffset = 0
        val normalsOffset = positionsOffset + vertices * 12
        val uvsOffset = normalsOffset + vertices * 12
        val indicesOffset = uvsOffset + vertices * 8
        val imageOffset = indicesOffset + mesh.indices.size * 4
        val imageLength = jpeg?.size ?: 0
        val binLength = align4(imageOffset + imageLength)

        val bin = ByteBuffer.allocate(binLength).order(ByteOrder.LITTLE_ENDIAN)
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (i in mesh.positions.indices) {
            val value = mesh.positions[i] * scale
            val axis = i % 3
            if (value < min[axis]) min[axis] = value
            if (value > max[axis]) max[axis] = value
            bin.putFloat(value)
        }
        for (value in mesh.normals) bin.putFloat(value)
        for (value in mesh.uvs) bin.putFloat(value)
        for (index in mesh.indices) bin.putInt(index)
        if (jpeg != null) bin.put(jpeg)

        val textured = jpeg != null
        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Reality3D\"},")
            append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
            append("\"nodes\":[{\"mesh\":0,\"name\":").append(quote(name)).append("}],")
            append("\"meshes\":[{\"name\":").append(quote(name)).append(",\"primitives\":[{")
            append("\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0,\"mode\":4}]}],")
            append("\"materials\":[{\"name\":\"Photo\",\"pbrMetallicRoughness\":{")
            if (textured) append("\"baseColorTexture\":{\"index\":0},")
            append("\"metallicFactor\":0.0,\"roughnessFactor\":0.9},")
            append("\"doubleSided\":").append(!mesh.solid).append("}],")
            if (textured) {
                append("\"textures\":[{\"sampler\":0,\"source\":0}],")
                append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
                append("\"images\":[{\"bufferView\":4,\"mimeType\":\"image/jpeg\"}],")
            }
            append("\"accessors\":[")
            append("{\"bufferView\":0,\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC3\",")
            append("\"min\":[").append(number(min[0])).append(',').append(number(min[1])).append(',').append(number(min[2])).append("],")
            append("\"max\":[").append(number(max[0])).append(',').append(number(max[1])).append(',').append(number(max[2])).append("]},")
            append("{\"bufferView\":1,\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC3\"},")
            append("{\"bufferView\":2,\"componentType\":$FLOAT,\"count\":$vertices,\"type\":\"VEC2\"},")
            append("{\"bufferView\":3,\"componentType\":$UNSIGNED_INT,\"count\":${mesh.indices.size},\"type\":\"SCALAR\"}],")
            append("\"bufferViews\":[")
            append(view(positionsOffset, vertices * 12, ARRAY_BUFFER)).append(',')
            append(view(normalsOffset, vertices * 12, ARRAY_BUFFER)).append(',')
            append(view(uvsOffset, vertices * 8, ARRAY_BUFFER)).append(',')
            append(view(indicesOffset, mesh.indices.size * 4, ELEMENT_ARRAY_BUFFER))
            if (textured) append(',').append(view(imageOffset, imageLength, null))
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

    private fun view(offset: Int, length: Int, target: Int?): String =
        "{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":$length" + (target?.let { ",\"target\":$it" } ?: "") + "}"

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
