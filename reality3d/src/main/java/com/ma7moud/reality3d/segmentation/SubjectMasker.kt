package com.ma7moud.reality3d.segmentation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SubjectMasker {
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build(),
    )

    suspend fun segment(bitmap: Bitmap): SubjectMask = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(image)
            .addOnSuccessListener { result ->
                runCatching {
                    val buffer = requireNotNull(result.foregroundConfidenceMask) {
                        "Subject segmentation returned no foreground mask."
                    }
                    buffer.rewind()
                    val expected = bitmap.width * bitmap.height
                    val values = FloatArray(expected)
                    val count = minOf(expected, buffer.remaining())
                    buffer.get(values, 0, count)
                    SubjectMask(bitmap.width, bitmap.height, values)
                }.onSuccess {
                    if (continuation.isActive) continuation.resume(it)
                }.onFailure {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    fun close() = segmenter.close()
}

data class SubjectMask(
    val width: Int,
    val height: Int,
    val confidence: FloatArray,
) {
    operator fun get(x: Int, y: Int): Float = confidence[y * width + x]
}
