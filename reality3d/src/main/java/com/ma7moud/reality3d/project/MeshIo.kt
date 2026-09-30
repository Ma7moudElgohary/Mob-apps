package com.ma7moud.reality3d.project

import com.ma7moud.reality3d.mesh.Mesh3D
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A compact binary copy of a [Mesh3D], for saved projects. Little-endian, versioned. */
object MeshIo {

    private const val MAGIC = 0x4D443352 // "R3DM"
    private const val VERSION = 1
    private const val HAS_UVS = 1
    private const val HAS_COLORS = 2
    private const val SOLID = 4
    private const val ISOLATED = 8
    private const val REAL_SCALE = 16

    fun write(mesh: Mesh3D): ByteArray {
        val uvs = mesh.uvs
        val colors = mesh.colors
        val floats = mesh.positions.size + mesh.normals.size + (uvs?.size ?: 0) + (colors?.size ?: 0)
        val buffer = ByteBuffer.allocate(20 + floats * 4 + mesh.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        var flags = 0
        if (uvs != null) flags = flags or HAS_UVS
        if (colors != null) flags = flags or HAS_COLORS
        if (mesh.solid) flags = flags or SOLID
        if (mesh.subjectIsolated) flags = flags or ISOLATED
        if (mesh.realScale) flags = flags or REAL_SCALE
        buffer.putInt(MAGIC).putInt(VERSION).putInt(flags).putInt(mesh.vertexCount).putInt(mesh.indices.size)
        buffer.asFloatBuffer().apply {
            put(mesh.positions)
            put(mesh.normals)
            uvs?.let { put(it) }
            colors?.let { put(it) }
        }
        buffer.position(20 + floats * 4)
        buffer.asIntBuffer().put(mesh.indices)
        return buffer.array()
    }

    fun read(bytes: ByteArray): Mesh3D {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 20 && buffer.getInt() == MAGIC) { "not a Reality3D mesh" }
        val version = buffer.getInt()
        require(version == VERSION) { "unsupported mesh version $version" }
        val flags = buffer.getInt()
        val vertices = buffer.getInt()
        val indexCount = buffer.getInt()
        val floats = buffer.asFloatBuffer()
        fun floats(count: Int) = FloatArray(count).also { floats.get(it) }
        val positions = floats(vertices * 3)
        val normals = floats(vertices * 3)
        val uvs = if (flags and HAS_UVS != 0) floats(vertices * 2) else null
        val colors = if (flags and HAS_COLORS != 0) floats(vertices * 3) else null
        buffer.position(20 + floats.position() * 4)
        val indices = IntArray(indexCount).also { buffer.asIntBuffer().get(it) }
        require(indices.all { it in 0 until vertices }) { "the mesh file is damaged" }
        return Mesh3D(
            positions, normals, uvs, indices,
            solid = flags and SOLID != 0,
            subjectIsolated = flags and ISOLATED != 0,
            colors = colors,
            realScale = flags and REAL_SCALE != 0,
        )
    }
}
