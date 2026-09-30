package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.MeshBuilder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Turns a fused scan volume and its photos into a closed, coloured mesh in real-world meters. */
object ScanReconstructor {

    private const val SMOOTHING_ITERATIONS = 6
    private const val MIN_PIECE_FRACTION = 0.1f

    /**
     * The model sits with its base at y = 0, centred on the origin, facing +Z towards where the first
     * photo was taken. Units are meters.
     */
    fun reconstruct(volume: TsdfVolume, keyframes: List<KeyframeImage>, progress: (String) -> Unit = {}): Mesh3D =
        reconstruct(volume, keyframes.size, { keyframes[it] }, keyframes.firstOrNull()?.pose, progress)

    /** Like the list version, but decodes one photo at a time through [frame]. [front] faces +Z. */
    fun reconstruct(
        volume: TsdfVolume,
        frameCount: Int,
        frame: (Int) -> KeyframeImage?,
        front: CameraPose?,
        progress: (String) -> Unit = {},
    ): Mesh3D {
        val surface = surface(volume, progress)
        progress("Colouring it from the photos…")
        val colors = VertexColorizer.colorize(
            surface.positions, surface.normals, surface.indices, frameCount, frame,
            tolerance = max(0.012f, 3f * volume.voxelSize),
        )
        toObjectFrame(surface.positions, surface.normals, volume.box.floorY, front)
        return Mesh3D(
            positions = surface.positions,
            normals = surface.normals,
            uvs = null,
            indices = surface.indices,
            solid = true,
            subjectIsolated = true,
            colors = colors,
            realScale = true,
        )
    }

    internal class Surface(val positions: FloatArray, val indices: IntArray, val normals: FloatArray)

    /** The closed, smoothed surface in world coordinates. */
    internal fun surface(volume: TsdfVolume, progress: (String) -> Unit = {}): Surface {
        progress("Finding the surface…")
        val n = volume.resolution + 2
        val s = volume.voxelSize
        // Padded grid point 0 is the centre of the empty layer just outside the box.
        val raw = MarchingTetrahedra.extract(
            volume.extractionField(), n, n, n,
            volume.originX - s / 2, volume.originY - s / 2, volume.originZ - s / 2, s,
        )
        check(raw.triangleCount > 0) { "nothing solid was found inside the box" }
        val pieces = MeshOps.keepLargePieces(raw, MIN_PIECE_FRACTION)

        // The box stops just above the table; drop the flat bottom onto the table so no height is lost.
        val positions = pieces.positions.copyOf()
        val count = positions.size / 3
        val floor = volume.box.floorY
        val base = BooleanArray(count)
        if (floor != null) {
            for (v in 0 until count) {
                if (positions[v * 3 + 1] < volume.originY + 0.6f * s) {
                    positions[v * 3 + 1] = floor
                    base[v] = true
                }
            }
        }
        progress("Smoothing the surface…")
        val smooth = MeshOps.smooth(positions, pieces.indices, SMOOTHING_ITERATIONS, base)
        return Surface(smooth, pieces.indices, MeshBuilder.vertexNormals(smooth, pieces.indices))
    }

    /** Moves the base centre to the origin and turns the model so the first photo looks at its front (+Z). */
    internal fun toObjectFrame(positions: FloatArray, normals: FloatArray, floorY: Float?, front: CameraPose?) {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (v in 0 until positions.size / 3) {
            minX = minOf(minX, positions[v * 3])
            maxX = maxOf(maxX, positions[v * 3])
            minY = minOf(minY, positions[v * 3 + 1])
            minZ = minOf(minZ, positions[v * 3 + 2])
            maxZ = maxOf(maxZ, positions[v * 3 + 2])
        }
        val centerX = (minX + maxX) / 2
        val centerZ = (minZ + maxZ) / 2
        val baseY = floorY ?: minY
        val angle = if (front != null) atan2(front.x - centerX, front.z - centerZ) else 0f
        val c = cos(angle)
        val sn = sin(angle)
        for (v in 0 until positions.size / 3) {
            val x = positions[v * 3] - centerX
            val z = positions[v * 3 + 2] - centerZ
            positions[v * 3] = x * c - z * sn
            positions[v * 3 + 1] -= baseY
            positions[v * 3 + 2] = x * sn + z * c
            val nx = normals[v * 3]
            val nz = normals[v * 3 + 2]
            normals[v * 3] = nx * c - nz * sn
            normals[v * 3 + 2] = nx * sn + nz * c
        }
    }
}
