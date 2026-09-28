package com.ma7moud.reality3d.mesh

import kotlin.math.max
import kotlin.math.sqrt

object MeshMath {
    fun recalculateNormals(mesh: DepthMesh): DepthMesh {
        val normals = FloatArray(mesh.positions.size)
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val ia = mesh.indices[i] * 3
            val ib = mesh.indices[i + 1] * 3
            val ic = mesh.indices[i + 2] * 3
            val ax = mesh.positions[ia]; val ay = mesh.positions[ia + 1]; val az = mesh.positions[ia + 2]
            val bx = mesh.positions[ib]; val by = mesh.positions[ib + 1]; val bz = mesh.positions[ib + 2]
            val cx = mesh.positions[ic]; val cy = mesh.positions[ic + 1]; val cz = mesh.positions[ic + 2]
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az
            val nx = aby * acz - abz * acy
            val ny = abz * acx - abx * acz
            val nz = abx * acy - aby * acx
            intArrayOf(ia, ib, ic).forEach { index ->
                normals[index] += nx; normals[index + 1] += ny; normals[index + 2] += nz
            }
            i += 3
        }
        var p = 0
        while (p < normals.size) {
            val length = sqrt(normals[p] * normals[p] + normals[p + 1] * normals[p + 1] + normals[p + 2] * normals[p + 2])
            if (length > 1e-8f) {
                normals[p] /= length; normals[p + 1] /= length; normals[p + 2] /= length
            } else {
                normals[p + 2] = 1f
            }
            p += 3
        }
        return mesh.copy(normals = normals)
    }

    fun bounds(mesh: DepthMesh): FloatArray {
        if (mesh.positions.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < mesh.positions.size) {
            val x = mesh.positions[i]; val y = mesh.positions[i + 1]; val z = mesh.positions[i + 2]
            minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
            i += 3
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun scaleToWidth(mesh: DepthMesh, targetWidthMeters: Float): DepthMesh {
        val b = bounds(mesh)
        val width = max(1e-6f, b[3] - b[0])
        val scale = targetWidthMeters / width
        val positions = mesh.positions.copyOf()
        for (i in positions.indices) positions[i] *= scale
        return mesh.copy(positions = positions, unitsToMeters = 1f)
    }

    fun distanceMeters(mesh: DepthMesh, vertexA: Int, vertexB: Int): Float? {
        val scale = mesh.unitsToMeters ?: return null
        if (vertexA !in 0 until mesh.vertexCount || vertexB !in 0 until mesh.vertexCount) return null
        val a = vertexA * 3; val b = vertexB * 3
        val dx = mesh.positions[a] - mesh.positions[b]
        val dy = mesh.positions[a + 1] - mesh.positions[b + 1]
        val dz = mesh.positions[a + 2] - mesh.positions[b + 2]
        return sqrt(dx * dx + dy * dy + dz * dz) * scale
    }
}
