package com.ma7moud.reality3d.depth

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MidasDepthEstimator(private val modelFile: File) {
    fun estimate(bitmap: Bitmap): DepthMap {
        val size = 256
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            input.putFloat((r - 0.485f) / 0.229f)
            input.putFloat((g - 0.456f) / 0.224f)
            input.putFloat((b - 0.406f) / 0.225f)
        }
        input.rewind()
        val output = Array(1) { Array(size) { FloatArray(size) } }
        Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(4) }).use { it.run(input, output) }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (y in 0 until size) for (x in 0 until size) {
            val value = output[0][y][x]
            if (value < min) min = value
            if (value > max) max = value
        }
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f
        val values = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) values[y * size + x] = ((output[0][y][x] - min) / range).coerceIn(0f, 1f)
        return DepthMap(size, size, values)
    }
}

data class DepthMap(val width: Int, val height: Int, val values: FloatArray) {
    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}
