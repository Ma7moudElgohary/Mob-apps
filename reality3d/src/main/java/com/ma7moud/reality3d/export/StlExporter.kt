package com.ma7moud.reality3d.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.mesh.DepthMesh
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object StlExporter {
    fun export(context: Context, mesh: DepthMesh): Uri {
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "reality3d_${System.currentTimeMillis()}.stl")
        val triangles = mesh.triangleCount
        val buffer = ByteBuffer.allocate(84 + triangles * 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ByteArray(80)); buffer.putInt(triangles)
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val ia = mesh.indices[i] * 3; val ib = mesh.indices[i + 1] * 3; val ic = mesh.indices[i + 2] * 3
            val ax = mesh.positions[ia]; val ay = mesh.positions[ia + 1]; val az = mesh.positions[ia + 2]
            val bx = mesh.positions[ib]; val by = mesh.positions[ib + 1]; val bz = mesh.positions[ib + 2]
            val cx = mesh.positions[ic]; val cy = mesh.positions[ic + 1]; val cz = mesh.positions[ic + 2]
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az
            var nx = aby * acz - abz * acy; var ny = abz * acx - abx * acz; var nz = abx * acy - aby * acx
            val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-8f); nx /= length; ny /= length; nz /= length
            buffer.putFloat(nx); buffer.putFloat(ny); buffer.putFloat(nz)
            intArrayOf(ia, ib, ic).forEach { p -> buffer.putFloat(mesh.positions[p]); buffer.putFloat(mesh.positions[p + 1]); buffer.putFloat(mesh.positions[p + 2]) }
            buffer.putShort(0); i += 3
        }
        file.writeBytes(buffer.array())
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
