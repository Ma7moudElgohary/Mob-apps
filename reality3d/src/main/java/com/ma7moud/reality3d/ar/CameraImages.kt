package com.ma7moud.reality3d.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteOrder

/** Copies of ARCore camera images, made quickly on the GL thread so the images can be released at once. */
internal object CameraImages {

    /** A DEPTH16 image as millimetres per pixel. */
    fun copyDepth(image: Image): ShortArray {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val depth = ShortArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) depth[y * width + x] = buffer.getShort(row + x * pixelStride)
        }
        return depth
    }

    /** A YUV_420_888 camera image in NV21 order, ready for JPEG compression on another thread. */
    class Nv21(val width: Int, val height: Int, private val data: ByteArray) {
        fun toJpeg(quality: Int): ByteArray {
            val output = ByteArrayOutputStream(width * height / 4)
            YuvImage(data, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, output)
            return output.toByteArray()
        }
    }

    fun copyNv21(image: Image): Nv21 {
        val width = image.width and 1.inv()
        val height = image.height and 1.inv()
        val out = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            (yBuffer as Buffer).position(row * yPlane.rowStride)
            if (yPlane.pixelStride == 1) {
                yBuffer.get(out, row * width, width)
            } else {
                for (col in 0 until width) out[row * width + col] = yBuffer.get(row * yPlane.rowStride + col * yPlane.pixelStride)
            }
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        var offset = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[offset++] = vBuffer.get(row * v.rowStride + col * v.pixelStride)
                out[offset++] = uBuffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return Nv21(width, height, out)
    }
}
