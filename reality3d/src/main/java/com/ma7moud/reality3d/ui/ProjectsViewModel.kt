package com.ma7moud.reality3d.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.export.ModelTexture
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.project.Project
import com.ma7moud.reality3d.project.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** The gallery of saved models and the one that is open. */
class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as Reality3DApplication).services.projects

    /** Newest first; null while the folder is read for the first time. */
    val projects: StateFlow<List<ProjectInfo>?> = store.projects

    private val _current = MutableStateFlow<Project?>(null)
    val current: StateFlow<Project?> = _current.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)
    val message: StateFlow<Pair<String, Boolean>?> = _message.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private var pendingSave: ExportFile? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { io("Couldn't read your models") { store.refresh() } }
    }

    fun open(id: String) {
        _message.value = null
        _current.value = null
        _loading.value = true
        viewModelScope.launch {
            try {
                _current.value = io("Couldn't open that model") { store.load(id) }
            } finally {
                _loading.value = false
            }
        }
    }

    fun close() {
        _current.value = null
        _message.value = null
    }

    fun rename(name: String) {
        val project = _current.value ?: return
        viewModelScope.launch {
            io("Couldn't rename it") {
                store.rename(project.info.id, name)
                store.load(project.info.id)
            }?.let { _current.value = it }
        }
    }

    /** Sets the saved model's real size from a measured length. */
    fun setRealLength(modelUnits: Float, meters: Float) {
        val project = _current.value ?: return
        if (modelUnits <= 0f || meters <= 0f) return
        viewModelScope.launch {
            io("Couldn't save the size") {
                store.setScale(project.info.id, meters / modelUnits, project.mesh)
                store.load(project.info.id)
            }?.let {
                _current.value = it
                _message.value = "Real size set. Exports use it too." to false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            io("Couldn't delete it") { store.delete(id) }
            if (_current.value?.info?.id == id) _current.value = null
        }
    }

    suspend fun export(format: ExportFormat, budget: GameReadyPack.Budget = GameReadyPack.Budget.MEDIUM): ExportFile? {
        val project = _current.value ?: return null
        _exporting.value = true
        return try {
            withContext(Dispatchers.Default) {
                val texture = project.texture?.let { ModelTexture(it, project.textureRegion ?: floatArrayOf(0f, 0f, 1f, 1f)) }
                Exporter.encode(
                    format, project.mesh, texture, baseName(project.info.name), budget = budget,
                    longestSideMeters = project.metersPerUnit * project.mesh.longestSide,
                )
            }
        } finally {
            _exporting.value = false
        }
    }

    fun holdForSaving(file: ExportFile) {
        pendingSave = file
    }

    fun savePending(uri: Uri?) {
        val file = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        viewModelScope.launch {
            io("Couldn't save the file") {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                    ?: throw IOException("no output stream")
            }?.let { _message.value = "Saved ${file.fileName}." to false }
        }
    }

    fun showMessage(text: String, isError: Boolean) {
        _message.value = text to isError
    }

    private suspend fun <T> io(failure: String, block: () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, failure, e)
        _message.value = "$failure: ${e.message ?: e.javaClass.simpleName}." to true
        null
    }

    private fun baseName(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "reality3d_model" }

    private companion object {
        const val TAG = "Reality3DProjects"
    }
}
