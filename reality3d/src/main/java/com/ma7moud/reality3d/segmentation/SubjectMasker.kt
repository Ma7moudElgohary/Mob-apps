package com.ma7moud.reality3d.segmentation

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.diagnostics.Step
import com.ma7moud.reality3d.util.awaitResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separates the photographed objects from their background. */
interface SubjectSegmenterEngine {
    /**
     * The foreground and each object on its own, or null when nothing could be told apart from the
     * background.
     */
    suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): Segmentation?
}

/**
 * ML Kit subject segmentation. Its model comes from Google Play services and is fetched on first use. Its native
 * code runs inside the app, so if it ever crashes the app, [diagnostics] turns off telling objects apart, and
 * then segmentation itself, on this phone.
 */
class MlKitSubjectMasker(private val context: Context, private val diagnostics: Diagnostics) : SubjectSegmenterEngine {

    private val objectsSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build(),
        )
    }
    private val foregroundSegmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }
    private val installer by lazy { ModuleInstall.getClient(context) }

    override suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): Segmentation? {
        if (diagnostics.isOff(Fallback.NO_SEGMENTATION)) return null
        val objects = !diagnostics.isOff(Fallback.ONE_OBJECT)
        val segmenter = if (objects) objectsSegmenter else foregroundSegmenter
        ensureModel(segmenter, onProgress)
        onProgress("Separating the subject from the background…", null)
        val result = diagnostics.during(if (objects) Step.FIND_OBJECTS else Step.SEPARATE_OBJECT) {
            // If ML Kit's own thread fails, its task never finishes; carry on without it rather than wait forever.
            withTimeoutOrNull(PROCESS_TIMEOUT_MS) { segmenter.process(InputImage.fromBitmap(photo, 0)).awaitResult() }
        } ?: return null
        val foreground = result.foregroundConfidenceMask?.let { buffer ->
            buffer.rewind()
            val count = photo.width * photo.height
            if (buffer.remaining() < count) return@let null
            val values = FloatArray(count)
            buffer.get(values)
            SubjectMask(photo.width, photo.height, values).takeIf { it.coverage in MIN_COVERAGE..MAX_COVERAGE }
        } ?: return null
        // Without separate objects, the whole foreground is the one object.
        if (!objects) return Segmentation(photo.width, photo.height, foreground, listOf(Subject(0, 0, photo.width, photo.height, foreground.confidence)))
        val subjects = result.subjects.mapNotNull { subject ->
            val buffer = subject.confidenceMask ?: return@mapNotNull null
            if (subject.width <= 0 || subject.height <= 0) return@mapNotNull null
            buffer.rewind()
            val count = subject.width * subject.height
            if (buffer.remaining() < count) return@mapNotNull null
            val values = FloatArray(count)
            buffer.get(values)
            Subject(subject.startX, subject.startY, subject.width, subject.height, values)
        }.filter { it.area >= photo.width * photo.height * MIN_COVERAGE }.sortedByDescending { it.area }
        return Segmentation(photo.width, photo.height, foreground, subjects)
    }

    private suspend fun ensureModel(segmenter: SubjectSegmenter, onProgress: (String, Float?) -> Unit) {
        val available = try {
            installer.areModulesAvailable(segmenter).awaitResult().areModulesAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true // Let process() report the real problem.
        }
        if (available) return
        onProgress(DOWNLOAD_LABEL, null)
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: InstallStatusListener
            fun finish(error: Exception?) {
                installer.unregisterListener(listener)
                if (!continuation.isActive) return
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
            }
            listener = InstallStatusListener { update ->
                val progress = update.progressInfo?.takeIf { it.totalBytesToDownload > 0 }
                onProgress(DOWNLOAD_LABEL, progress?.let { it.bytesDownloaded.toFloat() / it.totalBytesToDownload })
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finish(null)
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED ->
                        finish(IllegalStateException("couldn't download Google's subject model (error ${update.errorCode})"))
                }
            }
            val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
            installer.installModules(request)
                .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) finish(null) }
                .addOnFailureListener { finish(it) }
            continuation.invokeOnCancellation { installer.unregisterListener(listener) }
        }
    }

    private companion object {
        const val DOWNLOAD_LABEL = "Getting Google's subject model (once)…"
        const val PROCESS_TIMEOUT_MS = 30_000L
        const val MIN_COVERAGE = 0.002f
        const val MAX_COVERAGE = 0.995f
    }
}
