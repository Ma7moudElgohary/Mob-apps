package com.ma7moud.reality3d.segmentation

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.ma7moud.reality3d.util.awaitResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separates the photographed subject from its background. */
interface SubjectSegmenterEngine {
    /** The subject's confidence mask, or null when nothing could be told apart from the background. */
    suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): SubjectMask?
}

/** ML Kit subject segmentation. Its model comes from Google Play services and is fetched on first use. */
class MlKitSubjectMasker(private val context: Context) : SubjectSegmenterEngine {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }
    private val installer by lazy { ModuleInstall.getClient(context) }

    override suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): SubjectMask? {
        ensureModel(onProgress)
        onProgress("Separating the subject from the background…", null)
        val result = segmenter.process(InputImage.fromBitmap(photo, 0)).awaitResult()
        val buffer = result.foregroundConfidenceMask ?: return null
        buffer.rewind()
        val count = photo.width * photo.height
        if (buffer.remaining() < count) return null
        val values = FloatArray(count)
        buffer.get(values)
        return SubjectMask(photo.width, photo.height, values).takeIf { it.coverage in MIN_COVERAGE..MAX_COVERAGE }
    }

    private suspend fun ensureModel(onProgress: (String, Float?) -> Unit) {
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
        const val MIN_COVERAGE = 0.002f
        const val MAX_COVERAGE = 0.995f
    }
}
