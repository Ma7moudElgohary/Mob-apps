package com.ma7moud.reality3d.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshOptimizer

object GameReadyExporter {
    fun exportUnrealPack(context: Context, mesh: DepthMesh, texture: Bitmap, targetTriangles: Int): List<Uri> {
        val pack = MeshOptimizer.gameReady(mesh, targetTriangles)
        return listOf(
            GlbExporter.export(context, pack.lod0, texture, "Reality3D_LOD0"),
            GlbExporter.export(context, pack.lod1, texture, "Reality3D_LOD1"),
            GlbExporter.export(context, pack.lod2, texture, "Reality3D_LOD2"),
            GlbExporter.export(context, pack.collision, null, "Reality3D_Collision"),
        )
    }
}
