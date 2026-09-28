package com.ma7moud.reality3d.mesh

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A zip for game engines: three levels of detail simplified from the model, a convex collision hull, and
 * notes for importing them into Unreal Engine. Sizes are in meters, which Unreal turns into centimetres.
 */
object GameReadyPack {

    /** Triangles in the most detailed level; the next levels have half and a quarter as many. */
    enum class Budget(val triangles: Int, val label: String) {
        LIGHT(5_000, "5k"),
        MEDIUM(10_000, "10k"),
        DETAILED(25_000, "25k"),
    }

    class Levels(val lods: List<Mesh3D>, val collision: Mesh3D)

    fun levels(mesh: Mesh3D, budget: Budget): Levels {
        val lod0 = MeshSimplifier.simplify(mesh, budget.triangles)
        val lod1 = MeshSimplifier.simplify(lod0, budget.triangles / 2)
        val lod2 = MeshSimplifier.simplify(lod1, budget.triangles / 4)
        val hull = ConvexHull.of(mesh.positions, MAX_HULL_VERTICES)
        val collision = Mesh3D(
            positions = hull.positions,
            normals = MeshBuilder.vertexNormals(hull.positions, hull.indices),
            uvs = null,
            indices = hull.indices,
            solid = true,
            subjectIsolated = true,
            realScale = mesh.realScale,
        )
        return Levels(listOf(lod0, lod1, lod2), collision)
    }

    fun write(mesh: Mesh3D, texture: GlbWriter.Texture?, name: String, budget: Budget, longestSideMeters: Float = 0.2f): ByteArray {
        val levels = levels(mesh, budget)
        // The collision hull must line up with the render mesh, so it gets the same scale.
        val scale = if (mesh.realScale) 1f else longestSideMeters / kotlin.math.max(mesh.longestSide, 1e-6f)
        val hullSide = levels.collision.longestSide * scale
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(file: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(file))
                zip.write(bytes)
                zip.closeEntry()
            }
            levels.lods.forEachIndexed { index, lod ->
                entry("${name}_LOD$index.glb", GlbWriter.write(lod, texture, longestSideMeters * lod.longestSide / mesh.longestSide, "${name}_LOD$index"))
            }
            entry("UCX_${name}_00.glb", GlbWriter.write(levels.collision, texture = null, longestSideMeters = hullSide, name = "UCX_${name}_00"))
            entry("README.txt", readme(name, levels, mesh, scale).toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    private fun readme(name: String, levels: Levels, mesh: Mesh3D, scale: Float): String {
        val size = mesh.size.map { it * scale * 100f }
        return buildString {
            append("Reality3D game-ready pack: $name\n\n")
            append(String.format(Locale.ROOT, "Size: %.1f x %.1f x %.1f cm (width x height x depth)%s.\n",
                size[0], size[1], size[2], if (mesh.realScale) ", measured by the scan" else ", set in Reality3D"))
            levels.lods.forEachIndexed { index, lod -> append("${name}_LOD$index.glb: ${lod.triangleCount} triangles\n") }
            append("UCX_${name}_00.glb: convex collision hull, ${levels.collision.vertexCount} vertices\n\n")
            append("Unreal Engine 5\n")
            append("1. Drag ${name}_LOD0.glb into the Content Browser and import it (glTF sizes are meters; Unreal\n")
            append("   converts them to centimetres).\n")
            append("2. Open the static mesh, set LOD Import > Import LOD Level 1 and pick ${name}_LOD1.glb; repeat with\n")
            append("   LOD Level 2 and ${name}_LOD2.glb.\n")
            append("3. Collision: in the static mesh editor use Collision > Auto Convex Collision, or combine\n")
            append("   ${name}_LOD0.glb and UCX_${name}_00.glb in Blender and export an FBX; Unreal reads meshes named\n")
            append("   UCX_<mesh name>_00 as their collision.\n")
        }
    }

    // PhysX and Chaos accept up to 255; fewer keeps the hull cheap.
    private const val MAX_HULL_VERTICES = 64
}
