package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.scan.SyntheticScan.BLUE
import com.ma7moud.reality3d.scan.SyntheticScan.RED
import com.ma7moud.reality3d.scan.SyntheticScan.distance
import com.ma7moud.reality3d.scan.SyntheticScan.openEdges
import com.ma7moud.reality3d.scan.SyntheticScan.signedVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class ScanReconstructionTest {

    /** Mean and 95th-percentile distance of vertices to the true surface, ignoring the extended base. */
    private fun errors(positions: FloatArray, lowest: Float = 0.02f): Pair<Float, Float> {
        val distances = ArrayList<Float>()
        for (v in 0 until positions.size / 3) {
            if (positions[v * 3 + 1] < lowest) continue
            distances += abs(distance(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]))
        }
        distances.sort()
        return distances.average().toFloat() to distances[(distances.size * 0.95).toInt()]
    }

    @Test
    fun marchingTetrahedraGivesAClosedRoundSphere() {
        val n = 40
        val spacing = 0.01f
        val field = FloatArray(n * n * n) { index ->
            val x = index % n * spacing - 0.2f
            val y = index / n % n * spacing - 0.2f
            val z = index / (n * n) * spacing - 0.2f
            sqrt(x * x + y * y + z * z) - 0.12f
        }
        val mesh = MarchingTetrahedra.extract(field, n, n, n, -0.2f, -0.2f, -0.2f, spacing)
        assertEquals(0, openEdges(mesh.indices))
        val expectedVolume = (4.0 / 3.0 * PI * 0.12 * 0.12 * 0.12).toFloat()
        assertEquals(expectedVolume, signedVolume(mesh.positions, mesh.indices), expectedVolume * 0.03f)
        for (v in 0 until mesh.vertexCount) {
            val r = sqrt(mesh.positions[v * 3] * mesh.positions[v * 3] + mesh.positions[v * 3 + 1] * mesh.positions[v * 3 + 1] + mesh.positions[v * 3 + 2] * mesh.positions[v * 3 + 2])
            assertEquals(0.12f, r, spacing * 0.5f)
        }
    }

    @Test
    fun ringsOfDepthMapsFuseIntoAnAccurateClosedSurface() {
        val volume = SyntheticScan.fuse(SyntheticScan.ringPoses(), resolution = 64)
        val surface = ScanReconstructor.surface(volume)
        assertEquals(0, openEdges(surface.indices))
        assertTrue(signedVolume(surface.positions, surface.indices) > 0f)
        val (mean, p95) = errors(surface.positions)
        assertTrue("mean error $mean", mean < 0.001f)
        assertTrue("95% error $p95", p95 < 0.003f)
        // Nothing of the table: the lowest points are the base, dropped exactly onto it.
        var lowest = Float.MAX_VALUE
        for (v in 0 until surface.positions.size / 3) lowest = minOf(lowest, surface.positions[v * 3 + 1])
        assertEquals(0f, lowest, 1e-6f)
    }

    /**
     * Raw depth as ARCore gives it: [missing] of the pixels empty, and [outliers] of the rest several
     * centimetres off with low confidence; the good pixels are confident.
     */
    private fun rawFrame(pose: CameraPose, random: java.util.Random, missing: Float, outliers: Float, withConfidence: Boolean): DepthFrame {
        val dense = SyntheticScan.depthFrame(pose, noise = 0.002f, random = random)
        val depth = dense.depthMm.copyOf()
        val confidence = ByteArray(depth.size)
        for (i in depth.indices) {
            when {
                random.nextFloat() < missing -> depth[i] = 0
                random.nextFloat() < outliers -> {
                    depth[i] = (depth[i] + 60).toShort()
                    confidence[i] = 20
                }
                else -> confidence[i] = 220.toByte()
            }
        }
        return DepthFrame(dense.width, dense.height, depth, dense.intrinsics, pose, if (withConfidence) confidence else null)
    }

    @Test
    fun sparseRawDepthWithConfidenceIsAsAccurateAsDenseDepth() {
        val poses = SyntheticScan.ringPoses()
        fun fused(withConfidence: Boolean): TsdfVolume {
            val random = java.util.Random(7)
            return TsdfVolume(SyntheticScan.box, 64).apply {
                for (pose in poses) integrate(rawFrame(pose, random, missing = 0.5f, outliers = 0.15f, withConfidence = withConfidence))
            }
        }
        val raw = ScanReconstructor.surface(fused(withConfidence = true))
        assertEquals(0, openEdges(raw.indices))
        val (mean, p95) = errors(raw.positions)
        assertTrue("mean error $mean", mean < 0.0015f)
        assertTrue("95% error $p95", p95 < 0.004f)
        // Without the confidence map the outliers count in full and push the surface out.
        val (blindMean, _) = errors(ScanReconstructor.surface(fused(withConfidence = false)).positions)
        assertTrue("confidence should help: $mean vs $blindMean", mean < blindMean * 0.7f)
    }

    /** How far from its centre the ball touches the table in the model (the real ball touches at one point). */
    private fun ballFootprint(positions: FloatArray): Float {
        var widest = 0f
        for (v in 0 until positions.size / 3) {
            val x = positions[v * 3]
            val z = positions[v * 3 + 2]
            if (positions[v * 3 + 1] > 1e-4f || x > 0.05f) continue
            widest = maxOf(widest, sqrt(x * x + z * z))
        }
        return widest
    }

    @Test
    fun roundObjectsKeepTheirShapeDownToTheTable() {
        val surface = ScanReconstructor.surface(SyntheticScan.fuse(SyntheticScan.ringPoses(), resolution = 64))
        // Rays that reach the table under the ball show that space is empty, so there is no pedestal. At the
        // box's floor (4 mm up) the real ball is 2.3 cm from its centre; where no camera sees under it, the model
        // may bulge a little, but not out to the 4.3 cm of a base cut 1.5 cm above the table.
        val footprint = ballFootprint(surface.positions)
        assertTrue("footprint $footprint", footprint < 0.042f)
        // The sides stay accurate right down to the table.
        val (mean, p95) = errors(surface.positions, lowest = 0.006f)
        assertTrue("mean error $mean", mean < 0.001f)
        assertTrue("95% error $p95", p95 < 0.003f)
    }

    @Test
    fun noisyDepthStillGivesAClosedSurface() {
        // About 5 mm of noise at the scan distance, like ARCore depth on a phone without a depth sensor.
        val volume = SyntheticScan.fuse(SyntheticScan.ringPoses(), noise = 0.011f, resolution = 64)
        val surface = ScanReconstructor.surface(volume)
        assertEquals(0, openEdges(surface.indices))
        val (mean, p95) = errors(surface.positions)
        assertTrue("mean error $mean", mean < 0.0015f)
        assertTrue("95% error $p95", p95 < 0.004f)
    }

    @Test
    fun partialCoverageDoesNotFillTheBoxOrOpenTheSurface() {
        // One low ring from close up: the top corners of the box are never in view.
        val poses = SyntheticScan.ringPoses(elevationsDeg = listOf(20f), perRing = 16, distance = 0.3f)
        val surface = ScanReconstructor.surface(SyntheticScan.fuse(poses, resolution = 48))
        assertEquals(0, openEdges(surface.indices))
        var top = 0f
        for (v in 0 until surface.positions.size / 3) top = maxOf(top, surface.positions[v * 3 + 1])
        assertTrue("unseen space filled up to $top m", top < 0.2f)
    }

    @Test
    fun coloursComeFromThePhotos() {
        val poses = SyntheticScan.ringPoses()
        val volume = SyntheticScan.fuse(poses, resolution = 64)
        val photos = poses.filterIndexed { index, _ -> index % 3 == 0 }.map { SyntheticScan.photo(it) }
        val surface = ScanReconstructor.surface(volume)
        val colors = VertexColorizer.colorize(surface.positions, surface.normals, surface.indices, photos, tolerance = 0.014f)
        var ballRight = 0
        var ballTotal = 0
        var blockRight = 0
        var blockTotal = 0
        for (v in 0 until surface.positions.size / 3) {
            val x = surface.positions[v * 3]
            val y = surface.positions[v * 3 + 1]
            val z = surface.positions[v * 3 + 2]
            if (y < 0.03f) continue
            val r = colors[v * 3]
            val b = colors[v * 3 + 2]
            val toBall = sqrt((x - SyntheticScan.BALL_X) * (x - SyntheticScan.BALL_X) + (y - SyntheticScan.BALL_Y) * (y - SyntheticScan.BALL_Y) + z * z) - SyntheticScan.BALL_RADIUS
            if (abs(toBall) < 0.004f && x < 0.05f) {
                ballTotal++
                if (r > b + 0.2f) ballRight++
            } else if (x > 0.09f && abs(distance(x, y, z)) < 0.004f) {
                blockTotal++
                if (b > r + 0.2f) blockRight++
            }
        }
        assertTrue(ballTotal > 100 && blockTotal > 50)
        assertTrue("ball $ballRight/$ballTotal red", ballRight > ballTotal * 0.9)
        assertTrue("block $blockRight/$blockTotal blue", blockRight > blockTotal * 0.9)
        // Sanity: the photos really are red and blue where they should be.
        assertTrue(photos.first().argb.any { it == RED } && photos.first().argb.any { it == BLUE })
    }

    @Test
    fun finishedModelStandsOnTheOriginInMeters() {
        val poses = SyntheticScan.ringPoses()
        val mesh = ScanReconstructor.reconstruct(SyntheticScan.fuse(poses, resolution = 64), poses.take(6).map { SyntheticScan.photo(it) })
        assertTrue(mesh.realScale && mesh.solid)
        val b = mesh.bounds
        assertEquals(0f, b[1], 1e-6f)
        // Ball top 14 cm; ball to block 20 cm wide; 14 cm deep. The first photo was taken from +Z.
        assertEquals(0.14f, b[4] - b[1], 0.008f)
        assertEquals(0.20f, b[3] - b[0], 0.008f)
        assertEquals(0.14f, b[5] - b[2], 0.008f)
        assertEquals(0f, (b[0] + b[3]) / 2, 1e-4f)
        assertEquals(0f, (b[2] + b[5]) / 2, 1e-4f)
        assertEquals(mesh.vertexCount * 3, mesh.colors!!.size)
    }
}
