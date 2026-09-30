package com.ma7moud.reality3d.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.diagnostics.AppInfo
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.project.ProjectDraft
import com.ma7moud.reality3d.project.ProjectKind
import com.ma7moud.reality3d.remote.PhotoBuildOptions
import com.ma7moud.reality3d.remote.PhotoModel
import com.ma7moud.reality3d.remote.PhotoModelBuilder
import com.ma7moud.reality3d.remote.PhotoZip
import com.ma7moud.reality3d.remote.RemoteException
import com.ma7moud.reality3d.remote.RemoteSettings
import com.ma7moud.reality3d.remote.RemoteSettingsStore
import com.ma7moud.reality3d.remote.ServerAddress
import com.ma7moud.reality3d.remote.quality
import com.ma7moud.reality3d.export.ModelTexture
import com.ma7moud.reality3d.quality.QualityReport
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanLog
import com.ma7moud.reality3d.scan.ScanSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

sealed interface ScanScreenState {
    data object NeedsPermission : ScanScreenState
    data object Checking : ScanScreenState
    data object Installing : ScanScreenState
    data class Unsupported(val reason: String) : ScanScreenState
    data object Scanning : ScanScreenState
    data class Building(val label: String) : ScanScreenState

    /** The photos are being built into a model on the user's computer (this takes minutes). */
    data class Remote(val label: String, val fraction: Float?) : ScanScreenState
    class Result(val capture: ScanCapture) : ScanScreenState
    data class Failed(val message: String) : ScanScreenState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val factory = (application as Reality3DApplication).services.scanner

    private val _screen = MutableStateFlow<ScanScreenState>(ScanScreenState.Checking)
    val screen: StateFlow<ScanScreenState> = _screen.asStateFlow()

    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** A message about exporting, and whether it is an error. */
    val message: StateFlow<Pair<String, Boolean>?> = _message.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _saved = MutableStateFlow<ScanCapture?>(null)

    /** The capture last saved to My models. */
    val saved: StateFlow<ScanCapture?> = _saved.asStateFlow()

    private val projects = (application as Reality3DApplication).services.projects
    private val remoteSettings = RemoteSettingsStore(application)
    private val photoBuilder = PhotoModelBuilder((application as Reality3DApplication).services.remote)

    private val _needsComputer = MutableStateFlow<String?>(null)

    /** Set (to what to tell the person) while the address of their computer is asked for. */
    val needsComputer: StateFlow<String?> = _needsComputer.asStateFlow()

    var engine: ScanEngine? = null
        private set

    /** What this scan went through, for the report the user can copy. */
    private var log = ScanLog()
    private var logJob: Job? = null
    private var lastDetails = ""
    private var installRequested = false
    private var checkJob: Job? = null
    private var buildJob: Job? = null
    private var pendingSave: ExportFile? = null

    fun onPermissionResult(granted: Boolean) {
        if (!granted) _screen.value = ScanScreenState.NeedsPermission
    }

    /** Checks ARCore (and installs it the first time); call whenever the screen resumes with camera access. */
    fun check(activity: Activity) {
        when (_screen.value) {
            ScanScreenState.Scanning, is ScanScreenState.Building, is ScanScreenState.Remote, is ScanScreenState.Result -> return
            else -> Unit
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            repeat(CHECK_ATTEMPTS) {
                when (val support = factory.check(activity, userRequestedInstall = !installRequested)) {
                    ScanSupport.Ready -> {
                        if (engine == null) engine = factory.create(getApplication()).also { watch(it) }
                        _screen.value = ScanScreenState.Scanning
                        return@launch
                    }
                    ScanSupport.Installing -> {
                        installRequested = true
                        _screen.value = ScanScreenState.Installing
                        return@launch
                    }
                    is ScanSupport.Unsupported -> {
                        _screen.value = ScanScreenState.Unsupported(support.reason)
                        return@launch
                    }
                    ScanSupport.Checking -> {
                        _screen.value = ScanScreenState.Checking
                        delay(CHECK_DELAY_MS)
                    }
                }
            }
            _screen.value = ScanScreenState.Unsupported("Couldn't check ARCore on this phone. Try again later.")
        }
    }

    fun build() {
        val current = engine ?: return
        if (_screen.value != ScanScreenState.Scanning) return
        _screen.value = ScanScreenState.Building("Getting ready…")
        buildJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                // Progress keeps coming from the worker thread; it must not bring back a scan that was cancelled.
                val capture = current.build { label -> _screen.update { if (it is ScanScreenState.Building) ScanScreenState.Building(label) else it } }
                log.onBuilt((SystemClock.elapsedRealtime() - started) / 1000f, capture.quality, capture.mesh.triangleCount)
                // The engine stays open (its camera paused) so more views can be added to this scan.
                _screen.value = ScanScreenState.Result(capture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "Building the scan failed", e)
                log.onBuildFailed("${e.javaClass.simpleName}: ${e.message}")
                release()
                _screen.value = ScanScreenState.Failed("Couldn't build the model: ${e.message ?: e.javaClass.simpleName}.")
            }
        }
    }

    /** The computer the photos go to: asks for its address when there is none yet. */
    fun buildOnComputer() {
        if (_screen.value != ScanScreenState.Scanning) return
        if (remoteSettings.load().url.isBlank()) {
            _needsComputer.value = "Run the Reality3D server on your computer and type the address it shows. The phone and the computer must be on the same Wi-Fi."
            return
        }
        sendPhotos()
    }

    fun saveComputer(address: String, token: String) {
        val url = try {
            ServerAddress.normalize(address)
        } catch (e: RemoteException) {
            _needsComputer.value = e.message
            return
        }
        remoteSettings.save(remoteSettings.load().copy(url = url, token = token.trim()))
        _needsComputer.value = null
        sendPhotos()
    }

    fun dismissComputer() {
        _needsComputer.value = null
    }

    /** Zips the scan's photos, has the computer build the model from them, and shows what comes back. */
    private fun sendPhotos() {
        val current = engine ?: return
        val settings = remoteSettings.load()
        _message.value = null
        _screen.value = ScanScreenState.Remote("Getting the photos ready…", null)
        log.event("Building on the computer")
        buildJob = viewModelScope.launch {
            val zip = File(getApplication<Application>().cacheDir, "scan_photos.zip")
            try {
                val info = photoBuilder.checkServer(settings)
                val photos = current.photoSet() ?: throw RemoteException("There are no photos yet. Walk around the object first.")
                val zipped = withContext(Dispatchers.IO) { PhotoZip.fromScan(photos, zip) }
                val model = photoBuilder.build(settings.copy(engine = info.id), zipped.file, PhotoBuildOptions()) { fraction, label ->
                    _screen.update { if (it is ScanScreenState.Remote) ScanScreenState.Remote(label ?: it.label, fraction ?: it.fraction) else it }
                }
                val capture = ScanCapture(
                    model.mesh, photos.keyframes, model.quality(photos.keyframes.size),
                    texture = model.texture, metersPerUnit = model.metersPerUnit, sizeKnown = model.sizeKnown, fromPhotos = true,
                )
                log.event("Built on the computer from ${photos.keyframes.size} photos (${model.placed ?: "?"} placed), ${model.mesh.triangleCount} triangles")
                _screen.value = ScanScreenState.Result(capture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "Building on the computer failed", e)
                log.event("Building on the computer failed: ${e.message}")
                // Back to the camera: the photos are still there, and more of them may be what was missing.
                _message.value = (e.message ?: e.javaClass.simpleName) to true
                _screen.value = ScanScreenState.Scanning
            } finally {
                zip.delete()
            }
        }
    }

    /** Gives up on the computer's build and goes back to the camera with the scan as it was. */
    fun cancelComputer() {
        buildJob?.cancel()
        if (_screen.value is ScanScreenState.Remote) _screen.value = ScanScreenState.Scanning
    }

    /** Back to the camera with everything scanned so far, to fill in what the first build missed. */
    fun addMoreViews() {
        val current = engine ?: return
        if (_screen.value !is ScanScreenState.Result) return
        _message.value = null
        log.event("Added more views")
        current.continueScanning()
        _screen.value = ScanScreenState.Scanning
    }

    /** Back to the start, ready for a new scan. */
    fun reset() {
        checkJob?.cancel()
        buildJob?.cancel()
        _needsComputer.value = null
        release()
        installRequested = false
        _message.value = null
        _screen.value = ScanScreenState.Checking
    }

    /** Starts over at once, for example after a failed scan. */
    fun retry(activity: Activity) {
        reset()
        check(activity)
    }

    private fun release() {
        engine?.let {
            lastDetails = it.details
            it.pause()
            it.close()
        }
        engine = null
        logJob?.cancel()
    }

    /** Starts a fresh log for a new scan and feeds it what the engine reports. */
    private fun watch(scan: ScanEngine) {
        log = ScanLog()
        lastDetails = ""
        logJob?.cancel()
        logJob = viewModelScope.launch { scan.status.collect { log.onStatus(it) } }
    }

    /** What happened in this scan, as text to copy: the phone, what the camera and depth gave, warnings, the build. */
    fun scanReport(): String = log.report(AppInfo.header(getApplication()), engine?.details ?: lastDetails)

    override fun onCleared() = release()

    suspend fun export(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val result = _screen.value as? ScanScreenState.Result ?: return null
        _exporting.value = true
        return try {
            withContext(Dispatchers.Default) {
                val capture = result.capture
                Exporter.encode(
                    format, capture.mesh, capture.texture?.let { ModelTexture(it, floatArrayOf(0f, 0f, 1f, 1f)) }, baseName, capture.keyframes, budget,
                    longestSideMeters = capture.metersPerUnit * capture.mesh.longestSide,
                )
            }
        } finally {
            _exporting.value = false
        }
    }

    /** Saves the scanned model to My models, with its first photo as the thumbnail. */
    fun saveProject() {
        val capture = (_screen.value as? ScanScreenState.Result)?.capture ?: return
        if (_saved.value === capture) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    projects.save(
                        ProjectDraft(
                            name = (if (capture.fromPhotos) "Photo model " else "Scan ") + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date()),
                            kind = if (capture.fromPhotos) ProjectKind.PHOTOGRAMMETRY else ProjectKind.SCAN,
                            mesh = capture.mesh,
                            thumbnail = thumbnailOf(capture) ?: createBitmap(8, 8),
                            metersPerUnit = capture.metersPerUnit,
                            sizeKnown = capture.sizeKnown,
                            quality = capture.quality,
                            texture = capture.texture,
                            textureRegion = if (capture.texture != null) floatArrayOf(0f, 0f, 1f, 1f) else null,
                        ),
                    )
                }
                _saved.value = capture
                _message.value = "Saved to My models as “${info.name}”." to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the scan failed", e)
                _message.value = "Couldn't save the scan: ${e.message ?: e.javaClass.simpleName}." to true
            }
        }
    }

    /** The first scan photo, small. */
    private fun thumbnailOf(capture: ScanCapture): Bitmap? {
        val jpeg = capture.keyframes.firstOrNull()?.jpeg ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

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
                _message.value = "Saved ${file.fileName}." to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn't save the file: ${e.message ?: e.javaClass.simpleName}." to true
            }
        }
    }

    fun showMessage(text: String, isError: Boolean) {
        _message.value = text to isError
    }

    private val baseName: String
        get() = "scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    private companion object {
        const val TAG = "Reality3DScan"
        const val CHECK_ATTEMPTS = 20
        const val CHECK_DELAY_MS = 300L
        const val THUMBNAIL = 320
    }
}
