package com.ma7moud.reality3d.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.mesh.GlbWriter
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.ObjWriter
import com.ma7moud.reality3d.mesh.StlWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExportFormat(val label: String, val extension: String, val mimeType: String, val description: String) {
    GLB("GLB", "glb", "model/gltf-binary", "One file with the photo's colours. Opens in Windows 3D Viewer, Blender and online glTF viewers."),
    STL("STL", "stl", "model/stl", "For 3D printing: the shape only, 10 cm on its longest side."),
    OBJ("OBJ", "zip", "application/zip", "OBJ + MTL + texture in one zip, for Blender, SketchUp or Unreal."),
}

class ExportFile(val format: ExportFormat, val fileName: String, val bytes: ByteArray)

object Exporter {

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun encode(format: ExportFormat, mesh: Mesh3D, photo: Bitmap, baseName: String): ExportFile {
        val bytes = when (format) {
            ExportFormat.GLB -> GlbWriter.write(mesh, jpeg(photo), name = baseName)
            ExportFormat.STL -> StlWriter.write(mesh)
            ExportFormat.OBJ -> ObjWriter.writeZip(mesh, jpeg(photo), baseName)
        }
        return ExportFile(format, "$baseName.${format.extension}", bytes)
    }

    private fun jpeg(photo: Bitmap): ByteArray =
        ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    /** Writes the file where the FileProvider serves it and returns a share sheet for it. */
    suspend fun shareIntent(context: Context, file: ExportFile): Intent {
        val target = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "exports").apply { mkdirs() }
            val now = System.currentTimeMillis()
            directory.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
            File(directory, file.fileName).apply { writeBytes(file.bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.fileName)
            clipData = ClipData.newRawUri(file.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${file.format.label} model")
    }
}
