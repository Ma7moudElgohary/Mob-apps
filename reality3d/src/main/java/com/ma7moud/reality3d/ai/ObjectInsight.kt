package com.ma7moud.reality3d.ai

import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.ShapeProfile

/** Rough shape family of the photographed object. */
enum class ShapeHint { ROUND, BOXY, FLAT, THIN }

/** What Gemini Nano made of the photo. Every field is optional because small models skip lines. */
data class ObjectInsight(
    val name: String? = null,
    val shape: ShapeHint? = null,
    /** Front-to-back depth as a percent of the visible width. */
    val thicknessPercent: Int? = null,
    val tip: String? = null,
) {
    val isEmpty: Boolean get() = name == null && shape == null && thicknessPercent == null && tip == null
}

/** Applies the model's guess to the shape settings: profile and thickness. */
fun MeshSettings.withInsight(insight: ObjectInsight): MeshSettings {
    val fallback = when (insight.shape) {
        ShapeHint.ROUND -> 0.8f
        ShapeHint.BOXY -> 0.7f
        ShapeHint.FLAT -> 0.2f
        ShapeHint.THIN -> 0.1f
        null -> thickness
    }
    val suggested = insight.thicknessPercent?.let { it / 100f } ?: fallback
    return copy(
        profile = if (insight.shape == ShapeHint.BOXY) ShapeProfile.BOXY else if (insight.shape != null) ShapeProfile.ROUND else profile,
        thickness = suggested.coerceIn(MeshSettings.MIN_THICKNESS, MeshSettings.MAX_THICKNESS),
    )
}

object InsightParser {

    const val PROMPT = """You are helping a phone app turn one photo of an object into a 3D model.
Look at the main object in the photo and reply with exactly these four lines and nothing else:
NAME: the object in 2 to 4 words
SHAPE: one word from ROUND, BOXY, FLAT or THIN
THICKNESS: its front-to-back depth as a percent of its visible width, a number from 5 to 100
TIP: one short sentence on how to photograph it better for a 3D model"""

    private val line = Regex("""^[\s*#>\-•]*(NAME|OBJECT|SHAPE|THICKNESS|DEPTH|TIP)[\s*]*[:：=]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val number = Regex("""\d{1,3}""")

    private val shapeWords = listOf(
        ShapeHint.BOXY to listOf("BOXY", "BOX", "CUBE", "RECTANG", "SQUARE", "BLOCK"),
        ShapeHint.FLAT to listOf("FLAT", "PLANAR", "SHEET", "DISC", "DISK"),
        ShapeHint.THIN to listOf("THIN", "SLENDER", "STICK", "WIRE", "ROD"),
        ShapeHint.ROUND to listOf("ROUND", "SPHER", "CYLIND", "CURV", "OVAL", "BALL"),
    )

    fun parse(text: String): ObjectInsight {
        var name: String? = null
        var shape: ShapeHint? = null
        var thickness: Int? = null
        var tip: String? = null
        for (raw in text.lines()) {
            val match = line.find(raw.trim()) ?: continue
            val value = match.groupValues[2].trim().trim('*', '"', '\'', '`', ' ', '.').trim()
            if (value.isEmpty()) continue
            when (match.groupValues[1].uppercase()) {
                "NAME", "OBJECT" -> if (name == null) name = value.take(MAX_NAME)
                "SHAPE" -> if (shape == null) shape = shapeOf(value)
                "THICKNESS", "DEPTH" -> if (thickness == null) {
                    thickness = number.find(value)?.value?.toIntOrNull()?.coerceIn(5, 120)
                }
                "TIP" -> if (tip == null) tip = value.take(MAX_TIP)
            }
        }
        return ObjectInsight(name, shape, thickness, tip)
    }

    private fun shapeOf(value: String): ShapeHint? {
        val upper = value.uppercase()
        return shapeWords.firstOrNull { (_, words) -> words.any { upper.contains(it) } }?.first
    }

    private const val MAX_NAME = 40
    private const val MAX_TIP = 160
}
