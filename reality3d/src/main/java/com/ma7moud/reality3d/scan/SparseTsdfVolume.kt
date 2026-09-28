package com.ma7moud.reality3d.scan

import kotlin.math.floor

class SparseTsdfVolume(
    val voxelSizeMeters: Float = 0.012f,
    val truncationMeters: Float = 0.045f,
) {
    data class Key(val x: Int, val y: Int, val z: Int)
    data class Voxel(
        var tsdf: Float = 1f,
        var weight: Float = 0f,
        var r: Float = 0f,
        var g: Float = 0f,
        var b: Float = 0f,
    )

    private val voxels = HashMap<Key, Voxel>()

    @get:Synchronized
    val size: Int get() = voxels.size

    @Synchronized
    fun snapshot(): Map<Key, Voxel> = voxels.mapValues { (_, v) -> v.copy() }

    @Synchronized
    fun integrateSurfacePoint(camera: Vector3, surface: Vector3, confidence: Float, color: Int? = null) {
        val conf = confidence.coerceIn(0.05f, 1f)
        val ray = surface - camera
        val distance = ray.length()
        if (distance < 0.15f || distance > 5.0f) return
        val direction = ray * (1f / distance)
        val start = (distance - truncationMeters).coerceAtLeast(0.05f)
        val end = distance + truncationMeters
        var t = start
        while (t <= end) {
            val p = camera + direction * t
            val signed = (distance - t) / truncationMeters
            integrateVoxel(p, signed.coerceIn(-1f, 1f), conf, color)
            t += voxelSizeMeters
        }
    }

    private fun integrateVoxel(p: Vector3, tsdf: Float, confidence: Float, color: Int?) {
        val key = worldToKey(p)
        val voxel = voxels.getOrPut(key) { Voxel() }
        val newWeight = (voxel.weight + confidence).coerceAtMost(64f)
        val oldFactor = if (newWeight == 0f) 0f else voxel.weight / newWeight
        val newFactor = confidence / newWeight
        voxel.tsdf = voxel.tsdf * oldFactor + tsdf * newFactor
        color?.let {
            val rr = ((it shr 16) and 0xFF) / 255f
            val gg = ((it shr 8) and 0xFF) / 255f
            val bb = (it and 0xFF) / 255f
            voxel.r = voxel.r * oldFactor + rr * newFactor
            voxel.g = voxel.g * oldFactor + gg * newFactor
            voxel.b = voxel.b * oldFactor + bb * newFactor
        }
        voxel.weight = newWeight
    }

    fun worldToKey(p: Vector3): Key = Key(
        floor(p.x / voxelSizeMeters).toInt(),
        floor(p.y / voxelSizeMeters).toInt(),
        floor(p.z / voxelSizeMeters).toInt(),
    )

    fun keyToWorld(k: Key): Vector3 = Vector3(
        k.x * voxelSizeMeters,
        k.y * voxelSizeMeters,
        k.z * voxelSizeMeters,
    )

    @Synchronized
    fun load(snapshot: Map<Key, Voxel>) {
        voxels.clear()
        snapshot.forEach { (k, v) -> voxels[k] = v.copy() }
    }
}
