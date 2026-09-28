package com.ma7moud.reality3d.segmentation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class SubjectMask(
    val width: Int,
    val height: Int,
    val confidence: FloatArray,
) {
    operator fun get(x: Int, y: Int): Float = confidence[y * width + x]

    fun sampleNormalized(u: Float, v: Float): Float {
        val x = (u.coerceIn(0f, 1f) * (width - 1)).toInt()
        val y = (v.coerceIn(0f, 1f) * (height - 1)).toInt()
        return get(x, y)
    }

    fun meanConfidence(threshold: Float = 0.05f): Float {
        var sum = 0f
        var count = 0
        confidence.forEach {
            if (it >= threshold) { sum += it; count++ }
        }
        return if (count == 0) 0f else sum / count
    }

    fun bounds(threshold: Float = 0.5f): IntArray {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (get(x, y) >= threshold) {
                minX = min(minX, x); minY = min(minY, y)
                maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        return if (maxX < minX || maxY < minY) intArrayOf(0, 0, width - 1, height - 1)
        else intArrayOf(minX, minY, maxX, maxY)
    }
}

data class SubjectCandidate(
    val id: Int,
    val startX: Int,
    val startY: Int,
    val width: Int,
    val height: Int,
    val mask: SubjectMask,
)

data class SegmentationBundle(
    val foreground: SubjectMask,
    val subjects: List<SubjectCandidate>,
)

class SubjectMasker {
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build(),
            )
            .build(),
    )

    suspend fun segment(bitmap: Bitmap): SegmentationBundle = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(image)
            .addOnSuccessListener { result ->
                runCatching {
                    val foregroundBuffer = requireNotNull(result.foregroundConfidenceMask) {
                        "Subject segmentation returned no foreground mask."
                    }
                    foregroundBuffer.rewind()
                    val foregroundValues = FloatArray(bitmap.width * bitmap.height)
                    foregroundBuffer.get(foregroundValues, 0, min(foregroundValues.size, foregroundBuffer.remaining()))
                    val foreground = SubjectMask(bitmap.width, bitmap.height, foregroundValues)

                    val subjects = result.subjects.mapIndexed { index, subject ->
                        val localBuffer = requireNotNull(subject.confidenceMask)
                        localBuffer.rewind()
                        val local = FloatArray(subject.width * subject.height)
                        localBuffer.get(local, 0, min(local.size, localBuffer.remaining()))
                        val full = FloatArray(bitmap.width * bitmap.height)
                        for (y in 0 until subject.height) for (x in 0 until subject.width) {
                            val targetX = subject.startX + x
                            val targetY = subject.startY + y
                            if (targetX in 0 until bitmap.width && targetY in 0 until bitmap.height) {
                                full[targetY * bitmap.width + targetX] = local[y * subject.width + x]
                            }
                        }
                        SubjectCandidate(index, subject.startX, subject.startY, subject.width, subject.height, SubjectMask(bitmap.width, bitmap.height, full))
                    }
                    SegmentationBundle(foreground, subjects)
                }.onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    fun close() = segmenter.close()
}
