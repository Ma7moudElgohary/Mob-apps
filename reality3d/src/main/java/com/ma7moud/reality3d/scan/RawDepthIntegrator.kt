package com.ma7moud.reality3d.scan

import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.abs

internal data class IntegrationResult(
    val target: Vector3?,
    val meanConfidence: Float,
    val integratedPoints: Int,
    val depthTimestamp: Long?,
)

internal object RawDepthIntegrator {
    private val fullTextureCoordinates = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 0f,
        1f, 1f,
    )

    fun integrate(
        frame: Frame,
        volume: SparseTsdfVolume,
        existingTarget: Vector3?,
        lastDepthTimestamp: Long? = null,
        maxRadiusMeters: Float = 1.35f,
    ): IntegrationResult {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            return IntegrationResult(existingTarget, 0f, 0, lastDepthTimestamp)
        }

        val pose = camera.pose
        val cameraPos = Vector3(pose.tx(), pose.ty(), pose.tz())
        var depthImage: Image? = null
        var confidenceImage: Image? = null
        var cameraImage: Image? = null

        try {
            depthImage = frame.acquireRawDepthImage16Bits()
            val currentDepthTimestamp = depthImage.timestamp
            if (lastDepthTimestamp != null && currentDepthTimestamp == lastDepthTimestamp) {
                // ARCore reprojects the latest raw-depth estimate between actual depth updates.
                // Re-integrating the same estimate would waste work and overweight stale samples.
                return IntegrationResult(existingTarget, 0f, 0, currentDepthTimestamp)
            }

            confidenceImage = frame.acquireRawDepthConfidenceImage()
            cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull()

            val depthPlane = depthImage.planes[0]
            val confidencePlane = confidenceImage.planes[0]
            val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val confidenceBuffer = confidencePlane.buffer
            val depthWidth = depthImage.width
            val depthHeight = depthImage.height

            // Google's Raw Depth reference sample uses texture intrinsics for raw-depth
            // back-projection, scaled to the depth image dimensions.
            val intrinsics = camera.textureIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val intrinsicsDimensions = intrinsics.imageDimensions
            val scaleX = depthWidth.toFloat() / intrinsicsDimensions[0].coerceAtLeast(1)
            val scaleY = depthHeight.toFloat() / intrinsicsDimensions[1].coerceAtLeast(1)
            val fx = focal[0] * scaleX
            val fy = focal[1] * scaleY
            val cx = principal[0] * scaleX
            val cy = principal[1] * scaleY

            val target = existingTarget ?: findTarget(
                depthImage,
                confidenceImage,
                fx,
                fy,
                cx,
                cy,
                pose,
            )

            // The RGB CPU image generally has a different crop/aspect ratio from raw depth.
            // Transform the full camera texture region into CPU-image pixels once per frame and
            // use it to register depth samples to the correct color rows.
            val imageRegion = cameraImage?.let { mapTextureRegionToCpuImage(frame) }

            var confidenceSum = 0f
            var confidenceCount = 0
            var integrated = 0
            val step = when {
                depthWidth >= 500 -> 5
                depthWidth >= 300 -> 4
                else -> 2
            }

            for (y in 0 until depthHeight step step) {
                for (x in 0 until depthWidth step step) {
                    val depthMm = readDepth(
                        depthBuffer,
                        depthPlane.rowStride,
                        depthPlane.pixelStride,
                        x,
                        y,
                    )
                    if (depthMm !in 300..5000) continue

                    val confidence = readConfidence(
                        confidenceBuffer,
                        confidencePlane.rowStride,
                        confidencePlane.pixelStride,
                        x,
                        y,
                    ) / 255f
                    if (confidence < 0.50f) continue

                    val depthMeters = depthMm / 1000f
                    val local = floatArrayOf(
                        (x - cx) / fx * depthMeters,
                        (cy - y) / fy * depthMeters,
                        -depthMeters,
                    )
                    val worldArray = pose.transformPoint(local)
                    val world = Vector3(worldArray[0], worldArray[1], worldArray[2])
                    if (target != null && (world - target).length() > maxRadiusMeters) continue

                    // Quality must describe samples that actually belong to the scan target, not
                    // high-confidence background pixels rejected by the radius gate.
                    confidenceSum += confidence
                    confidenceCount++

                    val color = if (cameraImage != null && imageRegion != null) {
                        sampleRegisteredYuvRgb(
                            image = cameraImage,
                            depthX = x,
                            depthY = y,
                            depthWidth = depthWidth,
                            depthHeight = depthHeight,
                            imageRegion = imageRegion,
                        )
                    } else {
                        null
                    }

                    volume.integrateSurfacePoint(cameraPos, world, confidence, color)
                    integrated++
                }
            }

            return IntegrationResult(
                target = target,
                meanConfidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount,
                integratedPoints = integrated,
                depthTimestamp = currentDepthTimestamp,
            )
        } catch (_: NotYetAvailableException) {
            // Normal while ARCore is warming up or no raw-depth estimate is available yet.
            return IntegrationResult(existingTarget, 0f, 0, lastDepthTimestamp)
        } catch (_: Throwable) {
            return IntegrationResult(existingTarget, 0f, 0, lastDepthTimestamp)
        } finally {
            cameraImage?.close()
            confidenceImage?.close()
            depthImage?.close()
        }
    }

    private fun mapTextureRegionToCpuImage(frame: Frame): FloatArray {
        val imageCoordinates = FloatArray(fullTextureCoordinates.size)
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED,
            fullTextureCoordinates,
            Coordinates2d.IMAGE_PIXELS,
            imageCoordinates,
        )
        return imageCoordinates
    }

    private fun findTarget(
        depth: Image,
        confidence: Image,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        pose: Pose,
    ): Vector3? {
        val depthPlane = depth.planes[0]
        val confidencePlane = confidence.planes[0]
        val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val confidenceBuffer = confidencePlane.buffer
        val centerX = depth.width / 2
        val centerY = depth.height / 2
        var bestDepth = 0
        var bestX = centerX
        var bestY = centerY
        var bestScore = -1f

        for (dy in -8..8) {
            for (dx in -8..8) {
                val x = (centerX + dx).coerceIn(0, depth.width - 1)
                val y = (centerY + dy).coerceIn(0, depth.height - 1)
                val d = readDepth(
                    depthBuffer,
                    depthPlane.rowStride,
                    depthPlane.pixelStride,
                    x,
                    y,
                )
                val confidenceValue = readConfidence(
                    confidenceBuffer,
                    confidencePlane.rowStride,
                    confidencePlane.pixelStride,
                    x,
                    y,
                ) / 255f
                if (d in 350..3500) {
                    val score = confidenceValue - (abs(dx) + abs(dy)) * 0.01f
                    if (score > bestScore) {
                        bestScore = score
                        bestDepth = d
                        bestX = x
                        bestY = y
                    }
                }
            }
        }

        if (bestDepth == 0) return null
        val depthMeters = bestDepth / 1000f
        val local = floatArrayOf(
            (bestX - cx) / fx * depthMeters,
            (cy - bestY) / fy * depthMeters,
            -depthMeters,
        )
        val point = pose.transformPoint(local)
        return Vector3(point[0], point[1], point[2])
    }

    private fun readDepth(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int,
    ): Int {
        val offset = y * rowStride + x * pixelStride
        if (offset + 1 >= buffer.limit()) return 0
        return buffer.getShort(offset).toInt() and 0xFFFF
    }

    private fun readConfidence(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int,
    ): Int {
        val offset = y * rowStride + x * pixelStride
        if (offset >= buffer.limit()) return 0
        return buffer.get(offset).toInt() and 0xFF
    }

    private fun sampleRegisteredYuvRgb(
        image: Image,
        depthX: Int,
        depthY: Int,
        depthWidth: Int,
        depthHeight: Int,
        imageRegion: FloatArray,
    ): Int? {
        if (imageRegion.size < 8 || depthWidth <= 0 || depthHeight <= 0) return null

        val minY = minOf(imageRegion[1], imageRegion[3], imageRegion[5], imageRegion[7])
        val maxY = maxOf(imageRegion[1], imageRegion[3], imageRegion[5], imageRegion[7])
        val regionHeight = maxY - minY
        if (regionHeight <= 0f) return null

        val colorX = (depthX.toFloat() * image.width / depthWidth)
            .toInt()
            .coerceIn(0, image.width - 1)
        val colorY = (minY + depthY.toFloat() * regionHeight / depthHeight)
            .toInt()
            .coerceIn(0, image.height - 1)

        return sampleYuvRgb(image, colorX, colorY)
    }

    private fun sampleYuvRgb(image: Image, x: Int, y: Int): Int? {
        if (image.planes.size < 3) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val halfX = x / 2
        val halfY = y / 2

        val yOffset = y * yPlane.rowStride + x * yPlane.pixelStride
        val uOffset = halfY * uPlane.rowStride + halfX * uPlane.pixelStride
        val vOffset = halfY * vPlane.rowStride + halfX * vPlane.pixelStride
        if (yOffset !in 0 until yPlane.buffer.limit() ||
            uOffset !in 0 until uPlane.buffer.limit() ||
            vOffset !in 0 until vPlane.buffer.limit()
        ) {
            return null
        }

        val yValue = yPlane.buffer.get(yOffset).toInt() and 0xFF
        val uValue = uPlane.buffer.get(uOffset).toInt() and 0xFF
        val vValue = vPlane.buffer.get(vOffset).toInt() and 0xFF
        val yy = yValue.toFloat()
        val uu = uValue - 128f
        val vv = vValue - 128f
        val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
        val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
        val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
