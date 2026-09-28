package com.ma7moud.reality3d.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import org.json.JSONObject

data class ReconstructionAdvice(
    val objectType: String,
    val surfaceType: String,
    val reflective: Boolean,
    val transparent: Boolean,
    val thinStructures: Boolean,
    val holes: Boolean,
    val recommendedMode: String,
    val confidence: Int,
    val captureAdvice: List<String>,
    val raw: String,
)

class AiCoreAnalyzer {
    private val model = Generation.getClient()

    suspend fun status(): AiCoreState = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> AiCoreState.Available
        FeatureStatus.DOWNLOADABLE -> AiCoreState.Downloadable
        FeatureStatus.DOWNLOADING -> AiCoreState.Downloading
        else -> AiCoreState.Unavailable
    }

    suspend fun analyze(bitmap: Bitmap): ReconstructionAdvice {
        val request = generateContentRequest(
            ImagePart(bitmap),
            TextPart(
                """
                You are the on-device reconstruction advisor for Reality3D.
                Analyze only the main physical object. Return STRICT JSON and nothing else:
                {
                  "objectType":"...",
                  "surfaceType":"matte|mixed|reflective|transparent",
                  "reflective":false,
                  "transparent":false,
                  "thinStructures":false,
                  "holes":false,
                  "recommendedMode":"quick|scan360|ai3d",
                  "confidence":0,
                  "captureAdvice":["..."]
                }
                confidence is 0..100. Prefer scan360 for objects with occlusion, holes, wheels,
                handles, thin structures, shiny/transparent surfaces, or important hidden sides.
                Never invent dimensions.
                """.trimIndent(),
            ),
        ) {
            temperature = 0.1f
            maxOutputTokens = 360
        }
        val raw = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
        return parseAdvice(raw)
    }

    private fun parseAdvice(raw: String): ReconstructionAdvice {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val json = JSONObject(clean)
            val advice = json.optJSONArray("captureAdvice")
            ReconstructionAdvice(
                objectType = json.optString("objectType", "object"),
                surfaceType = json.optString("surfaceType", "mixed"),
                reflective = json.optBoolean("reflective", false),
                transparent = json.optBoolean("transparent", false),
                thinStructures = json.optBoolean("thinStructures", false),
                holes = json.optBoolean("holes", false),
                recommendedMode = json.optString("recommendedMode", "quick"),
                confidence = json.optInt("confidence", 50).coerceIn(0, 100),
                captureAdvice = buildList {
                    if (advice != null) for (i in 0 until advice.length()) add(advice.optString(i))
                }.filter { it.isNotBlank() },
                raw = clean,
            )
        }.getOrElse {
            ReconstructionAdvice("object", "mixed", false, false, false, false, "quick", 40, listOf(raw.ifBlank { "No advice returned." }), raw)
        }
    }

    fun close() = model.close()
}

sealed interface AiCoreState {
    data object Available : AiCoreState
    data object Downloadable : AiCoreState
    data object Downloading : AiCoreState
    data object Unavailable : AiCoreState
}
