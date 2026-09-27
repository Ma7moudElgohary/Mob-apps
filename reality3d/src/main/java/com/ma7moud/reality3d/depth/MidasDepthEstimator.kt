package com.ma7moud.reality3d.depth

import android.graphics.Bitmap
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs MiDaS v2.1 small (256×256 RGB, ImageNet-normalised, NHWC) on the CPU. */
class MidasDepthEstimator(private val modelFile: File) : Closeable {

    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(SIZE) { FloatArray(SIZE) } }
    private val pixels = IntArray(SIZE * SIZE)
    private var interpreter: Interpreter? = null

    /** Depth for the whole photo, scaled to 0..1 (1 = nearest). */
    @Synchronized
    fun estimate(photo: Bitmap): DepthMap {
        val scaled = photo.scale(SIZE, SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== photo) scaled.recycle()
        input.clear()
        for (pixel in pixels) {
            input.putFloat((((pixel shr 16) and 0xFF) / 255f - 0.485f) / 0.229f)
            input.putFloat((((pixel shr 8) and 0xFF) / 255f - 0.456f) / 0.224f)
            input.putFloat(((pixel and 0xFF) / 255f - 0.406f) / 0.225f)
        }
        run()
        val values = FloatArray(SIZE * SIZE)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (y in 0 until SIZE) {
            val row = output[0][y]
            for (x in 0 until SIZE) {
                val value = row[x]
                values[y * SIZE + x] = value
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f
        for (i in values.indices) values[i] = (values[i] - min) / range
        return DepthMap(SIZE, SIZE, values)
    }

    private fun run() {
        interpreter?.let {
            input.rewind()
            it.run(input, output)
            return
        }
        // Some LiteRT builds cannot run this fp16 graph through XNNPACK; the plain CPU kernels always can.
        interpreter = try {
            open(useXnnpack = true)
        } catch (e: Exception) {
            open(useXnnpack = false)
        }
    }

    private fun open(useXnnpack: Boolean): Interpreter {
        val created = Interpreter(modelFile, Interpreter.Options().setNumThreads(THREADS).setUseXNNPACK(useXnnpack))
        try {
            input.rewind()
            created.run(input, output)
            return created
        } catch (e: Exception) {
            created.close()
            throw e
        }
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private companion object {
        const val SIZE = 256
        const val THREADS = 4
    }
}
