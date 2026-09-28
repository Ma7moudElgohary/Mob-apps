package com.ma7moud.reality3d.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
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
import com.ma7moud.reality3d.segmentation.MaskEdit
import com.ma7moud.reality3d.segmentation.Segmentation
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
    /** The objects found in the photo, once it has been looked at. */
    val subjects: SubjectsView? = null,
    /** The model's real size per unit, once set from a measured length; null until then. */
    val metersPerUnit: Float? = null,
)

/** The objects found in the photo and which of them make the model. */
data class SubjectsView(
    /** Separate objects found; 0 when nothing stood out from the background. */
    val count: Int,
    /** The chosen objects; empty means all of them. */
    val selection: Set<Int>,
    /** Drawn over the photo: dims what is left out and tints the objects not chosen. */
    val overlay: Bitmap,
    /** The outline was changed by hand. */
    val edited: Boolean,
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

    /** The photo's objects: what the segmenter found, the chosen ones and any outline painted by hand. */
    private class PhotoSubjects(
        val photo: Bitmap,
        val segmentation: Segmentation?,
        val editWidth: Int,
        val editHeight: Int,
        /** The photo's brightness at editing resolution, for snapping outlines to its edges. */
        val guide: FloatArray,
    ) {
        var selection: Set<Int> = emptySet()

        /** Strokes and settings from the editor; its base is set from [selection] whenever it is used. */
        var edit: MaskEdit? = null

        val count: Int get() = segmentation?.subjects?.size ?: 0
    }

    private val _editor = MutableStateFlow<MaskEditorSession?>(null)

    /** The open mask editor, if any. */
    val editor: StateFlow<MaskEditorSession?> = _editor.asStateFlow()

    private var subjects: PhotoSubjects? = null
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
            val photo = try {
                withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open photo", e)
                fail("Couldn't open that photo (${e.readable()}).")
                return@launch
            }
            showPhoto(photo)
            findSubjects(photo)
        }
    }

    fun setPhoto(photo: Bitmap) {
        pipeline?.cancel()
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            showPhoto(photo)
            findSubjects(photo)
        }
    }

    private fun showPhoto(photo: Bitmap) {
        inputs = null
        subjects = null
        closeEditor()
        _state.update {
            it.copy(
                photo = photo, mesh = null, texture = null, insight = null, subjects = null, metersPerUnit = null,
                progress = null, isError = false, message = null,
            )
        }
    }

    /**
     * Looks for the objects in the photo, so they can be picked before the model is made. Returns null if
     * the segmenter failed; [generate] tries again.
     */
    private suspend fun findSubjects(photo: Bitmap): PhotoSubjects? {
        report("Finding the objects…", null)
        val segmentation = try {
            services.segmenter.segment(photo) { label, fraction -> report(label, fraction) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Subject segmentation failed", e)
            _state.update { it.copy(progress = null) }
            return null
        }
        val found = withContext(Dispatchers.Default) { examine(photo, segmentation) }
        subjects = found
        publishSubjects(found)
        _state.update { it.copy(progress = null) }
        return found
    }

    private fun examine(photo: Bitmap, segmentation: Segmentation?): PhotoSubjects {
        val (w, h) = MaskEdit.sizeFor(photo.width, photo.height)
        val small = if (w == photo.width && h == photo.height) photo else photo.scale(w, h)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        return PhotoSubjects(photo, segmentation, w, h, MaskEdit.brightness(pixels))
    }

    /** The mask the model is made from: the chosen objects, with any outline painted by hand. */
    private suspend fun currentMask(found: PhotoSubjects): SubjectMask? {
        val selection = found.selection
        val edit = found.edit?.copy()
        return withContext(Dispatchers.Default) {
            val chosen = found.segmentation?.maskFor(selection)
            if (edit == null) return@withContext chosen
            edit.setBase(chosen)
            edit.toSubjectMask().takeIf { it.coverage > 0f }
        }
    }

    /** Shows the objects over the photo. */
    private suspend fun publishSubjects(found: PhotoSubjects) {
        val selection = found.selection
        val mask = currentMask(found)
        val overlay = withContext(Dispatchers.Default) {
            val pixels = MaskOverlay.render(mask, found.segmentation, selection, found.editWidth, found.editHeight)
            MaskOverlay.toBitmap(pixels, found.editWidth, found.editHeight)
        }
        if (subjects !== found) return
        _state.update { it.copy(subjects = SubjectsView(found.count, selection, overlay, found.edit != null)) }
    }

    fun generate() {
        val photo = _state.value.photo ?: return
        if (pipeline?.isActive == true) return
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            try {
                ensureDepthModel()
                val found = subjects ?: findSubjects(photo) ?: withContext(Dispatchers.Default) { examine(photo, null) }.also {
                    subjects = it
                    publishSubjects(it)
                }
                makeModel(photo, found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "3D generation failed", e)
                fail("3D generation failed: ${e.readable()}.")
            }
        }
    }

    private suspend fun ensureDepthModel() {
        if (services.depth.isModelReady) return
        val label = "Downloading the depth model (${formatBytes(services.depth.downloadBytes)}, only once)…"
        report(label, 0f)
        services.depth.downloadModel { report(label, it) }
        _state.update { it.copy(depthModelReady = true) }
    }

    private suspend fun makeModel(photo: Bitmap, found: PhotoSubjects) {
        val mask = currentMask(found)
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
    }

    /**
     * Chooses the object at normalised photo point ([u], [v]): the first tap models it alone, later taps
     * add or remove objects. Choosing all of them is the same as choosing none.
     */
    fun tapSubject(u: Float, v: Float) {
        val found = subjects ?: return
        if (found.count < 2 || pipeline?.isActive == true) return
        val index = found.segmentation?.subjectAt(u, v) ?: return
        val current = found.selection
        var next = when {
            current.isEmpty() -> setOf(index)
            index in current -> current - index
            else -> current + index
        }
        if (next.size == found.count) next = emptySet()
        found.selection = next
        subjectsChanged(found)
    }

    fun useAllSubjects() {
        val found = subjects ?: return
        if (found.selection.isEmpty() || pipeline?.isActive == true) return
        found.selection = emptySet()
        subjectsChanged(found)
    }

    /** The mask changed: redraw it over the photo, and remake the model if there is one. */
    private fun subjectsChanged(found: PhotoSubjects) {
        val photo = found.photo
        val remake = _state.value.mesh != null
        pipeline?.cancel()
        rebuild?.cancel()
        // A different outline changes what one model unit is.
        _state.update { it.copy(metersPerUnit = null) }
        pipeline = viewModelScope.launch {
            try {
                publishSubjects(found)
                if (remake) {
                    ensureDepthModel()
                    makeModel(photo, found)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remaking the model failed", e)
                fail("Couldn't remake the model: ${e.readable()}.")
            }
        }
    }

    /** Opens the mask editor on the current outline. */
    fun openEditor() {
        val photo = _state.value.photo ?: return
        if (_editor.value != null || pipeline?.isActive == true) return
        pipeline = viewModelScope.launch {
            val found = subjects ?: withContext(Dispatchers.Default) { examine(photo, null) }.also { subjects = it }
            val selection = found.selection
            val saved = found.edit
            val edit = withContext(Dispatchers.Default) {
                (saved?.copy() ?: MaskEdit(found.editWidth, found.editHeight, found.guide)).apply {
                    setBase(found.segmentation?.maskFor(selection))
                }
            }
            if (subjects === found) _editor.value = MaskEditorSession(photo, edit, viewModelScope)
        }
    }

    /** Closes the editor, keeping the new outline when [apply] is true. */
    fun finishEditing(apply: Boolean) {
        val session = _editor.value ?: return
        closeEditor()
        val found = subjects ?: return
        if (!apply || !session.changed) return
        val edit = session.edit
        found.edit = edit.takeIf { it.hasStrokes || it.refineEdges || it.feather > 0 }
        subjectsChanged(found)
    }

    private fun closeEditor() {
        _editor.value?.close()
        _editor.value = null
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

    /** The real size of one unit of [mesh]: set from a measurement, or assumed from the default size. */
    fun metersPerUnit(mesh: Mesh3D): Float =
        _state.value.metersPerUnit ?: (Exporter.DEFAULT_LONGEST_SIDE_METERS / mesh.longestSide.coerceAtLeast(1e-6f))

    /** Sets the model's scale: a length measured on it ([modelUnits]) is really [meters] long. */
    fun setRealLength(modelUnits: Float, meters: Float) {
        if (modelUnits <= 0f || meters <= 0f) return
        _state.update { it.copy(metersPerUnit = meters / modelUnits, message = "Real size set. Exports use it too.", isError = false) }
    }

    /** Encodes the current model; null when there is none. */
    suspend fun export(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val snapshot = _state.value
        val mesh = snapshot.mesh ?: return null
        val texture = inputs?.texture ?: return null
        val longestSide = metersPerUnit(mesh) * mesh.longestSide
        _state.update { it.copy(exporting = true) }
        return try {
            withContext(Dispatchers.Default) {
                Exporter.encode(format, mesh, texture, baseName(snapshot.insight), budget = budget, longestSideMeters = longestSide)
            }
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
        val bitmap = createBitmap(photo.width, photo.height)
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
