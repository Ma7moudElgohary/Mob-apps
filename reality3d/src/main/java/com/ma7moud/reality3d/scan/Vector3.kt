package com.ma7moud.reality3d.scan

import kotlin.math.sqrt

data class Vector3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vector3 { val l = length().coerceAtLeast(1e-8f); return Vector3(x / l, y / l, z / l) }
}
