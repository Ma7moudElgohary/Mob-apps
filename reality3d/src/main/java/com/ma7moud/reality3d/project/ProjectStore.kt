package com.ma7moud.reality3d.project

import android.content.Context
import android.graphics.Bitmap
import com.ma7moud.reality3d.mesh.DepthMesh
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class RealityProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val mode: String,
    val status: String,
    val thumbnailPath: String?,
    val meshPath: String?,
    val scanPath: String?,
    val triangleCount: Int,
    val coverage: Int,
)

class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<RealityProject> = root.listFiles { file -> file.isDirectory }
        ?.mapNotNull(::load)
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun create(name: String, mode: String): RealityProject {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val project = RealityProject(id, name, now, now, mode, "draft", null, null, null, 0, 0)
        writeMeta(dir, project)
        return project
    }

    fun saveMesh(project: RealityProject, mesh: DepthMesh, thumbnail: Bitmap?, coverage: Int = project.coverage): RealityProject {
        val dir = File(root, project.id).apply { mkdirs() }
        val meshFile = File(dir, "mesh.r3dm")
        writeMesh(meshFile, mesh)
        val thumbPath = thumbnail?.let {
            File(dir, "thumb.png").also { file ->
                file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }.absolutePath
        } ?: project.thumbnailPath
        val updated = project.copy(
            updatedAt = System.currentTimeMillis(),
            status = "ready",
            thumbnailPath = thumbPath,
            meshPath = meshFile.absolutePath,
            triangleCount = mesh.triangleCount,
            coverage = coverage,
        )
        writeMeta(dir, updated)
        return updated
    }

    fun attachScan(project: RealityProject, scanFile: File, coverage: Int): RealityProject {
        val dir = File(root, project.id).apply { mkdirs() }
        val target = File(dir, "scan.r3ds")
        scanFile.copyTo(target, overwrite = true)
        val updated = project.copy(
            updatedAt = System.currentTimeMillis(),
            scanPath = target.absolutePath,
            status = "scanning",
            coverage = coverage,
        )
        writeMeta(dir, updated)
        return updated
    }

    fun loadMesh(project: RealityProject): DepthMesh? = project.meshPath?.let { readMesh(File(it)) }

    fun delete(project: RealityProject) {
        File(root, project.id).deleteRecursively()
    }

    private fun load(dir: File): RealityProject? = runCatching {
        val json = JSONObject(File(dir, "project.json").readText())
        RealityProject(
            id = json.getString("id"),
            name = json.getString("name"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            mode = json.optString("mode", "quick"),
            status = json.optString("status", "draft"),
            thumbnailPath = json.optString("thumbnailPath").takeIf { it.isNotBlank() },
            meshPath = json.optString("meshPath").takeIf { it.isNotBlank() },
            scanPath = json.optString("scanPath").takeIf { it.isNotBlank() },
            triangleCount = json.optInt("triangleCount", 0),
            coverage = json.optInt("coverage", 0),
        )
    }.getOrNull()

    private fun writeMeta(dir: File, p: RealityProject) {
        val json = JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("createdAt", p.createdAt)
            .put("updatedAt", p.updatedAt)
            .put("mode", p.mode)
            .put("status", p.status)
            .put("thumbnailPath", p.thumbnailPath ?: "")
            .put("meshPath", p.meshPath ?: "")
            .put("scanPath", p.scanPath ?: "")
            .put("triangleCount", p.triangleCount)
            .put("coverage", p.coverage)
        File(dir, "project.json").writeText(json.toString(2))
    }

    private fun writeMesh(file: File, mesh: DepthMesh) {
        val tex = mesh.texCoords
        val normals = mesh.normals
        val colors = mesh.colors ?: FloatArray(0)
        // Header = 9 Ints + 1 Float = 40 bytes.
        val bytes = 4 * 10 + 4 * (mesh.positions.size + tex.size + normals.size + colors.size + mesh.indices.size)
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC)
        b.putInt(VERSION)
        b.putInt(mesh.positions.size)
        b.putInt(tex.size)
        b.putInt(normals.size)
        b.putInt(colors.size)
        b.putInt(mesh.indices.size)
        b.putInt(mesh.columns)
        b.putInt(mesh.rows)
        b.putFloat(mesh.unitsToMeters ?: Float.NaN)
        mesh.positions.forEach(b::putFloat)
        tex.forEach(b::putFloat)
        normals.forEach(b::putFloat)
        colors.forEach(b::putFloat)
        mesh.indices.forEach(b::putInt)
        file.writeBytes(b.array())
    }

    private fun readMesh(file: File): DepthMesh? = runCatching {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC)
        require(b.int == VERSION)
        val pc = b.int
        val tc = b.int
        val nc = b.int
        val cc = b.int
        val ic = b.int
        val cols = b.int
        val rows = b.int
        val scale = b.float
        require(pc >= 0 && tc >= 0 && nc >= 0 && cc >= 0 && ic >= 0)
        val p = FloatArray(pc) { b.float }
        val t = FloatArray(tc) { b.float }
        val n = FloatArray(nc) { b.float }
        val c = FloatArray(cc) { b.float }
        val ind = IntArray(ic) { b.int }
        DepthMesh(p, t, ind, n, c.takeIf { it.isNotEmpty() }, cols, rows, scale.takeIf { it.isFinite() })
    }.getOrNull()

    companion object {
        private const val MAGIC = 0x5233444D
        private const val VERSION = 1
    }
}
