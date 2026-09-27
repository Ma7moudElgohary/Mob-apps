package com.ma7moud.reality3d.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

class AiCoreAnalyzer {
    private val model = Generation.getClient()

    suspend fun status(): AiCoreState = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> AiCoreState.Available
        FeatureStatus.DOWNLOADABLE -> AiCoreState.Downloadable
        FeatureStatus.DOWNLOADING -> AiCoreState.Downloading
        else -> AiCoreState.Unavailable
    }

    suspend fun analyze(bitmap: Bitmap): String {
        val request = generateContentRequest(
            ImagePart(bitmap),
            TextPart("Analyze the main physical object in this photo for 3D reconstruction. Return concise plain text with headings OBJECT, MATERIALS, SHAPE, RECONSTRUCTION RISKS, PHOTO TIP. Do not invent dimensions."),
        ) {
            temperature = 0.2f
            maxOutputTokens = 320
        }
        return model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
            .ifBlank { "AICore returned no text." }
    }

    fun close() = model.close()
}

sealed interface AiCoreState {
    data object Available : AiCoreState
    data object Downloadable : AiCoreState
    data object Downloading : AiCoreState
    data object Unavailable : AiCoreState
}
