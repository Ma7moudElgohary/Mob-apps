package com.ma7moud.reality3d.segmentation

import kotlin.math.max
import kotlin.math.min

/** One object the segmenter told apart: its confidence mask over its own box in the photo. */
class Subject(val left: Int, val top: Int, val width: Int, val height: Int, val confidence: FloatArray) {
    init {
        require(width > 0 && height > 0 && confidence.size == width * height)
    }

    /** Confidence at photo pixel ([x], [y]); zero outside the box. */
    fun at(x: Int, y: Int): Float {
        val lx = x - left
        val ly = y - top
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return 0f
        return confidence[ly * width + lx]
    }

    /** Pixels that belong to the object. */
    val area: Int by lazy { confidence.count { it >= 0.5f } }
}

/** What the segmenter found in a photo: the combined foreground and every object on its own. */
class Segmentation(val photoWidth: Int, val photoHeight: Int, val foreground: SubjectMask?, val subjects: List<Subject>) {

    /**
     * The mask of the [selected] objects (indices into [subjects]). An empty selection, or all of them,
     * means the whole foreground.
     */
    fun maskFor(selected: Set<Int>): SubjectMask? {
        val chosen = selected.filter { it in subjects.indices }
        if (chosen.isEmpty() || chosen.size == subjects.size) return foreground ?: combine(subjects.indices.toList())
        return combine(chosen)
    }

    private fun combine(indices: List<Int>): SubjectMask? {
        if (indices.isEmpty()) return null
        val values = FloatArray(photoWidth * photoHeight)
        for (index in indices) {
            val subject = subjects[index]
            val x0 = max(0, subject.left)
            val y0 = max(0, subject.top)
            val x1 = min(photoWidth, subject.left + subject.width)
            val y1 = min(photoHeight, subject.top + subject.height)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val i = y * photoWidth + x
                    val c = subject.at(x, y)
                    if (c > values[i]) values[i] = c
                }
            }
        }
        return SubjectMask(photoWidth, photoHeight, values)
    }

    /** The object under normalised photo point ([u], [v]), or null over the background. */
    fun subjectAt(u: Float, v: Float): Int? {
        val x = (u * photoWidth).toInt().coerceIn(0, photoWidth - 1)
        val y = (v * photoHeight).toInt().coerceIn(0, photoHeight - 1)
        var best: Int? = null
        var bestConfidence = 0.5f
        subjects.forEachIndexed { index, subject ->
            val c = subject.at(x, y)
            if (c >= bestConfidence) {
                bestConfidence = c
                best = index
            }
        }
        return best
    }
}
