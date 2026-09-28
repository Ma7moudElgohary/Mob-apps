package com.ma7moud.reality3d.texture

import android.graphics.Bitmap
import android.graphics.Color
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.pow

object TextureBuilder {
    fun masked(source: Bitmap, mask: SubjectMask, gamma: Float = 0.75f): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val i = y * source.width + x
            val u = x.toFloat() / (source.width - 1).coerceAtLeast(1)
            val v = y.toFloat() / (source.height - 1).coerceAtLeast(1)
            val confidence = mask.sampleNormalized(u, v).coerceIn(0f, 1f).pow(gamma)
            val p = pixels[i]
            pixels[i] = Color.argb(
                (confidence * 255f).toInt().coerceIn(0, 255),
                Color.red(p), Color.green(p), Color.blue(p),
            )
        }
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }
}
