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
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.PhotoSetWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExportFormat(val label: String, val extension: String, val mimeType: String, val description: String) {
    GLB("GLB", "glb", "model/gltf-binary", "One file with the colours. Opens in Windows 3D Viewer, Blender and online glTF viewers."),
    STL("STL", "stl", "model/stl", "For 3D printing: the shape only, in millimetres."),
    OBJ("OBJ", "zip", "application/zip", "OBJ with its colours in one zip, for Blender, SketchUp or Unreal."),
    PHOTOS("Photos", "zip", "application/zip", "The scan photos with the camera positions (in meters), for photogrammetry software on a computer."),
}

class ExportFile(val format: ExportFormat, val fileName: String, val bytes: ByteArray)

object Exporter {

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** [photo] textures single-photo models; [keyframes] are a scan's photos for [ExportFormat.PHOTOS]. */
    fun encode(format: ExportFormat, mesh: Mesh3D, photo: Bitmap?, baseName: String, keyframes: List<Keyframe> = emptyList()): ExportFile {
        val bytes = when (format) {
            ExportFormat.GLB -> GlbWriter.write(mesh, photo?.let(::jpeg), name = baseName)
            ExportFormat.STL -> StlWriter.write(mesh)
            ExportFormat.OBJ -> ObjWriter.writeZip(mesh, photo?.let(::jpeg), baseName)
            ExportFormat.PHOTOS -> PhotoSetWriter.write(keyframes)
        }
        val name = if (format == ExportFormat.PHOTOS) "${baseName}_photos" else baseName
        return ExportFile(format, "$name.${format.extension}", bytes)
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
        return Intent.createChooser(send, "Share ${file.fileName}")
    }
}
