package com.ma7moud.reality3d.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.ai.AiState
import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.ai.withInsight
import com.ma7moud.reality3d.data.ImageLoader
import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.export.ModelTexture
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.MeshBuilder
import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.TextureBaker
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class Progress(val label: String, val fraction: Float?)

data class UiState(
    val photo: Bitmap? = null,
    val mesh: Mesh3D? = null,
    /** The photo prepared as the model's texture (padded background, mask in alpha). */
    val texture: Bitmap? = null,
    val settings: MeshSettings = MeshSettings(),
    val progress: Progress? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val depthModelReady: Boolean = false,
    val depthDownloadBytes: Long = 0,
    /** Which hardware runs the depth model and how fast, once measured. */
    val depthBackend: String? = null,
    val insight: ObjectInsight? = null,
    val analyzing: Boolean = false,
    val rebuilding: Boolean = false,
    val exporting: Boolean = false,
)

class Reality3DViewModel(application: Application) : AndroidViewModel(application) {

    private val services = (application as Reality3DApplication).services
    val aiState: StateFlow<AiState> = services.ai.state

    private val _state = MutableStateFlow(
        UiState(
            depthModelReady = services.depth.isModelReady,
            depthDownloadBytes = services.depth.downloadBytes,
            depthBackend = services.depth.backendSummary,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** What the current photo's model is built from; settings changes rebuild from it without the models. */
    private class Inputs(val photo: Bitmap, val depth: DepthMap, val mask: SubjectMask?, val texture: ModelTexture)

    private var inputs: Inputs? = null
    private var pipeline: Job? = null
    private var rebuild: Job? = null
    private var pendingSave: ExportFile? = null

    init {
        services.ai.refresh()
    }

    fun onResume() {
        services.ai.refresh()
        _state.update { it.copy(depthModelReady = services.depth.isModelReady) }
    }

    fun loadPhoto(uri: Uri) {
        pipeline?.cancel()
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            _state.update { it.copy(progress = Progress("Opening the photo…", null), message = null, isError = false) }
            try {
                val photo = withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
                setPhoto(photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open photo", e)
                fail("Couldn't open that photo (${e.readable()}).")
            }
        }
    }

    fun setPhoto(photo: Bitmap) {
        rebuild?.cancel()
        inputs = null
        _state.update {
            it.copy(photo = photo, mesh = null, texture = null, insight = null, progress = null, isError = false, message = null)
        }
    }

    fun generate() {
        val photo = _state.value.photo ?: return
        if (pipeline?.isActive == true) return
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            try {
                if (!services.depth.isModelReady) {
                    val label = "Downloading the depth model (${formatBytes(services.depth.downloadBytes)}, only once)…"
                    report(label, 0f)
                    services.depth.downloadModel { report(label, it) }
                    _state.update { it.copy(depthModelReady = true) }
                }
                report("Finding the subject…", null)
                val mask = try {
                    services.segmenter.segment(photo) { label, fraction -> report(label, fraction) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Subject segmentation failed", e)
                    null
                }
                report("Estimating depth…", null)
                val depth = services.depth.estimate(photo, mask)
                val source = Inputs(photo, depth, mask, withContext(Dispatchers.Default) { bakeTexture(photo, mask) })
                _state.update { it.copy(depthBackend = services.depth.backendSummary) }
                inputs = source
                report("Building the 3D model…", null)
                val settings = _state.value.settings
                val mesh = build(source, settings)
                _state.update {
                    it.copy(
                        mesh = mesh,
                        texture = source.texture.bitmap,
                        progress = null,
                        isError = false,
                        message = if (mesh.subjectIsolated) null else NO_SUBJECT_MESSAGE,
                    )
                }
                if (_state.value.settings != settings) scheduleRebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "3D generation failed", e)
                fail("3D generation failed: ${e.readable()}.")
            }
        }
    }

    fun updateSettings(change: (MeshSettings) -> MeshSettings) {
        val current = _state.value.settings
        val next = change(current)
        if (next == current) return
        _state.update { it.copy(settings = next) }
        // A running pipeline picks the new settings up when it finishes.
        if (pipeline?.isActive != true) scheduleRebuild()
    }

    private fun scheduleRebuild() {
        val source = inputs ?: return
        rebuild?.cancel()
        rebuild = viewModelScope.launch {
            delay(REBUILD_DELAY_MS)
            _state.update { it.copy(rebuilding = true) }
            try {
                val mesh = build(source, _state.value.settings)
                if (inputs === source) _state.update { it.copy(mesh = mesh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rebuild failed", e)
                fail("Couldn't rebuild the model: ${e.readable()}.")
            } finally {
                _state.update { it.copy(rebuilding = false) }
            }
        }
    }

    private suspend fun build(source: Inputs, settings: MeshSettings): Mesh3D = withContext(Dispatchers.Default) {
        MeshBuilder.build(source.depth, source.mask, source.photo.width, source.photo.height, settings)
    }

    fun analyze() {
        val photo = _state.value.photo ?: return
        if (_state.value.analyzing) return
        _state.update { it.copy(analyzing = true) }
        viewModelScope.launch {
            try {
                val insight = services.ai.describe(photo)
                if (_state.value.photo !== photo) return@launch
                _state.update { it.copy(insight = insight, message = null, isError = false) }
                updateSettings { it.withInsight(insight) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano analysis failed", e)
                fail("Gemini Nano couldn't read the photo: ${e.readable()}.")
            } finally {
                _state.update { it.copy(analyzing = false) }
            }
        }
    }

    fun downloadAi() = services.ai.download()

    fun retryAi() = services.ai.refresh()

    /** Encodes the current model; null when there is none. */
    suspend fun export(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val snapshot = _state.value
        val mesh = snapshot.mesh ?: return null
        val texture = inputs?.texture ?: return null
        _state.update { it.copy(exporting = true) }
        return try {
            withContext(Dispatchers.Default) { Exporter.encode(format, mesh, texture, baseName(snapshot.insight), budget = budget) }
        } finally {
            _state.update { it.copy(exporting = false) }
        }
    }

    /**
     * The photo with its background padded by the subject's colours and the mask in alpha. The bitmap
     * keeps alpha unpremultiplied, as OpenGL and glTF expect.
     */
    private fun bakeTexture(photo: Bitmap, mask: SubjectMask?): ModelTexture {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val baked = TextureBaker.bake(pixels, photo.width, photo.height, mask)
        val bitmap = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        bitmap.isPremultiplied = false
        bitmap.setPixels(baked, 0, photo.width, 0, 0, photo.width, photo.height)
        return ModelTexture(bitmap, TextureBaker.subjectRegion(mask))
    }

    /** Keeps an encoded file while the system "save as" screen is open. */
    fun holdForSaving(file: ExportFile) {
        pendingSave = file
    }

    fun savePending(uri: Uri?) {
        val file = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                        ?: throw IOException("no output stream")
                }
                _state.update { it.copy(message = "Saved ${file.fileName}.", isError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Save failed", e)
                fail("Couldn't save the file: ${e.readable()}.")
            }
        }
    }

    fun showMessage(message: String, isError: Boolean) {
        _state.update { it.copy(message = message, isError = isError) }
    }

    private fun report(label: String, fraction: Float?) {
        _state.update { it.copy(progress = Progress(label, fraction), message = null, isError = false) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(progress = null, message = message, isError = true) }
    }

    private fun baseName(insight: ObjectInsight?): String {
        val fromName = insight?.name?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.take(32)
        if (!fromName.isNullOrEmpty()) return "${fromName}_3d"
        return "reality3d_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    }

    private fun Exception.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val TAG = "Reality3D"
        private const val REBUILD_DELAY_MS = 120L
        const val NO_SUBJECT_MESSAGE =
            "Couldn't separate the subject from the background, so the whole photo was used. A plain background helps."

        fun formatBytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
    }
}
