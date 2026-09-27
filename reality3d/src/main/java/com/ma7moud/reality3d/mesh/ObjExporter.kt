package com.ma7moud.reality3d.mesh

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ObjExporter {
    fun export(context: Context, mesh: DepthMesh, bitmap: Bitmap): List<Uri> {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = "reality3d_${System.currentTimeMillis()}"
        val obj = File(dir, "$base.obj")
        val mtl = File(dir, "$base.mtl")
        val texture = File(dir, "$base.jpg")
        texture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        mtl.writeText("newmtl Reality3DMaterial\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1.0\nillum 1\nmap_Kd ${texture.name}\n")
        obj.bufferedWriter().use { out ->
            out.appendLine("# Reality3D on-device depth mesh")
            out.appendLine("mtllib ${mtl.name}")
            out.appendLine("o Reality3D")
            var p = 0
            while (p < mesh.positions.size) {
                out.appendLine("v ${mesh.positions[p]} ${mesh.positions[p + 1]} ${mesh.positions[p + 2]}")
                p += 3
            }
            var t = 0
            while (t < mesh.texCoords.size) {
                out.appendLine("vt ${mesh.texCoords[t]} ${mesh.texCoords[t + 1]}")
                t += 2
            }
            out.appendLine("usemtl Reality3DMaterial")
            var i = 0
            while (i < mesh.indices.size) {
                val a = mesh.indices[i] + 1
                val b = mesh.indices[i + 1] + 1
                val c = mesh.indices[i + 2] + 1
                out.appendLine("f $a/$a $b/$b $c/$c")
                i += 3
            }
        }
        return listOf(obj, mtl, texture).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    }
}
