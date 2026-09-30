package com.ma7moud.reality3d.ui

import android.app.Application
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.ma7moud.reality3d.data.GalleryExport
import com.ma7moud.reality3d.data.ImageLoader
import com.ma7moud.reality3d.data.LastPhoto
import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.diagnostics.CrashReport
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.export.ModelTexture
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.mesh.GlbReader
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.MeshBuilder
import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.TextureBaker
import com.ma7moud.reality3d.project.ProjectDraft
import com.ma7moud.reality3d.project.ProjectKind
import com.ma7moud.reality3d.quality.PhotoQuality
import com.ma7moud.reality3d.quality.QualityReport
import com.ma7moud.reality3d.remote.BuildMode
import com.ma7moud.reality3d.remote.BuildQuality
import com.ma7moud.reality3d.remote.PhotoBuildOptions
import com.ma7moud.reality3d.remote.PhotoModelBuilder
import com.ma7moud.reality3d.remote.PhotoZip
import com.ma7moud.reality3d.remote.RemoteException
import com.ma7moud.reality3d.remote.quality
import com.ma7moud.reality3d.remote.RemoteSettings
import com.ma7moud.reality3d.remote.RemoteSettingsStore
import com.ma7moud.reality3d.remote.ServerAddress
import com.ma7moud.reality3d.remote.ServerInfo
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
import java.io.File
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
    /** How well the photo will work, once its objects are known. */
    val photoQuality: QualityReport? = null,
    val saving: Boolean = false,
    /** The model last saved to My models, to show it is saved. */
    val savedMesh: Mesh3D? = null,
    val remote: RemoteUi = RemoteUi(),
    /** Why the app closed unexpectedly last time, until the user closes the note. */
    val crash: CrashReport? = null,
    /** Features turned off because they crashed the app, or are known to crash on this phone model. */
    val turnedOff: List<Fallback> = emptyList(),
    /** Whether the Safe mode row can turn anything back on. */
    val canTurnBackOn: Boolean = false,
    /** ML Kit can't tell objects apart on this phone: offer Segment Anything (its download size in bytes). */
    val segmentAnythingOffer: Long? = null,
    /** 3D from many photos on the user's computer, and models opened from files. */
    val photoBuild: PhotoBuildUi = PhotoBuildUi(),
)

/** Many photos picked from the gallery, on their way to the computer's photo builder, and what came of it. */
data class PhotoBuildUi(
    val picked: List<Uri> = emptyList(),
    val options: PhotoBuildOptions = PhotoBuildOptions(),
    val progress: Progress? = null,
    val status: String? = null,
    val statusIsError: Boolean = false,
    /** The model that was made or opened, saved to My models. */
    val savedId: String? = null,
    val savedName: String? = null,
)

/** A full 3D model made by an image-to-3D AI on the user's computer. */
class AiModel(val mesh: Mesh3D, val texture: Bitmap?, val engine: String)

/** The connection to the user's Reality3D server and what it made. */
data class RemoteUi(
    val settings: RemoteSettings = RemoteSettings(),
    val server: ServerInfo? = null,
    val checking: Boolean = false,
    val status: String? = null,
    val statusIsError: Boolean = false,
    val progress: Progress? = null,
    val model: AiModel? = null,
    val saving: Boolean = false,
    /** The AI model last saved to My models. */
    val saved: AiModel? = null,
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
    /** A tap on something not yet found looks for an object there. */
    val canPick: Boolean = false,
)

class Reality3DViewModel(application: Application) : AndroidViewModel(application) {

    private val services = (application as Reality3DApplication).services
    private val diagnostics = (application as Reality3DApplication).diagnostics
    val aiState: StateFlow<AiState> = services.ai.state

    private val _state = MutableStateFlow(
        UiState(
            depthModelReady = services.depth.isModelReady,
            depthDownloadBytes = services.depth.downloadBytes,
            depthBackend = services.depth.backendSummary,
            crash = diagnostics.report,
            turnedOff = diagnostics.turnedOff,
            canTurnBackOn = diagnostics.canTurnBackOn,
            segmentAnythingOffer = segmentAnythingOffer(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** What the current photo's model is built from; settings changes rebuild from it without the models. */
    private class Inputs(val photo: Bitmap, val depth: DepthMap, val mask: SubjectMask?, val texture: ModelTexture)

    /** The photo's objects: what the segmenter found, the chosen ones and any outline painted by hand. */
    private class PhotoSubjects(
        val photo: Bitmap,
        var segmentation: Segmentation?,
        val editWidth: Int,
        val editHeight: Int,
        /** The photo's brightness at editing resolution, for snapping outlines to its edges. */
        val guide: FloatArray,
        /** The photo at the size photo quality is measured at. */
        val analysis: IntArray,
        val analysisWidth: Int,
        val analysisHeight: Int,
    ) {
        var selection: Set<Int> = segmentation?.suggested.orEmpty()

        /** Strokes and settings from the editor; its base is set from [selection] whenever it is used. */
        var edit: MaskEdit? = null

        val count: Int get() = segmentation?.subjects?.size ?: 0
    }

    private val _editor = MutableStateFlow<MaskEditorSession?>(null)

    /** The open mask editor, if any. */
    val editor: StateFlow<MaskEditorSession?> = _editor.asStateFlow()

    private var subjects: PhotoSubjects? = null

    /** The mask last shown, for rating the photo again. */
    private var lastMask: SubjectMask? = null
    private var inputs: Inputs? = null
    private val remoteSettings = RemoteSettingsStore(application)
    private val lastPhoto = LastPhoto(File(application.filesDir, "last_photo"))
    private var remoteJob: Job? = null
    private val photoBuilder = PhotoModelBuilder(services.remote)
    private var photoJob: Job? = null
    private var pipeline: Job? = null
    private var rebuild: Job? = null
    private var pendingSave: ExportFile? = null

    init {
        services.ai.refresh()
        val settings = remoteSettings.load()
        _state.update { it.copy(remote = it.remote.copy(settings = settings)) }
        if (settings.url.isNotBlank()) checkServer()
        restoreLastPhoto()
    }

    /**
     * After the app closed unexpectedly, brings back the photo it was working on, so the user doesn't have to find
     * it again. Nothing is analysed until they ask: the photo may be what brought the app down. After a normal
     * exit the kept photo is dropped.
     */
    private fun restoreLastPhoto() {
        if (diagnostics.report == null) {
            viewModelScope.launch(Dispatchers.IO) { lastPhoto.clear() }
            return
        }
        pipeline = viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { lastPhoto.load() } ?: return@launch
            if (_state.value.photo != null) return@launch
            showPhoto(photo)
            _state.update { it.copy(message = "Your last photo is back. Tap Make 3D model, or Edit outline to cut the object out yourself.", isError = false) }
        }
    }

    /** Keeps [photo] on disk from the moment it opens, before anything that could crash the app runs on it. */
    private suspend fun rememberPhoto(photo: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                lastPhoto.save(photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep the photo", e)
            }
        }
    }

    /** Also saves a photo taken with the app's camera in the phone's Gallery, where photos are looked for. */
    fun keepInGallery(uri: Uri) {
        if (!GalleryExport.available) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { GalleryExport.save(getApplication(), uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save the photo to the Gallery", e)
            }
        }
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
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory to open the photo", e)
                fail(OUT_OF_MEMORY_MESSAGE)
                return@launch
            }
            showPhoto(photo)
            rememberPhoto(photo)
            findSubjects(photo)
        }
    }

    fun setPhoto(photo: Bitmap) {
        pipeline?.cancel()
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            showPhoto(photo)
            rememberPhoto(photo)
            findSubjects(photo)
        }
    }

    private fun showPhoto(photo: Bitmap) {
        inputs = null
        subjects = null
        lastMask = null
        remoteJob?.cancel()
        _state.update { it.copy(remote = it.remote.copy(model = null, progress = null, status = null, saved = null)) }
        closeEditor()
        _state.update {
            it.copy(
                photo = photo, mesh = null, texture = null, insight = null, subjects = null, metersPerUnit = null,
                photoQuality = null, savedMesh = null, progress = null, isError = false, message = null,
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
            _state.update { it.copy(progress = null, message = "Couldn't cut the object out (${e.readable()}). Tap it, or use Edit outline.", isError = true) }
            return null
        } catch (e: LinkageError) {
            // Google Play services' ML Kit didn't match the app; carry on without separating the object.
            Log.w(TAG, "Subject segmentation couldn't start", e)
            _state.update { it.copy(progress = null, message = "Couldn't cut the object out (${e.javaClass.simpleName}). Use Edit outline.", isError = true) }
            return null
        }
        // Showing the objects is a help, not a must: if it fails, the photo stays and the model can still be made.
        return try {
            val found = withContext(Dispatchers.Default) { examine(photo, segmentation) }
            subjects = found
            publishSubjects(found)
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the photo's objects", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to show the photo's objects", e)
            null
        } finally {
            _state.update { it.copy(progress = null) }
        }
    }

    private fun examine(photo: Bitmap, segmentation: Segmentation?): PhotoSubjects {
        val (w, h) = MaskEdit.sizeFor(photo.width, photo.height)
        val pixels = pixelsAt(photo, w, h)
        val (aw, ah) = MaskEdit.sizeFor(photo.width, photo.height, longest = PhotoQuality.ANALYSIS_SIZE)
        return PhotoSubjects(photo, segmentation, w, h, MaskEdit.brightness(pixels), pixelsAt(photo, aw, ah), aw, ah)
    }

    private fun pixelsAt(photo: Bitmap, width: Int, height: Int): IntArray {
        val small = if (width == photo.width && height == photo.height) photo else photo.scale(width, height)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== photo) small.recycle()
        return pixels
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
        val insight = _state.value.insight
        val (overlay, quality) = withContext(Dispatchers.Default) {
            val pixels = MaskOverlay.render(mask, found.segmentation, selection, found.editWidth, found.editHeight)
            MaskOverlay.toBitmap(pixels, found.editWidth, found.editHeight) to
                PhotoQuality.assess(found.analysis, found.analysisWidth, found.analysisHeight, mask, insight)
        }
        lastMask = mask
        if (subjects !== found) return
        val view = SubjectsView(found.count, selection, overlay, found.edit != null, canPick = services.segmenter.canPick)
        _state.update { it.copy(subjects = view, photoQuality = quality) }
    }

    /** Rates the photo again, for example once Gemini Nano has said the object is shiny. */
    private fun rateAgain() {
        val found = subjects ?: return
        val mask = lastMask
        val insight = _state.value.insight
        viewModelScope.launch {
            val quality = withContext(Dispatchers.Default) {
                PhotoQuality.assess(found.analysis, found.analysisWidth, found.analysisHeight, mask, insight)
            }
            if (subjects === found) _state.update { it.copy(photoQuality = quality) }
        }
    }

    /** Saves the model, its texture and photo to My models. */
    fun saveProject() {
        val snapshot = _state.value
        val mesh = snapshot.mesh ?: return
        val source = inputs ?: return
        if (snapshot.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    services.projects.save(
                        ProjectDraft(
                            name = snapshot.insight?.name ?: ("Photo model " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())),
                            kind = ProjectKind.PHOTO,
                            mesh = mesh,
                            thumbnail = thumbnailOf(source.photo, source.texture.region),
                            metersPerUnit = metersPerUnit(mesh),
                            sizeKnown = snapshot.metersPerUnit != null,
                            quality = snapshot.photoQuality,
                            texture = source.texture.bitmap,
                            textureRegion = source.texture.region,
                            photo = source.photo,
                        ),
                    )
                }
                _state.update { it.copy(savedMesh = mesh, message = "Saved to My models as “${info.name}”.", isError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the project failed", e)
                fail("Couldn't save the model: ${e.readable()}.")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** The part of the photo the model uses, at most [THUMBNAIL] pixels across. */
    private fun thumbnailOf(photo: Bitmap, region: FloatArray): Bitmap {
        val left = (region[0] * photo.width).toInt().coerceIn(0, photo.width - 1)
        val top = (region[1] * photo.height).toInt().coerceIn(0, photo.height - 1)
        val width = ((region[2] - region[0]) * photo.width).toInt().coerceIn(1, photo.width - left)
        val height = ((region[3] - region[1]) * photo.height).toInt().coerceIn(1, photo.height - top)
        val scale = minOf(1f, THUMBNAIL.toFloat() / maxOf(width, height))
        val crop = Bitmap.createBitmap(photo, left, top, width, height)
        return if (scale >= 1f) crop else crop.scale((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
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
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory for the model", e)
                fail(OUT_OF_MEMORY_MESSAGE)
            } catch (e: LinkageError) {
                Log.w(TAG, "The depth model couldn't start", e)
                fail("The depth model couldn't start on this phone (${e.javaClass.simpleName}).")
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
        if (pipeline?.isActive == true) return
        val index = found.segmentation?.subjectAt(u, v)
        if (index == null) {
            if (services.segmenter.canPick) pickObject(found, u, v)
            return
        }
        if (found.count < 2) return
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
        pipeline?.cancel()
        rebuild?.cancel()
        pipeline = viewModelScope.launch { showChangedSubjects(found) }
    }

    private suspend fun showChangedSubjects(found: PhotoSubjects) {
        // A different outline changes what one model unit is.
        _state.update { it.copy(metersPerUnit = null) }
        try {
            publishSubjects(found)
            if (_state.value.mesh != null) {
                ensureDepthModel()
                makeModel(found.photo, found)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remaking the model failed", e)
            fail("Couldn't remake the model: ${e.readable()}.")
        }
    }

    /** Looks for an object where the user tapped and adds it to the chosen ones. */
    private fun pickObject(found: PhotoSubjects, u: Float, v: Float) {
        rebuild?.cancel()
        pipeline = viewModelScope.launch {
            report("Looking at what you tapped…", null)
            val subject = try {
                services.segmenter.objectAt(found.photo, u, v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't find the tapped object", e)
                null
            }
            if (subjects !== found) return@launch
            if (subject == null) {
                _state.update { it.copy(progress = null, message = "Nothing to cut out there. Tap on the object itself.", isError = false) }
                return@launch
            }
            found.segmentation = (found.segmentation ?: Segmentation(found.photo.width, found.photo.height, null, emptyList())) + subject
            // Everything is chosen when nothing is picked; otherwise the new object joins the picked ones.
            if (found.selection.isNotEmpty()) found.selection = found.selection + (found.count - 1)
            _state.update { it.copy(progress = null) }
            showChangedSubjects(found)
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
                rateAgain()
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

    private fun segmentAnythingOffer(): Long? {
        val model = services.segmentAnything
        val wanted = diagnostics.isOff(Fallback.ONE_OBJECT) && !diagnostics.isOff(Fallback.NO_SAM)
        return if (wanted && !model.isReady) model.downloadBytes else null
    }

    /** Fetches Segment Anything, then looks at the current photo with it. */
    fun getSegmentAnything() {
        if (pipeline?.isActive == true) return
        val model = services.segmentAnything
        pipeline = viewModelScope.launch {
            val label = "Downloading Segment Anything (${formatBytes(model.downloadBytes)}, only once)…"
            try {
                report(label, 0f)
                model.download { report(label, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Segment Anything download failed", e)
                fail("Couldn't download Segment Anything: ${e.readable()}.")
                return@launch
            }
            _state.update { it.copy(segmentAnythingOffer = segmentAnythingOffer(), progress = null) }
            val photo = _state.value.photo ?: return@launch
            subjects = null
            findSubjects(photo)
        }
    }

    fun retryAi() = services.ai.refresh()

    /** The real size of one unit of [mesh]: set from a measurement, or assumed from the default size. */
    fun metersPerUnit(mesh: Mesh3D): Float =
        _state.value.metersPerUnit ?: (Exporter.DEFAULT_LONGEST_SIDE_METERS / mesh.longestSide.coerceAtLeast(1e-6f))

    /** Sets the model's scale: a length measured on it ([modelUnits]) is really [meters] long. */
    fun setRealLength(modelUnits: Float, meters: Float) {
        if (modelUnits <= 0f || meters <= 0f) return
        _state.update { it.copy(metersPerUnit = meters / modelUnits, message = "Real size set. Exports use it too.", isError = false) }
    }

    private fun updateRemote(change: (RemoteUi) -> RemoteUi) = _state.update { it.copy(remote = change(it.remote)) }

    /** Keeps the server's address and access code, then connects to it. */
    fun saveRemoteSettings(url: String, token: String) {
        val address = try {
            ServerAddress.normalize(url)
        } catch (e: RemoteException) {
            updateRemote { it.copy(status = e.message, statusIsError = true) }
            return
        }
        val settings = _state.value.remote.settings.copy(url = address, token = token.trim())
        remoteSettings.save(settings)
        updateRemote { it.copy(settings = settings, server = null) }
        checkServer()
    }

    /** Asks the server what it can do; picks the first AI engine it has when the chosen one isn't there. */
    fun checkServer() {
        val settings = _state.value.remote.settings
        if (settings.url.isBlank() || _state.value.remote.checking) return
        updateRemote { it.copy(checking = true, status = null, statusIsError = false) }
        viewModelScope.launch {
            try {
                val info = services.remote.health(settings)
                val available = info.engines.filter { it.available && it.kind == "image" }
                val engine = settings.engine.takeIf { id -> available.any { it.id == id } }
                    ?: available.firstOrNull { it.id != PREVIEW_ENGINE }?.id
                    ?: available.firstOrNull()?.id
                    ?: settings.engine
                val updated = settings.copy(engine = engine)
                remoteSettings.save(updated)
                updateRemote { it.copy(settings = updated, server = info, checking = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't reach the Reality3D server", e)
                updateRemote { it.copy(server = null, checking = false, status = e.readable(), statusIsError = true) }
            }
        }
    }

    private fun updatePhotoBuild(change: (PhotoBuildUi) -> PhotoBuildUi) = _state.update { it.copy(photoBuild = change(it.photoBuild)) }

    /** The photos chosen in the gallery for the computer's photo builder. */
    fun pickPhotos(uris: List<Uri>) {
        if (photoJob?.isActive == true) return
        updatePhotoBuild { it.copy(picked = uris.take(PhotoZip.MAX_PHOTOS), savedId = null, savedName = null, status = null, statusIsError = false) }
    }

    fun clearPickedPhotos() {
        if (photoJob?.isActive == true) return
        updatePhotoBuild { it.copy(picked = emptyList(), status = null, statusIsError = false) }
    }

    fun setBuildQuality(quality: BuildQuality) = updatePhotoBuild { it.copy(options = it.options.copy(quality = quality)) }

    fun setBuildMode(mode: BuildMode) = updatePhotoBuild { it.copy(options = it.options.copy(mode = mode)) }

    /** Zips the chosen photos, has the computer build a model from them, and saves it to My models. */
    fun buildFromPhotos() {
        val picked = _state.value.photoBuild.picked
        if (picked.size < MIN_BUILD_PHOTOS || photoJob?.isActive == true) return
        val settings = _state.value.remote.settings
        if (settings.url.isBlank()) {
            updatePhotoBuild { it.copy(status = "Connect to your computer first.", statusIsError = true) }
            return
        }
        val options = _state.value.photoBuild.options
        photoJob = viewModelScope.launch {
            val zip = File(getApplication<Application>().cacheDir, "picked_photos.zip")
            updatePhotoBuild { it.copy(progress = Progress("Getting the photos ready…", null), status = null, statusIsError = false, savedId = null, savedName = null) }
            try {
                val engine = photoBuilder.checkServer(settings)
                val zipped = withContext(Dispatchers.IO) {
                    PhotoZip.fromUris(getApplication(), picked, zip) { done, total ->
                        updatePhotoBuild { it.copy(progress = Progress("Getting the photos ready ($done of $total)…", done.toFloat() / total)) }
                    }
                }
                if (zipped.photos < MIN_BUILD_PHOTOS) {
                    throw RemoteException("Only ${zipped.photos} of the ${picked.size} photos could be read. Pick them again, from the gallery.")
                }
                val model = photoBuilder.build(settings.copy(engine = engine.id), zipped.file, options) { fraction, label ->
                    updatePhotoBuild { it.copy(progress = Progress(label ?: it.progress?.label ?: "Building the model", fraction)) }
                }
                val info = withContext(Dispatchers.IO) {
                    val thumbnail = PhotoZip.thumbnail(getApplication<Application>().contentResolver, picked) ?: createBitmap(8, 8)
                    services.projects.save(
                        ProjectDraft(
                            name = "Photo model " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date()),
                            kind = ProjectKind.PHOTOGRAMMETRY,
                            mesh = model.mesh,
                            thumbnail = thumbnail,
                            metersPerUnit = model.metersPerUnit,
                            sizeKnown = model.sizeKnown,
                            quality = model.quality(zipped.photos),
                            texture = model.texture,
                            textureRegion = if (model.texture != null) floatArrayOf(0f, 0f, 1f, 1f) else null,
                        ),
                    )
                }
                updatePhotoBuild { it.copy(progress = null, savedId = info.id, savedName = info.name, picked = emptyList()) }
            } catch (e: CancellationException) {
                updatePhotoBuild { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Building from photos failed", e)
                updatePhotoBuild { it.copy(progress = null, status = e.readable(), statusIsError = true) }
            } finally {
                zip.delete()
            }
        }
    }

    fun cancelPhotoBuild() {
        photoJob?.cancel()
        updatePhotoBuild { it.copy(progress = null) }
    }

    /** Opens a .glb another app made (KIRI Engine, Scaniverse, Polycam, RealityScan...) and saves it to My models. */
    fun importModel(uri: Uri) {
        if (photoJob?.isActive == true) return
        photoJob = viewModelScope.launch {
            updatePhotoBuild { it.copy(progress = Progress("Opening the file…", null), status = null, statusIsError = false, savedId = null, savedName = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val (bytes, name) = withContext(Dispatchers.IO) {
                    val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val data = resolver.openInputStream(uri)?.use { stream ->
                        stream.readNBytes(MAX_IMPORT_BYTES + 1)
                    } ?: throw IOException("the file can't be opened")
                    if (data.size > MAX_IMPORT_BYTES) throw IOException("the file is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
                    data to (display ?: uri.lastPathSegment ?: "Model")
                }
                val model = withContext(Dispatchers.Default) { PhotoModelBuilder.open(bytes) }
                val info = withContext(Dispatchers.IO) {
                    val thumbnail = model.texture?.let { texture ->
                        val side = 320
                        val scale = side.toFloat() / maxOf(texture.width, texture.height)
                        if (scale < 1f) texture.scale((texture.width * scale).toInt().coerceAtLeast(1), (texture.height * scale).toInt().coerceAtLeast(1)) else texture
                    } ?: createBitmap(8, 8)
                    services.projects.save(
                        ProjectDraft(
                            name = name.substringBeforeLast('.').take(60).ifBlank { "Model" },
                            kind = ProjectKind.IMPORTED,
                            mesh = model.mesh,
                            thumbnail = thumbnail,
                            metersPerUnit = model.metersPerUnit,
                            sizeKnown = model.sizeKnown,
                            texture = model.texture,
                            textureRegion = if (model.texture != null) floatArrayOf(0f, 0f, 1f, 1f) else null,
                        ),
                    )
                }
                updatePhotoBuild { it.copy(progress = null, savedId = info.id, savedName = info.name) }
            } catch (e: CancellationException) {
                updatePhotoBuild { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the model failed", e)
                updatePhotoBuild { it.copy(progress = null, status = "Couldn't open that file: ${e.readable()}", statusIsError = true) }
            }
        }
    }

    fun chooseEngine(id: String) {
        val settings = _state.value.remote.settings.copy(engine = id)
        remoteSettings.save(settings)
        updateRemote { it.copy(settings = settings) }
    }

    /** Sends the cut-out to the server's AI and brings back a full 3D model, back included. */
    fun makeFullModel() {
        val photo = _state.value.photo ?: return
        val settings = _state.value.remote.settings
        if (remoteJob?.isActive == true) return
        remoteJob = viewModelScope.launch {
            updateRemote { it.copy(progress = Progress("Preparing the cut-out…", null), status = null, statusIsError = false) }
            try {
                val mask = subjects?.let { currentMask(it) }
                val png = withContext(Dispatchers.Default) { cutoutPng(photo, mask) }
                val bytes = services.remote.generate(settings, png) { fraction, message ->
                    updateRemote { it.copy(progress = Progress(message ?: "The AI is working…", fraction)) }
                }
                val engine = _state.value.remote.server?.engines?.firstOrNull { it.id == settings.engine }?.name ?: settings.engine
                val model = withContext(Dispatchers.Default) {
                    val read = GlbReader.read(bytes)
                    AiModel(read.mesh, read.texture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }, engine)
                }
                updateRemote { it.copy(model = model, progress = null) }
            } catch (e: CancellationException) {
                updateRemote { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The full 3D model failed", e)
                updateRemote { it.copy(progress = null, status = "Couldn't make the full 3D model: ${e.readable()}", statusIsError = true) }
            }
        }
    }

    fun cancelFullModel() {
        remoteJob?.cancel()
        updateRemote { it.copy(progress = null) }
    }

    /**
     * The object cut out along the mask (transparent background) with a little margin, at most
     * [CUTOUT_SIZE] pixels across, as image-to-3D AIs expect.
     */
    private fun cutoutPng(photo: Bitmap, mask: SubjectMask?): ByteArray {
        val region = TextureBaker.subjectRegion(mask, margin = 0.06f)
        val left = (region[0] * photo.width).toInt().coerceIn(0, photo.width - 1)
        val top = (region[1] * photo.height).toInt().coerceIn(0, photo.height - 1)
        val width = ((region[2] - region[0]) * photo.width).toInt().coerceIn(1, photo.width - left)
        val height = ((region[3] - region[1]) * photo.height).toInt().coerceIn(1, photo.height - top)
        val scale = minOf(1f, CUTOUT_SIZE.toFloat() / maxOf(width, height))
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        val crop = Bitmap.createBitmap(photo, left, top, width, height)
        val sized = if (w == width && h == height) crop else crop.scale(w, h)
        val pixels = IntArray(w * h)
        sized.getPixels(pixels, 0, w, 0, 0, w, h)
        if (mask != null) {
            for (y in 0 until h) {
                val v = (top + (y + 0.5f) * height / h) / photo.height
                for (x in 0 until w) {
                    val u = (left + (x + 0.5f) * width / w) / photo.width
                    val alpha = (mask.sample(u, v).coerceIn(0f, 1f) * 255f).toInt()
                    pixels[y * w + x] = (alpha shl 24) or (pixels[y * w + x] and 0xFFFFFF)
                }
            }
        }
        val out = createBitmap(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return Exporter.png(out)
    }

    /** The AI model's real size per unit: as long as the photo model (set or assumed), which is the same object. */
    fun aiMetersPerUnit(mesh: Mesh3D): Float {
        val photoModel = _state.value.mesh
        val longest = if (photoModel != null) metersPerUnit(photoModel) * photoModel.longestSide else Exporter.DEFAULT_LONGEST_SIDE_METERS
        return longest / mesh.longestSide.coerceAtLeast(1e-6f)
    }

    suspend fun exportAi(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val model = _state.value.remote.model ?: return null
        val name = baseName(_state.value.insight) + "_ai"
        _state.update { it.copy(exporting = true) }
        return try {
            withContext(Dispatchers.Default) {
                Exporter.encode(
                    format, model.mesh, model.texture?.let { ModelTexture(it, floatArrayOf(0f, 0f, 1f, 1f)) }, name,
                    budget = budget, longestSideMeters = aiMetersPerUnit(model.mesh) * model.mesh.longestSide,
                )
            }
        } finally {
            _state.update { it.copy(exporting = false) }
        }
    }

    fun saveAiProject() {
        val model = _state.value.remote.model ?: return
        val photo = _state.value.photo ?: return
        if (_state.value.remote.saving) return
        updateRemote { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    services.projects.save(
                        ProjectDraft(
                            name = (_state.value.insight?.name ?: "Full 3D model") + " (AI)",
                            kind = ProjectKind.AI,
                            mesh = model.mesh,
                            thumbnail = thumbnailOf(photo, TextureBaker.subjectRegion(lastMask)),
                            metersPerUnit = aiMetersPerUnit(model.mesh),
                            sizeKnown = _state.value.metersPerUnit != null,
                            texture = model.texture,
                            textureRegion = floatArrayOf(0f, 0f, 1f, 1f),
                        ),
                    )
                }
                updateRemote { it.copy(saved = model) }
                _state.update { it.copy(message = "Saved to My models as “${info.name}”.", isError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the AI model failed", e)
                fail("Couldn't save the model: ${e.readable()}.")
            } finally {
                updateRemote { it.copy(saving = false) }
            }
        }
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

    fun dismissCrash() {
        diagnostics.dismiss()
        _state.update { it.copy(crash = null) }
    }

    /** Turns back on the features a crash turned off; what is known to crash on this phone model stays off. */
    fun turnFeaturesBackOn() {
        diagnostics.turnAllBackOn()
        val stays = diagnostics.turnedOff
        _state.update {
            it.copy(
                turnedOff = stays,
                canTurnBackOn = diagnostics.canTurnBackOn,
                segmentAnythingOffer = segmentAnythingOffer(),
                message = if (stays.isEmpty()) "Every feature is back on." else "Turned back on what could be. ${stays.joinToString(" ") { it.label }}",
                isError = false,
            )
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
        private const val THUMBNAIL = 320
        private const val CUTOUT_SIZE = 1024
        private const val PREVIEW_ENGINE = "preview"

        /** The photo builder needs this many photos to have anything to go on. */
        const val MIN_BUILD_PHOTOS = 6
        private const val MAX_IMPORT_BYTES = 200 * 1024 * 1024
        private const val OUT_OF_MEMORY_MESSAGE = "The phone ran out of memory for this photo. Close other apps and try again."
        const val NO_SUBJECT_MESSAGE =
            "Couldn't separate the subject from the background, so the whole photo was used. A plain background helps."

        fun formatBytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
    }
}
