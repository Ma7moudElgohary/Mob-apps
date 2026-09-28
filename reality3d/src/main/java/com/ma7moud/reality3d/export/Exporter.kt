package com.ma7moud.reality3d.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.mesh.GlbWriter
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.ObjWriter
import com.ma7moud.reality3d.mesh.PlyWriter
import com.ma7moud.reality3d.mesh.TextureBaker
import com.ma7moud.reality3d.mesh.StlWriter
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.PhotoSetWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val extension: String, val mimeType: String, val description: String) {
    GLB("GLB", "glb", "model/gltf-binary", "One file with the colours. Opens in Windows 3D Viewer, Blender and online glTF viewers."),
    STL("STL", "stl", "model/stl", "For 3D printing: the shape only, in millimetres."),
    OBJ("OBJ", "zip", "application/zip", "OBJ with its colours in one zip, for Blender, SketchUp or Unreal."),
    PLY("PLY", "ply", "application/octet-stream", "Every point with its colour and normal, plus the triangles, for CloudCompare, MeshLab and other point-cloud tools."),
    UNREAL("Unreal", "zip", "application/zip", "Game-ready: three levels of detail, a collision hull and import notes for Unreal Engine."),
    PHOTOS("Photos", "zip", "application/zip", "The scan photos with the camera positions (in meters), for photogrammetry software on a computer."),
}

class ExportFile(val format: ExportFormat, val fileName: String, val bytes: ByteArray)

/**
 * A single-photo model's texture: the photo baked by [com.ma7moud.reality3d.mesh.TextureBaker] (not
 * premultiplied, the subject mask in alpha) and the [region] of it the model uses (left, top, right, bottom).
 */
class ModelTexture(val bitmap: Bitmap, val region: FloatArray)

object Exporter {

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * [texture] textures single-photo models; [keyframes] are a scan's photos for [ExportFormat.PHOTOS];
     * [budget] sets the triangles of the [ExportFormat.UNREAL] levels of detail.
     *
     * Textured files carry only the part of the photo the model uses. Open reliefs get a PNG whose alpha
     * cuts them out along the subject; closed models, whose rim the cut would open, get a JPEG.
     */
    fun encode(
        format: ExportFormat,
        mesh: Mesh3D,
        texture: ModelTexture?,
        baseName: String,
        keyframes: List<Keyframe> = emptyList(),
        budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM,
    ): ExportFile {
        val cropped = if (texture != null && mesh.uvs != null) TextureBaker.cropUvs(mesh, texture.region) else mesh
        val bytes = when (format) {
            ExportFormat.GLB -> GlbWriter.write(cropped, texture?.let { glbTexture(it, mesh.solid) }, name = baseName)
            ExportFormat.STL -> StlWriter.write(mesh)
            ExportFormat.OBJ -> ObjWriter.writeZip(cropped, texture?.let { encode(it, alpha = false) }, baseName)
            ExportFormat.PLY -> PlyWriter.write(mesh, texture?.let { pixels(it.bitmap) })
            ExportFormat.UNREAL -> GameReadyPack.write(cropped, texture?.let { glbTexture(it, mesh.solid) }, assetName(baseName), budget)
            ExportFormat.PHOTOS -> PhotoSetWriter.write(keyframes)
        }
        val name = when (format) {
            ExportFormat.PHOTOS -> "${baseName}_photos"
            ExportFormat.UNREAL -> "${baseName}_unreal"
            else -> baseName
        }
        return ExportFile(format, "$name.${format.extension}", bytes)
    }

    /** Unreal asset names allow letters, digits and underscores only. */
    internal fun assetName(name: String): String = name.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "Reality3D" }

    private fun pixels(photo: Bitmap): PlyWriter.Photo {
        val argb = IntArray(photo.width * photo.height)
        photo.getPixels(argb, 0, photo.width, 0, 0, photo.width, photo.height)
        return PlyWriter.Photo(photo.width, photo.height, argb)
    }

    private fun glbTexture(texture: ModelTexture, solid: Boolean): GlbWriter.Texture =
        if (solid) {
            GlbWriter.Texture(encode(texture, alpha = false), "image/jpeg")
        } else {
            GlbWriter.Texture(encode(texture, alpha = true), "image/png", alphaMask = true)
        }

    /**
     * The texture's region as a PNG with alpha, or as an opaque JPEG. It is cropped through its pixels:
     * a bitmap without premultiplied alpha can't be drawn onto a canvas.
     */
    private fun encode(texture: ModelTexture, alpha: Boolean): ByteArray {
        val source = texture.bitmap
        val x0 = (texture.region[0] * source.width).toInt().coerceIn(0, source.width - 1)
        val y0 = (texture.region[1] * source.height).toInt().coerceIn(0, source.height - 1)
        val x1 = (texture.region[2] * source.width).roundToInt().coerceIn(x0 + 1, source.width)
        val y1 = (texture.region[3] * source.height).roundToInt().coerceIn(y0 + 1, source.height)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, x0, y0, w, h)
        if (!alpha) for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
        val region = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (alpha) region.isPremultiplied = false
        region.setPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        region.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        region.recycle()
        return out.toByteArray()
    }

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
