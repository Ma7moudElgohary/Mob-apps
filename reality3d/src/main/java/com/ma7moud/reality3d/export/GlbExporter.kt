package com.ma7moud.reality3d.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

object GlbExporter {
    fun export(context: Context, source: DepthMesh, texture: Bitmap?, name: String = "Reality3D"): Uri {
        val mesh = if (source.normals.size == source.positions.size) source else MeshMath.recalculateNormals(source)
        val binary = ByteArrayOutputStream()
        val (posOffset, posLength) = binary.writeAligned(floatBytes(mesh.positions))
        val (normalOffset, normalLength) = binary.writeAligned(floatBytes(mesh.normals))
        val uvData = if (mesh.texCoords.size == mesh.vertexCount * 2) mesh.texCoords else FloatArray(mesh.vertexCount * 2)
        val (uvOffset, uvLength) = binary.writeAligned(floatBytes(uvData))
        val (indexOffset, indexLength) = binary.writeAligned(intBytes(mesh.indices))
        val imageBytes = texture?.let {
            ByteArrayOutputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        }
        val imageView = imageBytes?.let { binary.writeAligned(it) }

        val bounds = MeshMath.bounds(mesh)
        val views = JSONArray()
        fun view(offset: Int, length: Int, target: Int? = null): Int {
            val o = JSONObject().put("buffer", 0).put("byteOffset", offset).put("byteLength", length)
            target?.let { o.put("target", it) }
            views.put(o); return views.length() - 1
        }
        val posView = view(posOffset, posLength, 34962)
        val normalView = view(normalOffset, normalLength, 34962)
        val uvView = view(uvOffset, uvLength, 34962)
        val indexView = view(indexOffset, indexLength, 34963)
        val imageViewIndex = imageView?.let { view(it.first, it.second) }

        val accessors = JSONArray()
        fun accessor(view: Int, component: Int, count: Int, type: String, min: JSONArray? = null, max: JSONArray? = null): Int {
            val o = JSONObject().put("bufferView", view).put("componentType", component).put("count", count).put("type", type)
            min?.let { o.put("min", it) }; max?.let { o.put("max", it) }
            accessors.put(o); return accessors.length() - 1
        }
        val posAccessor = accessor(posView, 5126, mesh.vertexCount, "VEC3",
            JSONArray(listOf(bounds[0], bounds[1], bounds[2])), JSONArray(listOf(bounds[3], bounds[4], bounds[5])))
        val normalAccessor = accessor(normalView, 5126, mesh.vertexCount, "VEC3")
        val uvAccessor = accessor(uvView, 5126, mesh.vertexCount, "VEC2")
        val indexAccessor = accessor(indexView, 5125, mesh.indices.size, "SCALAR")

        val primitive = JSONObject()
            .put("attributes", JSONObject().put("POSITION", posAccessor).put("NORMAL", normalAccessor).put("TEXCOORD_0", uvAccessor))
            .put("indices", indexAccessor)
            .put("mode", 4)
        if (imageViewIndex != null) primitive.put("material", 0)

        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "Reality3D"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("mesh", 0).put("name", name)))
            .put("meshes", JSONArray().put(JSONObject().put("name", name).put("primitives", JSONArray().put(primitive))))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", binary.size())))
            .put("bufferViews", views)
            .put("accessors", accessors)

        if (imageViewIndex != null) {
            json.put("images", JSONArray().put(JSONObject().put("bufferView", imageViewIndex).put("mimeType", "image/png")))
            json.put("samplers", JSONArray().put(JSONObject().put("magFilter", 9729).put("minFilter", 9987).put("wrapS", 33071).put("wrapT", 33071)))
            json.put("textures", JSONArray().put(JSONObject().put("sampler", 0).put("source", 0)))
            json.put("materials", JSONArray().put(JSONObject().put("name", "Reality3DMaterial").put(
                "pbrMetallicRoughness", JSONObject()
                    .put("baseColorTexture", JSONObject().put("index", 0))
                    .put("metallicFactor", 0.05)
                    .put("roughnessFactor", 0.72),
            ).put("doubleSided", true).put("alphaMode", "BLEND")))
        }

        val jsonBytesRaw = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytesRaw.size % 4) % 4
        val jsonBytes = jsonBytesRaw + ByteArray(jsonPad) { 0x20 }
        val binBytes = binary.toByteArray()
        val totalLength = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "reality3d_${System.currentTimeMillis()}.glb")
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x67, 0x6C, 0x54, 0x46)); out.write(littleEndianInt(2)); out.write(littleEndianInt(totalLength))
            out.write(littleEndianInt(jsonBytes.size)); out.write(byteArrayOf(0x4A, 0x53, 0x4F, 0x4E)); out.write(jsonBytes)
            out.write(littleEndianInt(binBytes.size)); out.write(byteArrayOf(0x42, 0x49, 0x4E, 0x00)); out.write(binBytes)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
