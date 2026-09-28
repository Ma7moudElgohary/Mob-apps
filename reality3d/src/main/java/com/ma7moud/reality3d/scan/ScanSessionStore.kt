package com.ma7moud.reality3d.scan

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ScanSessionStore {
    fun save(file: File, volume: SparseTsdfVolume, target: Vector3?, coverage: BooleanArray) {
        val voxels = volume.snapshot()
        val count = voxels.size
        // Header = 5 x 4 bytes + target 3 x 4 bytes + 9 coverage bytes.
        val bytes = 4 * 8 + 9 + count * (3 * 4 + 5 * 4)
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC)
        b.putInt(VERSION)
        b.putFloat(volume.voxelSizeMeters)
        b.putFloat(volume.truncationMeters)
        b.putInt(count)
        b.putFloat(target?.x ?: Float.NaN)
        b.putFloat(target?.y ?: Float.NaN)
        b.putFloat(target?.z ?: Float.NaN)
        repeat(9) { b.put(if (coverage.getOrNull(it) == true) 1 else 0) }
        voxels.forEach { (k, v) ->
            b.putInt(k.x)
            b.putInt(k.y)
            b.putInt(k.z)
            b.putFloat(v.tsdf)
            b.putFloat(v.weight)
            b.putFloat(v.r)
            b.putFloat(v.g)
            b.putFloat(v.b)
        }
        file.parentFile?.mkdirs()
        file.writeBytes(b.array())
    }

    data class Loaded(val volume: SparseTsdfVolume, val target: Vector3?, val coverage: BooleanArray)

    fun load(file: File): Loaded? = runCatching {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC)
        require(b.int == VERSION)
        val voxel = b.float
        val trunc = b.float
        val count = b.int
        require(count >= 0)
        val tx = b.float
        val ty = b.float
        val tz = b.float
        val coverage = BooleanArray(9) { b.get().toInt() != 0 }
        val map = HashMap<SparseTsdfVolume.Key, SparseTsdfVolume.Voxel>(count)
        repeat(count) {
            val key = SparseTsdfVolume.Key(b.int, b.int, b.int)
            map[key] = SparseTsdfVolume.Voxel(b.float, b.float, b.float, b.float, b.float)
        }
        val volume = SparseTsdfVolume(voxel, trunc).apply { load(map) }
        val target = if (tx.isFinite() && ty.isFinite() && tz.isFinite()) Vector3(tx, ty, tz) else null
        Loaded(volume, target, coverage)
    }.getOrNull()

    private const val MAGIC = 0x52334453
    private const val VERSION = 1
}
