package com.ma7moud.reality3d.mesh

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GlbReaderTest {

    private val tetra = Mesh3D(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        normals = floatArrayOf(-1f, -1f, -1f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).also { n ->
            for (v in 0 until 4) {
                val l = kotlin.math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
                for (k in 0 until 3) n[v * 3 + k] /= l
            }
        },
        uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        indices = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
        solid = true,
        subjectIsolated = true,
    )

    @Test
    fun readsWhatTheExporterWrites() {
        val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        val glb = GlbWriter.write(tetra, GlbWriter.Texture(png, "image/png"), longestSideMeters = 2f)
        val model = GlbReader.read(glb)
        // The exporter scales the longest side to 2 m.
        for (i in tetra.positions.indices) assertEquals(tetra.positions[i] * 2f, model.mesh.positions[i], 1e-5f)
        assertArrayEquals(tetra.uvs, model.mesh.uvs, 1e-6f)
        assertArrayEquals(tetra.indices, model.mesh.indices)
        assertArrayEquals(tetra.normals, model.mesh.normals, 1e-5f)
        assertArrayEquals(png, model.texture)
    }

    @Test
    fun scanColoursComeBackInSrgb() {
        val colors = floatArrayOf(1f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 0.2f, 0.8f, 0.4f)
        val scan = Mesh3D(tetra.positions, tetra.normals, null, tetra.indices, solid = true, subjectIsolated = true, colors = colors, realScale = true)
        val model = GlbReader.read(GlbWriter.write(scan, texture = null))
        assertNull(model.mesh.uvs)
        assertNull(model.texture)
        assertArrayEquals(colors, model.mesh.colors, 0.01f)
        assertArrayEquals(scan.positions, model.mesh.positions, 1e-6f)
    }

    /** A GLB built by hand: one triangle with 16-bit indices and no normals, under a mirroring node transform. */
    private fun handMade(extensionsRequired: Boolean = false): ByteArray {
        val bin = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { bin.putFloat(it) }
        shortArrayOf(0, 1, 2).forEach { bin.putShort(it) }
        bin.putShort(0) // padding
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("children", JSONArray().put(1)).put("translation", JSONArray(listOf(10, 0, 0))))
                .put(JSONObject().put("mesh", 0).put("scale", JSONArray(listOf(-2, 2, 2)))))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(JSONObject().put("attributes", JSONObject().put("POSITION", 0)).put("indices", 1)))))
            .put("accessors", JSONArray()
                .put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", 3).put("type", "VEC3"))
                .put(JSONObject().put("bufferView", 1).put("componentType", 5123).put("count", 3).put("type", "SCALAR")))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", 36))
                .put(JSONObject().put("buffer", 0).put("byteOffset", 36).put("byteLength", 6)))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", 44)))
        if (extensionsRequired) json.put("extensionsRequired", JSONArray().put("KHR_draco_mesh_compression"))
        var text = json.toString().toByteArray()
        text += ByteArray((4 - text.size % 4) % 4) { ' '.code.toByte() }
        val out = ByteBuffer.allocate(12 + 8 + text.size + 8 + 44).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(text.size).putInt(0x4E4F534A).put(text)
        out.putInt(44).putInt(0x004E4942).put(bin.array())
        return out.array()
    }

    @Test
    fun nodeTransformsAreAppliedAndMirroringKeepsTheWinding() {
        val mesh = GlbReader.read(handMade()).mesh
        // Parent moves by 10 in x; child mirrors x and doubles the size.
        assertArrayEquals(floatArrayOf(10f, 0f, 0f, 8f, 0f, 0f, 10f, 2f, 0f), mesh.positions, 1e-5f)
        // Mirroring flips the triangle, so it is wound back: its normal still points to +z.
        assertArrayEquals(intArrayOf(0, 2, 1), mesh.indices)
        for (v in 0 until 3) assertEquals(1f, mesh.normals[v * 3 + 2], 1e-5f)
    }

    @Test
    fun unsupportedFilesSayWhy() {
        assertThrows(GlbReader.FormatException::class.java) { GlbReader.read(ByteArray(40)) }
        val draco = assertThrows(GlbReader.FormatException::class.java) { GlbReader.read(handMade(extensionsRequired = true)) }
        assertTrue(draco.message!!.contains("KHR_draco_mesh_compression"))
        val cut = handMade().copyOf(60)
        assertThrows(GlbReader.FormatException::class.java) { GlbReader.read(cut) }
        assertNotNull(GlbReader.read(handMade()).mesh)
    }

    @Test
    fun readsThePhotoBuildersNoteAndIgnoresOtherFiles() {
        val plain = GlbWriter.write(tetra, texture = null)
        assertNull(GlbReader.read(plain).info)
        val measured = GlbReader.read(GlbTestKit.withBuildNote(plain, scaleKnown = true, photos = 40, placed = 38)).info!!
        assertTrue(measured.scaleKnown)
        assertEquals(40, measured.photos)
        assertEquals(38, measured.placed)
        val unknown = GlbReader.read(GlbTestKit.withBuildNote(plain, scaleKnown = false)).info!!
        assertTrue(!unknown.scaleKnown)
        assertNull(unknown.photos)
        // The note doesn't get in the way of the model itself.
        assertArrayEquals(tetra.indices, GlbReader.read(GlbTestKit.withBuildNote(plain, scaleKnown = true)).mesh.indices)
    }
}
