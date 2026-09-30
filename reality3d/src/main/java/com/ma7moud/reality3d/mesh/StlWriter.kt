package com.ma7moud.reality3d.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Binary STL for 3D printing, in millimetres. The model is turned Z-up (the slicer convention), with
 * its front facing -Y, and placed on the build plate (lowest point at Z = 0). Real-scale scans keep
 * their measured size; other models get [longestSideMm] on their longest side.
 */
object StlWriter {

    fun write(mesh: Mesh3D, longestSideMm: Float = 100f): ByteArray {
        val scale = if (mesh.realScale) 1000f else longestSideMm / max(mesh.longestSide, 1e-6f)
        val b = mesh.bounds
        val centerX = (b[0] + b[3]) / 2
        val centerZ = (b[2] + b[5]) / 2
        val floor = b[1]
        // Mesh (x, y, z) with y up becomes STL (x, -z, y) with z up.
        fun px(i: Int) = (mesh.positions[i * 3] - centerX) * scale
        fun py(i: Int) = -(mesh.positions[i * 3 + 2] - centerZ) * scale
        fun pz(i: Int) = (mesh.positions[i * 3 + 1] - floor) * scale

        val triangles = mesh.triangleCount
        val out = ByteBuffer.allocate(84 + triangles * 50).order(ByteOrder.LITTLE_ENDIAN)
        // The header must not start with "solid", or some readers take the file for ASCII STL.
        val header = "Reality3D binary STL, millimetres".toByteArray(Charsets.US_ASCII).copyOf(80)
        out.put(header)
        out.putInt(triangles)
        for (t in 0 until triangles) {
            val a = mesh.indices[t * 3]
            val c1 = mesh.indices[t * 3 + 1]
            val c2 = mesh.indices[t * 3 + 2]
            val ux = px(c1) - px(a)
            val uy = py(c1) - py(a)
            val uz = pz(c1) - pz(a)
            val vx = px(c2) - px(a)
            val vy = py(c2) - py(a)
            val vz = pz(c2) - pz(a)
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0f) {
                nx /= length
                ny /= length
                nz /= length
            }
            out.putFloat(nx).putFloat(ny).putFloat(nz)
            for (v in intArrayOf(a, c1, c2)) out.putFloat(px(v)).putFloat(py(v)).putFloat(pz(v))
            out.putShort(0)
        }
        return out.array()
    }
}
