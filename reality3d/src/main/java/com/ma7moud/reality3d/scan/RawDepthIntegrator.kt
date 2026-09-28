package com.ma7moud.reality3d.scan

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import java.nio.ByteOrder
import kotlin.math.abs

internal data class IntegrationResult(
    val target: Vector3?,
    val meanConfidence: Float,
    val integratedPoints: Int,
)

internal object RawDepthIntegrator {
    fun integrate(frame: Frame, volume: SparseTsdfVolume, existingTarget: Vector3?, maxRadiusMeters: Float = 1.35f): IntegrationResult {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return IntegrationResult(existingTarget, 0f, 0)
        val pose = camera.pose
        val cameraPos = Vector3(pose.tx(), pose.ty(), pose.tz())
        var depthImage: Image? = null
        var confidenceImage: Image? = null
        var cameraImage: Image? = null
        try {
            depthImage = frame.acquireRawDepthImage16Bits()
            confidenceImage = frame.acquireRawDepthConfidenceImage()
            cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull()
            val depthPlane = depthImage.planes[0]
            val confidencePlane = confidenceImage.planes[0]
            val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val confBuffer = confidencePlane.buffer
            val dw = depthImage.width; val dh = depthImage.height

            val intrinsics = camera.imageIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            val sx = dw.toFloat() / dims[0].coerceAtLeast(1)
            val sy = dh.toFloat() / dims[1].coerceAtLeast(1)
            val fx = focal[0] * sx; val fy = focal[1] * sy
            val cx = principal[0] * sx; val cy = principal[1] * sy

            var target = existingTarget ?: findTarget(depthImage, confidenceImage, fx, fy, cx, cy, pose)
            var confidenceSum = 0f
            var confidenceCount = 0
            var integrated = 0
            val step = when {
                dw >= 500 -> 5
                dw >= 300 -> 4
                else -> 2
            }
            for (y in 0 until dh step step) {
                for (x in 0 until dw step step) {
                    val depthMm = readDepth(depthBuffer, depthPlane.rowStride, depthPlane.pixelStride, x, y)
                    if (depthMm !in 300..5000) continue
                    val conf = readConfidence(confBuffer, confidencePlane.rowStride, confidencePlane.pixelStride, x, y) / 255f
                    if (conf < 0.50f) continue
                    confidenceSum += conf; confidenceCount++
                    val z = depthMm / 1000f
                    val local = floatArrayOf((x - cx) / fx * z, -(y - cy) / fy * z, -z)
                    val worldArray = pose.transformPoint(local)
                    val world = Vector3(worldArray[0], worldArray[1], worldArray[2])
                    if (target != null && (world - target).length() > maxRadiusMeters) continue
                    val color = cameraImage?.let { sampleYuvRgb(it, x.toFloat() / dw, y.toFloat() / dh) }
                    volume.integrateSurfacePoint(cameraPos, world, conf, color)
                    integrated++
                }
            }
            return IntegrationResult(target, if (confidenceCount == 0) 0f else confidenceSum / confidenceCount, integrated)
        } catch (_: Throwable) {
            return IntegrationResult(existingTarget, 0f, 0)
        } finally {
            cameraImage?.close(); confidenceImage?.close(); depthImage?.close()
        }
    }

    private fun findTarget(depth: Image, confidence: Image, fx: Float, fy: Float, cx: Float, cy: Float, pose: com.google.ar.core.Pose): Vector3? {
        val dp = depth.planes[0]; val cp = confidence.planes[0]
        val db = dp.buffer.order(ByteOrder.LITTLE_ENDIAN); val cb = cp.buffer
        val centerX = depth.width / 2; val centerY = depth.height / 2
        var bestDepth = 0
        var bestX = centerX; var bestY = centerY; var bestScore = -1f
        for (dy in -8..8) for (dx in -8..8) {
            val x = (centerX + dx).coerceIn(0, depth.width - 1); val y = (centerY + dy).coerceIn(0, depth.height - 1)
            val d = readDepth(db, dp.rowStride, dp.pixelStride, x, y)
            val c = readConfidence(cb, cp.rowStride, cp.pixelStride, x, y) / 255f
            if (d in 350..3500) {
                val score = c - (abs(dx) + abs(dy)) * 0.01f
                if (score > bestScore) { bestScore = score; bestDepth = d; bestX = x; bestY = y }
            }
        }
        if (bestDepth == 0) return null
        val z = bestDepth / 1000f
        val local = floatArrayOf((bestX - cx) / fx * z, -(bestY - cy) / fy * z, -z)
        val p = pose.transformPoint(local)
        return Vector3(p[0], p[1], p[2])
    }

    private fun readDepth(buffer: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
        val offset = y * rowStride + x * pixelStride
        if (offset + 1 >= buffer.limit()) return 0
        return buffer.getShort(offset).toInt() and 0xFFFF
    }

    private fun readConfidence(buffer: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
        val offset = y * rowStride + x * pixelStride
        if (offset >= buffer.limit()) return 0
        return buffer.get(offset).toInt() and 0xFF
    }

    private fun sampleYuvRgb(image: Image, u: Float, v: Float): Int {
        val x = (u.coerceIn(0f, 0.999f) * image.width).toInt(); val y = (v.coerceIn(0f, 0.999f) * image.height).toInt()
        val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
        val yValue = yp.buffer.get(y * yp.rowStride + x * yp.pixelStride).toInt() and 0xFF
        val ux = x / 2; val uy = y / 2
        val uValue = up.buffer.get(uy * up.rowStride + ux * up.pixelStride).toInt() and 0xFF
        val vValue = vp.buffer.get(uy * vp.rowStride + ux * vp.pixelStride).toInt() and 0xFF
        val yy = yValue.toFloat(); val uu = uValue - 128f; val vv = vValue - 128f
        val r = (yy + 1.402f * vv).toInt().coerceIn(0,255)
        val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0,255)
        val b = (yy + 1.772f * uu).toInt().coerceIn(0,255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
