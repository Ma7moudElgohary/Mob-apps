package com.ma7moud.reality3d.segmentation

import com.ma7moud.reality3d.depth.bilinear

/** Per-pixel confidence (0..1) that a pixel belongs to the photographed subject. */
class SubjectMask(val width: Int, val height: Int, val confidence: FloatArray) {
    init {
        require(width > 0 && height > 0 && confidence.size == width * height) { "Mask size mismatch" }
    }

    operator fun get(x: Int, y: Int): Float = confidence[y * width + x]

    /** Bilinear sample at normalised photo coordinates (0..1 on both axes). */
    fun sample(u: Float, v: Float): Float = bilinear(confidence, width, height, u, v)

    /** Fraction of the photo that is subject. */
    val coverage: Float by lazy { confidence.count { it >= 0.5f }.toFloat() / confidence.size }
}
