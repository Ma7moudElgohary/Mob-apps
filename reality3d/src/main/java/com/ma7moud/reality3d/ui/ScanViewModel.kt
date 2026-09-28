package com.ma7moud.reality3d.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.project.ProjectDraft
import com.ma7moud.reality3d.project.ProjectKind
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
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

    var engine: ScanEngine? = null
        private set

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
            ScanScreenState.Scanning, is ScanScreenState.Building, is ScanScreenState.Result -> return
            else -> Unit
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            repeat(CHECK_ATTEMPTS) {
                when (val support = factory.check(activity, userRequestedInstall = !installRequested)) {
                    ScanSupport.Ready -> {
                        if (engine == null) engine = factory.create(getApplication())
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
            try {
                // Progress keeps coming from the worker thread; it must not bring back a scan that was cancelled.
                val capture = current.build { label -> _screen.update { if (it is ScanScreenState.Building) ScanScreenState.Building(label) else it } }
                // The engine stays open (its camera paused) so more views can be added to this scan.
                _screen.value = ScanScreenState.Result(capture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "Building the scan failed", e)
                release()
                _screen.value = ScanScreenState.Failed("Couldn't build the model: ${e.message ?: e.javaClass.simpleName}.")
            }
        }
    }

    /** Back to the camera with everything scanned so far, to fill in what the first build missed. */
    fun addMoreViews() {
        val current = engine ?: return
        if (_screen.value !is ScanScreenState.Result) return
        _message.value = null
        current.continueScanning()
        _screen.value = ScanScreenState.Scanning
    }

    /** Back to the start, ready for a new scan. */
    fun reset() {
        checkJob?.cancel()
        buildJob?.cancel()
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
            it.pause()
            it.close()
        }
        engine = null
    }

    override fun onCleared() = release()

    suspend fun export(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val result = _screen.value as? ScanScreenState.Result ?: return null
        _exporting.value = true
        return try {
            withContext(Dispatchers.Default) {
                Exporter.encode(format, result.capture.mesh, null, baseName, result.capture.keyframes, budget)
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
                            name = "Scan " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date()),
                            kind = ProjectKind.SCAN,
                            mesh = capture.mesh,
                            thumbnail = thumbnailOf(capture) ?: createBitmap(8, 8),
                            metersPerUnit = 1f,
                            sizeKnown = true,
                            quality = capture.quality,
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
