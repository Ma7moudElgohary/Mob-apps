package com.ma7moud.reality3d.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.quality.QualityReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

enum class ProjectKind { PHOTO, SCAN }

/** A saved model as listed in the gallery. */
class ProjectInfo(
    val id: String,
    val name: String,
    val kind: ProjectKind,
    val createdAt: Long,
    val triangles: Int,
    /** Width, height and depth in meters. */
    val size: FloatArray,
    /** The size was measured by a scan or set by hand, rather than assumed. */
    val sizeKnown: Boolean,
    /** How good the capture was, or null when it wasn't rated. */
    val quality: QualityReport?,
    val thumbnail: File,
)

/** A saved model, opened. */
class Project(
    val info: ProjectInfo,
    val mesh: Mesh3D,
    /** The model's texture (subject mask in alpha, not premultiplied) and the part of it the model uses. */
    val texture: Bitmap?,
    val textureRegion: FloatArray?,
    /** The original photo, for comparing. */
    val photo: Bitmap?,
    val metersPerUnit: Float,
)

/** What to save. */
class ProjectDraft(
    val name: String,
    val kind: ProjectKind,
    val mesh: Mesh3D,
    val thumbnail: Bitmap,
    val metersPerUnit: Float,
    val sizeKnown: Boolean,
    val quality: QualityReport? = null,
    val texture: Bitmap? = null,
    val textureRegion: FloatArray? = null,
    val photo: Bitmap? = null,
)

/**
 * Saved models, each in its own folder under [root]: `meta.json`, `mesh.bin` ([MeshIo]), `thumb.jpg`
 * and, for single-photo models, `texture.png` and `photo.jpg`. Blocking: call from a background thread.
 */
class ProjectStore(private val root: File) {

    private val _projects = MutableStateFlow<List<ProjectInfo>?>(null)

    /** Newest first; null until [refresh] has read the folder. */
    val projects: StateFlow<List<ProjectInfo>?> = _projects.asStateFlow()

    fun refresh(): List<ProjectInfo> {
        val list = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir -> runCatching { readInfo(dir) }.getOrNull() }
            .sortedByDescending { it.createdAt }
        _projects.value = list
        return list
    }

    fun save(draft: ProjectDraft): ProjectInfo {
        val id = "p" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6)
        val dir = File(root, id)
        val staging = File(root, ".$id.tmp")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("couldn't create the project folder")
        try {
            File(staging, MESH).writeBytes(MeshIo.write(draft.mesh))
            writeImage(draft.thumbnail, File(staging, THUMB), Bitmap.CompressFormat.JPEG, 85)
            draft.texture?.let { writeImage(it, File(staging, TEXTURE), Bitmap.CompressFormat.PNG, 100) }
            draft.photo?.let { writeImage(it, File(staging, PHOTO), Bitmap.CompressFormat.JPEG, 92) }
            val size = draft.mesh.size.map { it * draft.metersPerUnit }
            val meta = JSONObject()
                .put("name", draft.name)
                .put("kind", draft.kind.name)
                .put("createdAt", System.currentTimeMillis())
                .put("triangles", draft.mesh.triangleCount)
                .put("metersPerUnit", draft.metersPerUnit.toDouble())
                .put("size", JSONArray(size.map { it.toDouble() }))
                .put("sizeKnown", draft.sizeKnown)
            draft.quality?.let { quality ->
                meta.put("quality", quality.score).put("qualityIssues", JSONArray(quality.issues))
            }
            draft.textureRegion?.let { region -> meta.put("textureRegion", JSONArray(region.map { it.toDouble() })) }
            File(staging, META).writeText(meta.toString())
            // Appears whole or not at all.
            if (!staging.renameTo(dir)) throw IOException("couldn't store the project")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        refresh()
        return readInfo(dir)
    }

    fun load(id: String): Project {
        val dir = folder(id)
        val info = readInfo(dir)
        val meta = JSONObject(File(dir, META).readText())
        val mesh = MeshIo.read(File(dir, MESH).readBytes())
        val texture = File(dir, TEXTURE).takeIf { it.exists() }?.let { file ->
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPremultiplied = false })
        }
        val photo = File(dir, PHOTO).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val region = meta.optJSONArray("textureRegion")?.let { array -> FloatArray(array.length()) { array.getDouble(it).toFloat() } }
        return Project(info, mesh, texture, region, photo, meta.getDouble("metersPerUnit").toFloat())
    }

    fun rename(id: String, name: String) = updateMeta(id) { it.put("name", name.trim().ifEmpty { "Untitled" }.take(MAX_NAME)) }

    /** Sets the real size of one model unit, from a measurement made on the saved model. */
    fun setScale(id: String, metersPerUnit: Float, mesh: Mesh3D) = updateMeta(id) { meta ->
        meta.put("metersPerUnit", metersPerUnit.toDouble())
            .put("size", JSONArray(mesh.size.map { (it * metersPerUnit).toDouble() }))
            .put("sizeKnown", true)
    }

    fun delete(id: String) {
        folder(id).deleteRecursively()
        refresh()
    }

    private fun updateMeta(id: String, change: (JSONObject) -> Unit) {
        val file = File(folder(id), META)
        val meta = JSONObject(file.readText())
        change(meta)
        val temp = File(file.parentFile, "$META.tmp")
        temp.writeText(meta.toString())
        if (!temp.renameTo(file)) throw IOException("couldn't update the project")
        refresh()
    }

    private fun folder(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9]+"))) { "bad project id" }
        return File(root, id).also { if (!it.isDirectory) throw IOException("that model is gone") }
    }

    private fun readInfo(dir: File): ProjectInfo {
        val meta = JSONObject(File(dir, META).readText())
        val size = meta.getJSONArray("size")
        return ProjectInfo(
            id = dir.name,
            name = meta.getString("name"),
            kind = ProjectKind.valueOf(meta.getString("kind")),
            createdAt = meta.getLong("createdAt"),
            triangles = meta.getInt("triangles"),
            size = FloatArray(3) { size.getDouble(it).toFloat() },
            sizeKnown = meta.optBoolean("sizeKnown", false),
            quality = if (!meta.has("quality")) null else QualityReport(
                meta.getInt("quality"),
                meta.optJSONArray("qualityIssues")?.let { issues -> List(issues.length()) { issues.getString(it) } }.orEmpty(),
            ),
            thumbnail = File(dir, THUMB),
        )
    }

    private fun writeImage(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat, quality: Int) {
        file.outputStream().use { if (!bitmap.compress(format, quality, it)) throw IOException("couldn't write ${file.name}") }
    }

    companion object {
        private const val META = "meta.json"
        private const val MESH = "mesh.bin"
        private const val THUMB = "thumb.jpg"
        private const val TEXTURE = "texture.png"
        private const val PHOTO = "photo.jpg"
        private const val MAX_NAME = 60
    }
}
