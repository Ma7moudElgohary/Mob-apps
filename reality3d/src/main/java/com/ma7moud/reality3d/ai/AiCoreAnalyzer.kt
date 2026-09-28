package com.ma7moud.reality3d.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

sealed interface AiState {
    data object Checking : AiState
    data object Ready : AiState
    data object NeedsDownload : AiState
    data class Downloading(val progress: Float?) : AiState
    data object NotSupported : AiState
    data class Failed(val message: String) : AiState
}

/**
 * Looks at the photo and advises on the reconstruction: what the object is, its shape, what will be hard
 * (shine, transparency, thin parts, holes) and whether a 360° scan would do better.
 */
interface ObjectAi {
    val state: StateFlow<AiState>

    /** Re-reads whether the model is ready; cheap enough to call on every resume. */
    fun refresh()

    fun download()

    suspend fun describe(photo: Bitmap): ObjectInsight
}

class AiUnavailableException : Exception("Gemini Nano isn't ready on this phone")

/** Gemini Nano through ML Kit's GenAI Prompt API and Android AICore. The photo never leaves the phone. */
class AiCoreAnalyzer(private val scope: CoroutineScope) : ObjectAi {

    private val model: GenerativeModel by lazy { Generation.getClient() }
    private val _state = MutableStateFlow<AiState>(AiState.Checking)
    override val state: StateFlow<AiState> = _state.asStateFlow()

    // Gemini Nano runs one request at a time.
    private val lock = Mutex()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    override fun refresh() {
        val current = _state.value
        if (current is AiState.Ready || current is AiState.Downloading) return
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = scope.launch { _state.value = query() }
    }

    override fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.value = AiState.Downloading(null)
            var total = 0L
            try {
                model.download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted -> {
                            total = event.bytesToDownload
                            _state.value = AiState.Downloading(if (total > 0) 0f else null)
                        }
                        is DownloadStatus.DownloadProgress -> _state.value = AiState.Downloading(
                            if (total > 0) (event.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null,
                        )
                        is DownloadStatus.DownloadCompleted -> _state.value = AiState.Ready
                        is DownloadStatus.DownloadFailed -> _state.value = AiState.Failed(event.e.message ?: "Download failed")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AiState.Failed(e.message ?: "Download failed")
            }
            if (_state.value is AiState.Downloading) _state.value = query()
        }
    }

    override suspend fun describe(photo: Bitmap): ObjectInsight = lock.withLock {
        if (_state.value != AiState.Ready) {
            val fresh = query()
            _state.value = fresh
            if (fresh != AiState.Ready) throw AiUnavailableException()
        }
        val request = generateContentRequest(ImagePart(photo), TextPart(InsightParser.PROMPT)) {
            temperature = 0.1f
            topK = 8
            maxOutputTokens = 320
        }
        val text = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
        InsightParser.parse(text).also {
            if (it.isEmpty) throw IllegalStateException("its answer wasn't in the expected format")
        }
    }

    private suspend fun query(): AiState = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiState.Ready
            FeatureStatus.UNAVAILABLE -> AiState.NotSupported
            else -> AiState.NeedsDownload
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AiState.Failed(e.message ?: e.javaClass.simpleName)
    }
}
