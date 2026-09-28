package com.ma7moud.reality3d.depth

import android.graphics.Bitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min

class DepthAnythingEstimator(
    private val modelFile: File,
    private val backendSelector: LiteRtBackendSelector,
) {
    data class Result(val depth: DepthMap, val backend: InferenceBackend)

    fun estimate(bitmap: Bitmap, requestedBackend: InferenceBackend? = null): Result {
        val input = prepareInput(bitmap)
        val (raw, backend) = backendSelector.infer(modelFile, input, requestedBackend)
        require(raw.size >= OUTPUT_WIDTH * OUTPUT_HEIGHT) {
            "Unexpected Depth Anything V2 output size ${raw.size}"
        }
        val normalized = robustNormalize(raw, OUTPUT_WIDTH * OUTPUT_HEIGHT)
        return Result(DepthMap(OUTPUT_WIDTH, OUTPUT_HEIGHT, normalized), backend)
    }

    fun prepareInput(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        val plane = INPUT_WIDTH * INPUT_HEIGHT
        val data = FloatArray(plane * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            data[i] = (r - 0.485f) / 0.229f
            data[plane + i] = (g - 0.456f) / 0.224f
            data[2 * plane + i] = (b - 0.406f) / 0.225f
        }
        if (scaled !== bitmap) scaled.recycle()
        return data
    }

    private fun robustNormalize(raw: FloatArray, count: Int): FloatArray {
        val finite = FloatArray(count)
        var n = 0
        for (i in 0 until min(count, raw.size)) {
            val value = raw[i]
            if (value.isFinite()) finite[n++] = value
        }
        if (n == 0) return FloatArray(count)
        val sorted = finite.copyOf(n).apply { sort() }
        val low = sorted[((n - 1) * 0.02f).toInt().coerceIn(0, n - 1)]
        val high = sorted[((n - 1) * 0.98f).toInt().coerceIn(0, n - 1)]
        val range = max(1e-6f, high - low)
        return FloatArray(count) { i -> ((raw[i].coerceIn(low, high) - low) / range).coerceIn(0f, 1f) }
    }

    companion object {
        const val INPUT_HEIGHT = 518
        const val INPUT_WIDTH = 686
        const val OUTPUT_HEIGHT = 518
        const val OUTPUT_WIDTH = 686
    }
}

data class DepthMap(val width: Int, val height: Int, val values: FloatArray) {
    operator fun get(x: Int, y: Int): Float = values[y * width + x]
    operator fun set(x: Int, y: Int, value: Float) { values[y * width + x] = value }
    fun copy(): DepthMap = DepthMap(width, height, values.copyOf())
}
