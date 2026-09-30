package com.ma7moud.reality3d.mesh

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Reads binary glTF (.glb) models, such as those image-to-3D AIs write, into a [Mesh3D]: every triangle
 * mesh in the scene merged with its node transforms, positions, normals (worked out when missing), texture
 * coordinates, vertex colours and the first base colour texture. Compressed meshes (Draco, meshopt) and
 * sparse accessors aren't supported.
 */
object GlbReader {

    class Model(
        val mesh: Mesh3D,
        /** The base colour image as stored (PNG or JPEG), or null. */
        val texture: ByteArray?,
        /** What a Reality3D photo build wrote about itself into the file, or null for any other GLB. */
        val info: BuildInfo? = null,
    )

    /**
     * The photo builder's note in the GLB: whether the model is in real meters, and how many of the photos
     * it could place.
     */
    class BuildInfo(val scaleKnown: Boolean, val photos: Int?, val placed: Int?)

    class FormatException(message: String) : Exception(message)

    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val TRIANGLES = 4

    fun read(bytes: ByteArray): Model {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 20 || buffer.getInt(0) != MAGIC) throw FormatException("not a GLB file")
        if (buffer.getInt(4) != 2) throw FormatException("only glTF 2 is supported")
        var offset = 12
        var json: JSONObject? = null
        var binary: ByteBuffer? = null
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            if (length < 0 || offset + 8 + length > bytes.size) throw FormatException("the GLB file is cut short")
            when (type) {
                CHUNK_JSON -> json = JSONObject(String(bytes, offset + 8, length, Charsets.UTF_8))
                CHUNK_BIN -> binary = ByteBuffer.wrap(bytes, offset + 8, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            offset += 8 + length
        }
        val gltf = json ?: throw FormatException("the GLB file has no JSON")
        gltf.optJSONArray("extensionsRequired")?.let { required ->
            if (required.length() > 0) throw FormatException("the model uses ${required.getString(0)}, which isn't supported")
        }
        return Parser(gltf, binary).parse().let { it.copy(info = buildInfo(gltf)) }
    }

    private fun Model.copy(info: BuildInfo?) = Model(mesh, texture, info)

    private fun buildInfo(gltf: JSONObject): BuildInfo? {
        val note = gltf.optJSONObject("asset")?.optJSONObject("extras")?.optJSONObject("reality3d") ?: return null
        if (note.optString("source") != "photogrammetry") return null
        return BuildInfo(
            scaleKnown = note.optBoolean("scaleKnown", false),
            photos = if (note.has("photos")) note.optInt("photos") else null,
            placed = if (note.has("placed")) note.optInt("placed") else null,
        )
    }

    private class Parser(val gltf: JSONObject, val binary: ByteBuffer?) {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val indices = ArrayList<Int>()
        var anyUvs = false
        var anyColors = false
        var anyNormalsMissing = false
        var textureIndex: Int? = null

        fun parse(): Model {
            val scenes = gltf.optJSONArray("scenes")
            val nodes = gltf.optJSONArray("nodes") ?: JSONArray()
            val roots: List<Int> = when {
                scenes != null && scenes.length() > 0 -> {
                    val scene = scenes.getJSONObject(gltf.optInt("scene", 0).coerceIn(0, scenes.length() - 1))
                    scene.optJSONArray("nodes")?.let { list -> List(list.length()) { list.getInt(it) } }.orEmpty()
                }
                else -> {
                    // No scene: every node that isn't a child is a root.
                    val children = HashSet<Int>()
                    for (i in 0 until nodes.length()) nodes.getJSONObject(i).optJSONArray("children")?.let { c -> for (k in 0 until c.length()) children += c.getInt(k) }
                    (0 until nodes.length()).filter { it !in children }
                }
            }
            val identity = FloatArray(16).also { for (k in 0 until 4) it[k * 5] = 1f }
            if (roots.isEmpty() && gltf.optJSONArray("meshes")?.length() ?: 0 > 0) {
                addMesh(0, identity)
            } else {
                for (root in roots) visit(nodes, root, identity, 0)
            }
            if (indices.isEmpty()) throw FormatException("the model has no triangles")
            val positionArray = positions.toFloatArray()
            val indexArray = indices.toIntArray()
            val normalArray = if (anyNormalsMissing) MeshBuilder.vertexNormals(positionArray, indexArray) else normals.toFloatArray()
            val mesh = Mesh3D(
                positions = positionArray,
                normals = normalArray,
                uvs = if (anyUvs) uvs.toFloatArray() else null,
                indices = indexArray,
                solid = true,
                subjectIsolated = true,
                colors = if (anyColors && !anyUvs) colors.toFloatArray() else null,
            )
            return Model(mesh, textureIndex?.let { image(it) })
        }

        fun visit(nodes: JSONArray, index: Int, parent: FloatArray, depth: Int) {
            if (depth > 64 || index !in 0 until nodes.length()) return
            val node = nodes.getJSONObject(index)
            val world = multiply(parent, localMatrix(node))
            if (node.has("mesh")) addMesh(node.getInt("mesh"), world)
            node.optJSONArray("children")?.let { children -> for (k in 0 until children.length()) visit(nodes, children.getInt(k), world, depth + 1) }
        }

        fun addMesh(meshIndex: Int, world: FloatArray) {
            val mesh = gltf.getJSONArray("meshes").getJSONObject(meshIndex)
            val primitives = mesh.getJSONArray("primitives")
            val normalMatrix = normalMatrix(world)
            for (p in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(p)
                if (primitive.optInt("mode", TRIANGLES) != TRIANGLES) continue
                if (primitive.optJSONObject("extensions")?.length() ?: 0 > 0) throw FormatException("compressed meshes aren't supported")
                val attributes = primitive.getJSONObject("attributes")
                val position = floats(attributes.getInt("POSITION"))
                val count = position.size / 3
                val base = positions.size / 3
                for (v in 0 until count) {
                    val x = position[v * 3]
                    val y = position[v * 3 + 1]
                    val z = position[v * 3 + 2]
                    positions += world[0] * x + world[4] * y + world[8] * z + world[12]
                    positions += world[1] * x + world[5] * y + world[9] * z + world[13]
                    positions += world[2] * x + world[6] * y + world[10] * z + world[14]
                }
                if (attributes.has("NORMAL")) {
                    val normal = floats(attributes.getInt("NORMAL"))
                    for (v in 0 until count) {
                        val x = normal[v * 3]
                        val y = normal[v * 3 + 1]
                        val z = normal[v * 3 + 2]
                        var nx = normalMatrix[0] * x + normalMatrix[3] * y + normalMatrix[6] * z
                        var ny = normalMatrix[1] * x + normalMatrix[4] * y + normalMatrix[7] * z
                        var nz = normalMatrix[2] * x + normalMatrix[5] * y + normalMatrix[8] * z
                        val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
                        nx /= length
                        ny /= length
                        nz /= length
                        normals += nx
                        normals += ny
                        normals += nz
                    }
                } else {
                    anyNormalsMissing = true
                    repeat(count * 3) { normals += 0f }
                }
                // Every primitive adds texture coordinates and colours, real or placeholders, so they stay aligned.
                val uv = if (attributes.has("TEXCOORD_0")) floats(attributes.getInt("TEXCOORD_0")) else null
                if (uv != null) {
                    anyUvs = true
                    for (k in 0 until count * 2) uvs += uv[k]
                    if (textureIndex == null) textureIndex = baseColorTexture(primitive)
                } else {
                    repeat(count * 2) { uvs += 0f }
                }
                val color = if (attributes.has("COLOR_0")) colorsOf(attributes.getInt("COLOR_0"), count) else null
                if (color != null) {
                    anyColors = true
                    colors.addAll(color.asList())
                } else {
                    repeat(count * 3) { colors += 1f }
                }
                val primitiveIndices = if (primitive.has("indices")) ints(primitive.getInt("indices")) else IntArray(count) { it }
                val flip = determinant(world) < 0f
                for (t in 0 until primitiveIndices.size / 3) {
                    val a = primitiveIndices[t * 3]
                    val b = primitiveIndices[t * 3 + 1]
                    val c = primitiveIndices[t * 3 + 2]
                    if (a !in 0 until count || b !in 0 until count || c !in 0 until count) throw FormatException("the model's triangles point outside it")
                    indices += base + a
                    // A mirroring transform turns the winding inside out; undo that.
                    indices += base + if (flip) c else b
                    indices += base + if (flip) b else c
                }
            }
        }

        fun baseColorTexture(primitive: JSONObject): Int? {
            val material = gltf.optJSONArray("materials")?.optJSONObject(primitive.optInt("material", -1)) ?: return null
            val textureRef = material.optJSONObject("pbrMetallicRoughness")?.optJSONObject("baseColorTexture") ?: return null
            if (textureRef.optInt("texCoord", 0) != 0) return null
            val texture = gltf.optJSONArray("textures")?.optJSONObject(textureRef.getInt("index")) ?: return null
            return if (texture.has("source")) texture.getInt("source") else null
        }

        fun image(index: Int): ByteArray? {
            val image = gltf.optJSONArray("images")?.optJSONObject(index) ?: return null
            if (image.has("bufferView")) return view(image.getInt("bufferView")).let { (data, _) -> ByteArray(data.remaining()).also { data.get(it) } }
            val uri = image.optString("uri")
            if (uri.startsWith("data:")) return Base64.decode(uri.substringAfter(","), Base64.DEFAULT)
            return null
        }

        /** A buffer view's bytes and stride (0 when tightly packed). */
        fun view(index: Int): Pair<ByteBuffer, Int> {
            val view = gltf.getJSONArray("bufferViews").getJSONObject(index)
            if (view.optInt("buffer", 0) != 0) throw FormatException("the model refers to outside files")
            val data = binary ?: throw FormatException("the model has no binary data")
            val start = view.optInt("byteOffset", 0)
            val length = view.getInt("byteLength")
            if (start < 0 || start + length > data.capacity()) throw FormatException("the model's data is cut short")
            val slice = (data.duplicate().position(start).limit(start + length) as ByteBuffer).slice().order(ByteOrder.LITTLE_ENDIAN)
            return slice to view.optInt("byteStride", 0)
        }

        /** An accessor's values as floats, normalised integers scaled to 0..1 (or -1..1). */
        fun floats(accessorIndex: Int): FloatArray {
            val accessor = gltf.getJSONArray("accessors").getJSONObject(accessorIndex)
            if (accessor.has("sparse")) throw FormatException("sparse accessors aren't supported")
            val count = accessor.getInt("count")
            val components = componentsOf(accessor.getString("type"))
            val type = accessor.getInt("componentType")
            val size = componentSize(type)
            val normalized = accessor.optBoolean("normalized", false)
            val out = FloatArray(count * components)
            if (!accessor.has("bufferView")) return out
            val (data, stride) = view(accessor.getInt("bufferView"))
            val offset = accessor.optInt("byteOffset", 0)
            val step = if (stride > 0) stride else size * components
            if (count > 0 && offset + (count - 1) * step + size * components > data.capacity()) throw FormatException("the model's data is cut short")
            for (i in 0 until count) {
                for (c in 0 until components) {
                    val at = offset + i * step + c * size
                    out[i * components + c] = when (type) {
                        5126 -> data.getFloat(at)
                        5121 -> (data.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                        5123 -> (data.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                        5120 -> data.get(at).toInt().let { if (normalized) maxOf(it / 127f, -1f) else it.toFloat() }
                        5122 -> data.getShort(at).toInt().let { if (normalized) maxOf(it / 32767f, -1f) else it.toFloat() }
                        5125 -> data.getInt(at).toFloat()
                        else -> throw FormatException("unknown component type $type")
                    }
                }
            }
            return out
        }

        fun ints(accessorIndex: Int): IntArray {
            val accessor = gltf.getJSONArray("accessors").getJSONObject(accessorIndex)
            val count = accessor.getInt("count")
            val type = accessor.getInt("componentType")
            val size = componentSize(type)
            val (data, stride) = view(accessor.getInt("bufferView"))
            val offset = accessor.optInt("byteOffset", 0)
            val step = if (stride > 0) stride else size
            if (count > 0 && offset + (count - 1) * step + size > data.capacity()) throw FormatException("the model's data is cut short")
            return IntArray(count) { i ->
                val at = offset + i * step
                when (type) {
                    5121 -> data.get(at).toInt() and 0xFF
                    5123 -> data.getShort(at).toInt() and 0xFFFF
                    5125 -> data.getInt(at)
                    else -> throw FormatException("unsupported index type $type")
                }
            }
        }

        /** Vertex colours as sRGB 0..1 RGB (glTF stores them linear). */
        fun colorsOf(accessorIndex: Int, count: Int): FloatArray {
            val accessor = gltf.getJSONArray("accessors").getJSONObject(accessorIndex)
            val components = componentsOf(accessor.getString("type"))
            val values = floats(accessorIndex)
            val integer = accessor.getInt("componentType") != 5126 && !accessor.optBoolean("normalized", false)
            return FloatArray(count * 3) { i ->
                val raw = values[(i / 3) * components + i % 3]
                val linear = (if (integer) raw / 255f else raw).coerceIn(0f, 1f)
                if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
            }
        }

        fun componentsOf(type: String) = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT4" -> 16
            else -> throw FormatException("unsupported accessor type $type")
        }

        fun componentSize(type: Int) = when (type) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> throw FormatException("unknown component type $type")
        }
    }

    /** The node's own transform: its matrix, or translation × rotation × scale. Column-major. */
    internal fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { m -> if (m.length() == 16) return FloatArray(16) { m.getDouble(it).toFloat() } }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
        return floatArrayOf(
            (1 - 2 * (qy * qy + qz * qz)) * sx, 2 * (qx * qy + qz * qw) * sx, 2 * (qx * qz - qy * qw) * sx, 0f,
            2 * (qx * qy - qz * qw) * sy, (1 - 2 * (qx * qx + qz * qz)) * sy, 2 * (qy * qz + qx * qw) * sy, 0f,
            2 * (qx * qz + qy * qw) * sz, 2 * (qy * qz - qx * qw) * sz, (1 - 2 * (qx * qx + qy * qy)) * sz, 0f,
            tx, ty, tz, 1f,
        )
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { k ->
        val column = k / 4
        val row = k % 4
        var sum = 0f
        for (i in 0 until 4) sum += a[i * 4 + row] * b[column * 4 + i]
        sum
    }

    /** The inverse transpose of the upper 3×3, for normals (column-major 3×3). */
    private fun normalMatrix(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-20f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val inv = 1f / det
        // Cofactor matrix / det, which is the inverse transpose; stored column-major.
        return floatArrayOf(
            (e * i - f * h) * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
            -(d * i - f * g) * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
            (d * h - e * g) * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv,
        )
    }

    private fun determinant(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
}
