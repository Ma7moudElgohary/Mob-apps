package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.mesh.TestShapes.disk
import com.ma7moud.reality3d.mesh.TestShapes.flatDepth
import com.ma7moud.reality3d.mesh.TestShapes.mask
import com.ma7moud.reality3d.mesh.TestShapes.openEdges
import com.ma7moud.reality3d.mesh.TestShapes.rampDepth
import com.ma7moud.reality3d.segmentation.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class MeshBuilderTest {

    private val photoWidth = 400
    private val photoHeight = 300
    private val diskMask = disk(photoWidth, photoHeight, 200f, 150f, 100f)

    private fun build(mask: SubjectMask? = diskMask, settings: MeshSettings = MeshSettings(), width: Int = photoWidth, height: Int = photoHeight) =
        MeshBuilder.build(flatDepth(), mask, width, height, settings)

    @Test
    fun solidMeshIsClosed() {
        val mesh = build()
        assertTrue(mesh.solid)
        assertTrue(mesh.subjectIsolated)
        assertEquals(0, openEdges(mesh))
    }

    @Test
    fun reliefMeshIsOpenAndFacesTheCamera() {
        val relief = build(settings = MeshSettings(solid = false))
        val solid = build()
        assertFalse(relief.solid)
        assertTrue(openEdges(relief) > 0)
        assertEquals(solid.triangleCount, relief.triangleCount * 2)
        // Flat depth, round subject: the middle of the front surface faces the camera.
        var facing = 0
        for (i in 0 until relief.vertexCount) if (relief.normals[i * 3 + 2] > 0.5f) facing++
        assertTrue(facing > relief.vertexCount / 2)
    }

    @Test
    fun meshIsScaledAndCentred() {
        val b = build().bounds
        // The disk is 200 px across, so both sides are about one unit, centred on the origin.
        assertEquals(-0.5f, b[0], 0.02f)
        assertEquals(0.5f, b[3], 0.02f)
        assertEquals(-0.5f, b[1], 0.02f)
        assertEquals(0.5f, b[4], 0.02f)
        assertEquals(0f, b[2] + b[5], 1e-4f)
    }

    @Test
    fun thicknessFollowsTheSetting() {
        val thin = build(settings = MeshSettings(thickness = 0.2f)).bounds
        val thick = build(settings = MeshSettings(thickness = 1f)).bounds
        val thinDepth = thin[5] - thin[2]
        val thickDepth = thick[5] - thick[2]
        assertEquals(5f, thickDepth / thinDepth, 0.1f)
        // A disk inflates to about half a sphere of radius r / sqrt(2) on each side at thickness 1.
        assertEquals(1f / sqrt(2f), thickDepth, 0.08f)
    }

    @Test
    fun boxyProfileIsFullerThanRound() {
        fun meanFrontDepth(mesh: Mesh3D): Float {
            var sum = 0f
            var count = 0
            for (i in 0 until mesh.vertexCount) {
                if (mesh.positions[i * 3 + 2] > 0f) {
                    sum += mesh.positions[i * 3 + 2]
                    count++
                }
            }
            return sum / count
        }
        val round = build(settings = MeshSettings(profile = ShapeProfile.ROUND))
        val boxy = build(settings = MeshSettings(profile = ShapeProfile.BOXY))
        assertTrue(meanFrontDepth(boxy) > meanFrontDepth(round) * 1.1f)
    }

    @Test
    fun depthMapTiltsTheModel() {
        fun tilt(strength: Float): Float {
            val mesh = MeshBuilder.build(rampDepth(), diskMask, photoWidth, photoHeight, MeshSettings(depthStrength = strength, thickness = 0.1f))
            var left = 0f
            var right = 0f
            for (i in 0 until mesh.vertexCount) {
                val x = mesh.positions[i * 3]
                val z = mesh.positions[i * 3 + 2]
                if (x < -0.25f) left += z else if (x > 0.25f) right += z
            }
            return right - left
        }
        assertTrue("nearer side should come forward", tilt(1f) > 0f)
        assertTrue(tilt(2f) > tilt(1f))
        assertEquals(0f, tilt(0f), 0.5f)
    }

    @Test
    fun twoSubjectsAreBothKeptAndClosed() {
        val twoDisks = mask(photoWidth, photoHeight) { x, y -> hypot(x - 100f, y - 150f) <= 60f || hypot(x - 300f, y - 150f) <= 60f }
        val mesh = build(twoDisks)
        assertEquals(0, openEdges(mesh))
        var left = false
        var right = false
        for (i in 0 until mesh.vertexCount) {
            if (mesh.positions[i * 3] < -0.3f) left = true
            if (mesh.positions[i * 3] > 0.3f) right = true
        }
        assertTrue(left && right)
    }

    @Test
    fun specksAndPinholesAreCleanedUp() {
        val messy = mask(photoWidth, photoHeight) { x, y ->
            val inDisk = hypot(x - 200f, y - 150f) <= 100f
            val pinhole = abs(x - 200) <= 1 && abs(y - 150) <= 1
            val speck = x in 10..12 && y in 10..12
            (inDisk && !pinhole) || speck
        }
        val clean = build()
        val mesh = build(messy)
        assertEquals(0, openEdges(mesh))
        assertEquals(clean.vertexCount, mesh.vertexCount)
        for (i in 0 until 6) assertEquals(clean.bounds[i], mesh.bounds[i], 1e-3f)
    }

    @Test
    fun missingOrEmptyMaskUsesTheWholePhoto() {
        for (mask in listOf(null, mask(photoWidth, photoHeight) { _, _ -> false })) {
            val mesh = build(mask)
            assertFalse(mesh.subjectIsolated)
            assertEquals(0, openEdges(mesh))
            val b = mesh.bounds
            assertEquals(1f, b[3] - b[0], 1e-3f)
            assertEquals(0.75f, b[4] - b[1], 0.01f)
        }
    }

    @Test
    fun portraitPhotosWork() {
        val tall = disk(300, 600, 150f, 300f, 140f)
        val mesh = build(tall, width = 300, height = 600)
        assertEquals(0, openEdges(mesh))
        val b = mesh.bounds
        assertEquals(1f, b[4] - b[1], 0.03f)
        assertEquals(1f, b[3] - b[0], 0.03f)
    }

    @Test
    fun smallMasksAreSampled() {
        // A mask smaller than the vertex grid is sampled bilinearly instead of averaged.
        val mesh = MeshBuilder.build(flatDepth(), disk(40, 30, 20f, 15f, 10f), 400, 300, MeshSettings())
        assertTrue(mesh.subjectIsolated)
        assertEquals(0, openEdges(mesh))
    }

    @Test
    fun attributesAreWellFormed() {
        val mesh = MeshBuilder.build(rampDepth(), diskMask, photoWidth, photoHeight, MeshSettings(detail = MeshDetail.HIGH))
        assertEquals(mesh.vertexCount * 3, mesh.normals.size)
        assertEquals(mesh.vertexCount * 2, mesh.uvs.size)
        for (i in 0 until mesh.vertexCount) {
            val n = sqrt(mesh.normals[i * 3] * mesh.normals[i * 3] + mesh.normals[i * 3 + 1] * mesh.normals[i * 3 + 1] + mesh.normals[i * 3 + 2] * mesh.normals[i * 3 + 2])
            assertEquals(1f, n, 1e-3f)
            assertTrue(mesh.uvs[i * 2] in 0f..1f && mesh.uvs[i * 2 + 1] in 0f..1f)
        }
        for (index in mesh.indices) assertTrue(index in 0 until mesh.vertexCount)
        // Texture follows the photo: the top of the model samples the top of the photo.
        var topV = 1f
        var topY = -1f
        for (i in 0 until mesh.vertexCount) {
            if (mesh.positions[i * 3 + 1] > topY) {
                topY = mesh.positions[i * 3 + 1]
                topV = mesh.uvs[i * 2 + 1]
            }
        }
        assertTrue(topV < 0.3f)
    }

    @Test
    fun randomShapesAreAlwaysClosed() {
        // Blobs, thin strands, diagonal touches and speckle: the solid mesh must stay watertight.
        val random = java.util.Random(7)
        repeat(40) { round ->
            val w = 120 + random.nextInt(220)
            val h = 120 + random.nextInt(220)
            val blobs = List(1 + random.nextInt(5)) { floatArrayOf(random.nextFloat() * w, random.nextFloat() * h, 4f + random.nextFloat() * 50f) }
            val strands = List(random.nextInt(4)) {
                floatArrayOf(random.nextFloat() * w, random.nextFloat() * h, random.nextFloat() * w, random.nextFloat() * h, 0.5f + random.nextFloat() * 3f)
            }
            val speckle = random.nextBoolean()
            val noise = java.util.Random(round.toLong())
            val shape = mask(w, h) { x, y ->
                blobs.any { hypot(x - it[0], y - it[1]) < it[2] } ||
                    strands.any { distanceToSegment(x.toFloat(), y.toFloat(), it) < it[4] } ||
                    (speckle && noise.nextFloat() < 0.03f)
            }
            for (settings in listOf(MeshSettings(), MeshSettings(detail = MeshDetail.HIGH, profile = ShapeProfile.BOXY))) {
                val mesh = MeshBuilder.build(rampDepth(), shape, w, h, settings)
                assertEquals("round $round", 0, openEdges(mesh))
                assertTrue(mesh.positions.all { it.isFinite() } && mesh.normals.all { it.isFinite() })
                for (index in mesh.indices) assertTrue(index in 0 until mesh.vertexCount)
            }
        }
    }

    private fun distanceToSegment(px: Float, py: Float, s: FloatArray): Float {
        val dx = s[2] - s[0]
        val dy = s[3] - s[1]
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - s[0]) * dx + (py - s[1]) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (s[0] + t * dx), py - (s[1] + t * dy))
    }

    @Test
    fun highDetailHasMoreTriangles() {
        val standard = build()
        val high = build(settings = MeshSettings(detail = MeshDetail.HIGH))
        assertTrue(high.triangleCount > standard.triangleCount * 2)
        assertEquals(0, openEdges(high))
    }

    @Test
    fun silhouetteFollowsTheMaskContour() {
        // A soft-edged disk: vertices on the outline should sit close to the 50% contour.
        val soft = SubjectMask(photoWidth, photoHeight, FloatArray(photoWidth * photoHeight) {
            val d = hypot(it % photoWidth - 200f, it / photoWidth - 150f)
            (1f - (d - 95f) / 10f).coerceIn(0f, 1f)
        })
        val mesh = build(soft, MeshSettings(solid = false))
        // Outline vertices are the ends of edges used by a single triangle.
        val uses = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) {
            for (e in 0 until 3) {
                val a = mesh.indices[t * 3 + e].toLong()
                val b = mesh.indices[t * 3 + (e + 1) % 3].toLong()
                uses.merge(if (a < b) (a shl 32) or b else (b shl 32) or a, 1, Int::plus)
            }
        }
        val outline = HashSet<Int>()
        for ((key, count) in uses) {
            if (count != 1) continue
            outline += (key ushr 32).toInt()
            outline += (key and 0xFFFFFFFFL).toInt()
        }
        assertTrue(outline.size > 50)
        var worst = 0f
        for (i in outline) {
            // UVs are photo coordinates, so they give the vertex's pixel position directly.
            val r = hypot(mesh.uvs[i * 2] * (photoWidth - 1) - 200f, mesh.uvs[i * 2 + 1] * (photoHeight - 1) - 150f)
            worst = max(worst, abs(r - 100f))
        }
        // Within a grid cell of the true outline (399 px / 144 cells ≈ 2.8 px).
        val cell = (photoWidth - 1f) / MeshDetail.STANDARD.gridCells
        assertTrue("outline off by $worst px", worst < cell)
    }
}
