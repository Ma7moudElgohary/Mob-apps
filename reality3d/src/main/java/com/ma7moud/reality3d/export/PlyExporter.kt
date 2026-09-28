package com.ma7moud.reality3d.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.mesh.DepthMesh
import java.io.File

object PlyExporter {
    fun export(context: Context, mesh: DepthMesh): Uri {
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "reality3d_${System.currentTimeMillis()}.ply")
        file.bufferedWriter().use { out ->
            out.appendLine("ply"); out.appendLine("format ascii 1.0")
            out.appendLine("element vertex ${mesh.vertexCount}")
            out.appendLine("property float x"); out.appendLine("property float y"); out.appendLine("property float z")
            if (mesh.normals.size == mesh.positions.size) {
                out.appendLine("property float nx"); out.appendLine("property float ny"); out.appendLine("property float nz")
            }
            if (mesh.colors?.size == mesh.vertexCount * 4) {
                out.appendLine("property uchar red"); out.appendLine("property uchar green"); out.appendLine("property uchar blue"); out.appendLine("property uchar alpha")
            }
            out.appendLine("element face ${mesh.triangleCount}"); out.appendLine("property list uchar int vertex_indices"); out.appendLine("end_header")
            for (v in 0 until mesh.vertexCount) {
                val p = v * 3
                out.append("${mesh.positions[p]} ${mesh.positions[p + 1]} ${mesh.positions[p + 2]}")
                if (mesh.normals.size == mesh.positions.size) out.append(" ${mesh.normals[p]} ${mesh.normals[p + 1]} ${mesh.normals[p + 2]}")
                mesh.colors?.takeIf { it.size == mesh.vertexCount * 4 }?.let {
                    val c = v * 4
                    out.append(" ${(it[c] * 255).toInt().coerceIn(0,255)} ${(it[c+1] * 255).toInt().coerceIn(0,255)} ${(it[c+2] * 255).toInt().coerceIn(0,255)} ${(it[c+3] * 255).toInt().coerceIn(0,255)}")
                }
                out.appendLine()
            }
            var i = 0
            while (i + 2 < mesh.indices.size) { out.appendLine("3 ${mesh.indices[i]} ${mesh.indices[i + 1]} ${mesh.indices[i + 2]}"); i += 3 }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
