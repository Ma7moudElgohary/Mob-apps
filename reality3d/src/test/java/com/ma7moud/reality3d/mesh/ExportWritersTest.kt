package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.mesh.TestShapes.disk
import com.ma7moud.reality3d.mesh.TestShapes.rampDepth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

class ExportWritersTest {

    private val mesh = MeshBuilder.build(rampDepth(), disk(400, 300, 200f, 150f, 100f), 400, 300, MeshSettings())
    private val jpeg = ByteArray(1001) { (it * 7).toByte() }

    @Test
    fun glbIsWellFormed() {
        val glb = GlbWriter.write(mesh, jpeg, longestSideMeters = 0.2f, name = "Test \"mug\"")
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(glb.size, buffer.int)

        val jsonLength = buffer.int
        assertEquals(0x4E4F534A, buffer.int)
        assertEquals(0, jsonLength % 4)
        val json = String(glb, 20, jsonLength, Charsets.UTF_8).trimEnd()
        buffer.position(20 + jsonLength)
        val binLength = buffer.int
        assertEquals(0x004E4942, buffer.int)
        assertEquals(0, binLength % 4)
        assertEquals(glb.size, 28 + jsonLength + binLength)
        val binStart = 28 + jsonLength

        assertTrue(json.contains("\"name\":\"Test \\\"mug\\\"\""))
        assertTrue(json.contains("\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2"))
        assertTrue(json.contains("\"byteLength\":$binLength}]"))
        assertTrue(json.contains("\"doubleSided\":false"))
        val counts = Regex("\"count\":(\\d+)").findAll(json).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(mesh.vertexCount, mesh.vertexCount, mesh.vertexCount, mesh.indices.size), counts)

        // Positions are scaled so the longest side is 0.2 m, and the accessor bounds match them.
        val bin = ByteBuffer.wrap(glb, binStart, binLength).order(ByteOrder.LITTLE_ENDIAN)
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until mesh.vertexCount * 3) {
            val value = bin.float
            min[i % 3] = min(min[i % 3], value)
            max[i % 3] = max(max[i % 3], value)
        }
        assertEquals(0.2f, (0 until 3).maxOf { max[it] - min[it] }, 1e-5f)
        val declaredMin = Regex("\"min\":\\[([^]]+)]").find(json)!!.groupValues[1].split(',').map { it.toFloat() }
        val declaredMax = Regex("\"max\":\\[([^]]+)]").find(json)!!.groupValues[1].split(',').map { it.toFloat() }
        for (axis in 0 until 3) {
            assertEquals(min[axis], declaredMin[axis], 0f)
            assertEquals(max[axis], declaredMax[axis], 0f)
        }

        // The photo is embedded byte for byte where its buffer view says.
        val views = Regex("\\{\"buffer\":0,\"byteOffset\":(\\d+),\"byteLength\":(\\d+)").findAll(json).toList()
        assertEquals(5, views.size)
        val imageOffset = views[4].groupValues[1].toInt()
        val imageLength = views[4].groupValues[2].toInt()
        assertEquals(jpeg.size, imageLength)
        assertArrayEquals(jpeg, glb.copyOfRange(binStart + imageOffset, binStart + imageOffset + imageLength))
        for (view in views) assertEquals(0, view.groupValues[1].toInt() % 4)
    }

    @Test
    fun glbWithoutTextureOrBackIsDoubleSided() {
        val relief = MeshBuilder.build(rampDepth(), disk(400, 300, 200f, 150f, 100f), 400, 300, MeshSettings(solid = false))
        val glb = GlbWriter.write(relief, jpeg = null)
        val jsonLength = ByteBuffer.wrap(glb, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val json = String(glb, 20, jsonLength, Charsets.UTF_8)
        assertTrue(json.contains("\"doubleSided\":true"))
        assertFalse(json.contains("images"))
        assertFalse(json.contains("baseColorTexture"))
    }

    @Test
    fun stlIsPrintReady() {
        val stl = StlWriter.write(mesh, longestSideMm = 100f)
        assertEquals(84 + mesh.triangleCount * 50, stl.size)
        assertFalse(String(stl, 0, 5, Charsets.US_ASCII).equals("solid", ignoreCase = true))
        val buffer = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        assertEquals(mesh.triangleCount, buffer.int)
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (t in 0 until mesh.triangleCount) {
            repeat(3) { buffer.float } // normal
            for (v in 0 until 3) {
                for (axis in 0 until 3) {
                    val value = buffer.float
                    min[axis] = min(min[axis], value)
                    max[axis] = max(max[axis], value)
                }
            }
            buffer.short
        }
        assertEquals("sits on the build plate", 0f, min[2], 1e-4f)
        assertEquals(100f, (0 until 3).maxOf { max[it] - min[it] }, 1e-3f)
        // The photo's up direction becomes Z.
        val b = mesh.bounds
        assertEquals((b[4] - b[1]) / mesh.longestSide * 100f, max[2] - min[2], 1e-2f)
    }

    @Test
    fun objZipHasModelMaterialAndTexture() {
        val zip = ObjWriter.writeZip(mesh, jpeg, "mug_3d")
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }
        assertEquals(listOf("mug_3d.obj", "mug_3d.mtl", "mug_3d.jpg"), entries.keys.toList())
        assertArrayEquals(jpeg, entries["mug_3d.jpg"])
        assertTrue(String(entries["mug_3d.mtl"]!!).contains("map_Kd mug_3d.jpg"))

        val lines = String(entries["mug_3d.obj"]!!).lines()
        assertTrue(lines.contains("mtllib mug_3d.mtl"))
        assertEquals(mesh.vertexCount, lines.count { it.startsWith("v ") })
        assertEquals(mesh.vertexCount, lines.count { it.startsWith("vt ") })
        assertEquals(mesh.vertexCount, lines.count { it.startsWith("vn ") })
        assertEquals(mesh.triangleCount, lines.count { it.startsWith("f ") })
        // OBJ texture rows start at the bottom of the image.
        val firstVt = lines.first { it.startsWith("vt ") }.split(' ').drop(1).map { it.toFloat() }
        val uvs = mesh.uvs!!
        assertEquals(uvs[0], firstVt[0], 1e-5f)
        assertEquals(1f - uvs[1], firstVt[1], 1e-5f)
        // Plain decimal numbers that any importer reads, whatever the phone's language.
        val numbers = lines.filter { it.startsWith("v ") }.flatMap { it.split(' ').drop(1) }
        assertTrue(numbers.all { Regex("-?\\d+\\.\\d{5}").matches(it) })
        val face = lines.first { it.startsWith("f ") }.split(' ').drop(1)
        assertTrue(face.all { Regex("(\\d+)/\\1/\\1").matches(it) })
    }
}
