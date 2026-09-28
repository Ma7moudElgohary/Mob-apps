package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.mesh.TestShapes.disk
import com.ma7moud.reality3d.mesh.TestShapes.rampDepth
import com.ma7moud.reality3d.scan.SyntheticScan.openEdges
import com.ma7moud.reality3d.scan.SyntheticScan.signedVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

class GameReadyTest {

    private val mesh = MeshBuilder.build(rampDepth(), disk(400, 300, 200f, 150f, 100f), 400, 300, MeshSettings(detail = MeshDetail.HIGH))

    @Test
    fun hullOfACubeIsTheCube() {
        val random = Random(2)
        val points = ArrayList<Float>()
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) points += listOf(x, y, z)
        repeat(200) { points += listOf(random.nextFloat() * 1.8f - 0.9f, random.nextFloat() * 1.8f - 0.9f, random.nextFloat() * 1.8f - 0.9f) }
        val hull = ConvexHull.of(points.toFloatArray())
        assertEquals(8, hull.vertexCount)
        assertEquals(12, hull.triangleCount)
        assertEquals(0, openEdges(hull.indices))
        assertEquals(8f, signedVolume(hull.positions, hull.indices), 1e-3f)
    }

    @Test
    fun hullEnclosesEveryPointWithFewVertices() {
        val hull = ConvexHull.of(mesh.positions, maxVertices = 64)
        assertTrue(hull.vertexCount <= 64)
        assertEquals(0, openEdges(hull.indices))
        assertTrue(signedVolume(hull.positions, hull.indices) > 0f)
        // Every model vertex is inside or near the hull: built from extreme points only, it sits a little inside.
        val p = hull.positions
        val size = mesh.longestSide
        for (t in hull.indices.indices step 3) {
            val a = hull.indices[t]; val b = hull.indices[t + 1]; val c = hull.indices[t + 2]
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            for (v in 0 until mesh.vertexCount) {
                val d = (mesh.positions[v * 3] - p[a * 3]) * nx + (mesh.positions[v * 3 + 1] - p[a * 3 + 1]) * ny + (mesh.positions[v * 3 + 2] - p[a * 3 + 2]) * nz
                assertTrue("vertex $v is ${d / size} outside", d <= size * 0.05f)
            }
        }
    }

    @Test
    fun levelsHalveAndStayClosed() {
        val levels = GameReadyPack.levels(mesh, GameReadyPack.Budget.LIGHT)
        val counts = levels.lods.map { it.triangleCount }
        assertTrue(counts[0] <= 5_000 && counts[1] <= 2_500 && counts[2] <= 1_250)
        assertTrue(counts[2] > 600)
        for (lod in levels.lods) {
            assertEquals(0, openEdges(lod.indices))
            assertEquals(lod.vertexCount * 2, lod.uvs!!.size)
        }
    }

    @Test
    fun packHasTheLevelsHullAndNotes() {
        val zip = GameReadyPack.write(mesh, null, "Mug", GameReadyPack.Budget.MEDIUM)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
                val bytes = input.readBytes()
                if (entry.name.endsWith(".glb")) assertEquals(0x46546C67, ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int)
                if (entry.name == "README.txt") assertTrue(String(bytes).contains("Import LOD Level 1"))
            }
        }
        assertEquals(listOf("Mug_LOD0.glb", "Mug_LOD1.glb", "Mug_LOD2.glb", "UCX_Mug_00.glb", "README.txt"), names)
    }

    @Test
    fun plyHasEveryVertexAndFace() {
        val ply = PlyWriter.write(mesh, PlyWriter.Photo(2, 2, intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)))
        val headerEnd = String(ply, 0, 600, Charsets.US_ASCII).indexOf("end_header\n") + "end_header\n".length
        val header = String(ply, 0, headerEnd, Charsets.US_ASCII)
        assertTrue(header.contains("format binary_little_endian 1.0"))
        assertTrue(header.contains("element vertex ${mesh.vertexCount}"))
        assertTrue(header.contains("element face ${mesh.triangleCount}"))
        assertEquals(headerEnd + mesh.vertexCount * 27 + mesh.triangleCount * 13, ply.size)
        // Normalised models come out 20 cm long.
        val buffer = ByteBuffer.wrap(ply, headerEnd, ply.size - headerEnd).order(ByteOrder.LITTLE_ENDIAN)
        var maxX = -1f; var minX = 1f
        for (v in 0 until mesh.vertexCount) {
            val x = buffer.getFloat(headerEnd + v * 27)
            maxX = maxOf(maxX, x); minX = minOf(minX, x)
        }
        assertTrue(maxX - minX <= 0.2001f)
        // The first face is a triangle of valid vertices.
        val faces = headerEnd + mesh.vertexCount * 27
        assertEquals(3.toByte(), ply[faces])
        assertTrue(buffer.getInt(faces + 1) in 0 until mesh.vertexCount)
    }
}
