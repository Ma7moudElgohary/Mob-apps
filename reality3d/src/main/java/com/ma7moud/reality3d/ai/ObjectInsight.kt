package com.ma7moud.reality3d.ai

import com.ma7moud.reality3d.mesh.MeshDetail
import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.ShapeProfile

/** Rough shape family of the photographed object. */
enum class ShapeHint { ROUND, BOXY, FLAT, THIN }

/** How the object's surface looks to a camera: plain surfaces give depth estimation little to hold on to. */
enum class SurfaceType { MATTE, GLOSSY, TEXTURED, PLAIN }

/** How to capture the object: one photo, or walking round it with the 360° scan. */
enum class CaptureMode { SINGLE_PHOTO, SCAN_360 }

/**
 * Gemini Nano's reading of the photo, used to set up the reconstruction and to advise on capturing the
 * object. Every field is optional because small models skip some.
 */
data class ObjectInsight(
    val name: String? = null,
    val shape: ShapeHint? = null,
    /** Front-to-back depth as a percent of the visible width. */
    val thicknessPercent: Int? = null,
    val tip: String? = null,
    val surface: SurfaceType? = null,
    val reflective: Boolean? = null,
    val transparent: Boolean? = null,
    /** Handles, straps, legs, wires: parts a coarse mesh can lose. */
    val thinParts: Boolean? = null,
    /** Gaps you can see through, which one photo can't model. */
    val holes: Boolean? = null,
    val recommendedMode: CaptureMode? = null,
    /** How sure the model is, 0..1. */
    val confidence: Float? = null,
    val advice: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = name == null && shape == null && thicknessPercent == null && tip == null && surface == null &&
            reflective == null && transparent == null && thinParts == null && holes == null &&
            recommendedMode == null && confidence == null && advice.isEmpty()

    /** The capture tips to show: the list, or the single tip of the older line format. */
    val tips: List<String> get() = advice.ifEmpty { listOfNotNull(tip) }
}

/**
 * Applies the model's reading to the shape settings: profile and thickness, and High detail when thin
 * parts would otherwise be lost.
 */
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
        detail = if (insight.thinParts == true) MeshDetail.HIGH else detail,
    )
}

object InsightParser {

    /** Asks for strict JSON; [parse] still copes with the fences, stray text and slips small models make. */
    const val PROMPT = """You are the reconstruction advisor of a phone app that turns photos of an object into a 3D model.
Look at the main object in the photo. Reply with one JSON object and nothing else, with exactly these keys:
{"objectType": the object in 2 to 4 words,
"shape": one of "ROUND", "BOXY", "FLAT", "THIN",
"thicknessPercent": its front-to-back depth as a percent of its visible width, a number from 5 to 100,
"surfaceType": one of "MATTE", "GLOSSY", "TEXTURED", "PLAIN",
"reflective": true or false,
"transparent": true or false,
"thinStructures": true if it has thin parts such as handles, straps, legs or wires,
"holes": true if there are gaps you can see through,
"recommendedMode": "SINGLE_PHOTO" if one photo shows its shape well, otherwise "SCAN_360",
"confidence": how sure you are, from 0.0 to 1.0,
"captureAdvice": a list of up to three short tips for photographing or scanning it}"""

    private val line = Regex("""^[\s*#>\-•]*(NAME|OBJECT|SHAPE|THICKNESS|DEPTH|TIP)[\s*]*[:：=]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val number = Regex("""-?\d+(?:[.,]\d+)?""")

    private val shapeWords = listOf(
        ShapeHint.BOXY to listOf("BOXY", "BOX", "CUBE", "RECTANG", "SQUARE", "BLOCK"),
        ShapeHint.FLAT to listOf("FLAT", "PLANAR", "SHEET", "DISC", "DISK"),
        ShapeHint.THIN to listOf("THIN", "SLENDER", "STICK", "WIRE", "ROD"),
        ShapeHint.ROUND to listOf("ROUND", "SPHER", "CYLIND", "CURV", "OVAL", "BALL"),
    )

    private val surfaceWords = listOf(
        SurfaceType.GLOSSY to listOf("GLOSS", "SHINY", "POLISH", "METALLIC", "GLASSY"),
        SurfaceType.TEXTURED to listOf("TEXTUR", "PATTERN", "ROUGH", "DETAILED"),
        SurfaceType.PLAIN to listOf("PLAIN", "UNIFORM", "FEATURELESS", "SMOOTH"),
        SurfaceType.MATTE to listOf("MATTE", "MATT", "DULL"),
    )

    /** Reads the JSON answer, or the older four-line one. */
    fun parse(text: String): ObjectInsight =
        if ('{' in text) parseJson(text).takeUnless { it.isEmpty } ?: parseLines(text) else parseLines(text)

    private fun parseJson(text: String): ObjectInsight {
        val json = text.substringAfter('{')
        // Small models sometimes echo the prompt's descriptions instead of answering; those count as blank.
        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { rawValue(json, it) }?.takeUnless(::isTemplate)
        val advice = field("captureAdvice", "advice", "tips")?.let(::strings).orEmpty()
            .map { it.trim().trimEnd('.').take(MAX_TIP) }
            .filter { it.isNotEmpty() }
            .take(MAX_ADVICE)
        return ObjectInsight(
            name = field("objectType", "object", "name")?.let(::string)?.take(MAX_NAME),
            shape = field("shape")?.let(::string)?.let(::shapeOf),
            thicknessPercent = field("thicknessPercent", "thickness", "depthPercent")?.let(::numberOf)
                ?.let { if (it > 0f && it <= 1f) it * 100 else it }?.toInt()?.coerceIn(5, 120),
            surface = field("surfaceType", "surface")?.let(::string)?.let(::surfaceOf),
            reflective = field("reflective", "shiny")?.let(::booleanOf),
            transparent = field("transparent", "seeThrough")?.let(::booleanOf),
            thinParts = field("thinStructures", "thinParts")?.let(::booleanOf),
            holes = field("holes", "hasHoles")?.let(::booleanOf),
            recommendedMode = field("recommendedMode", "mode")?.let(::string)?.let(::modeOf),
            confidence = field("confidence")?.let(::numberOf)?.let { if (it > 1f) it / 100f else it }?.coerceIn(0f, 1f),
            advice = advice,
        )
    }

    /**
     * The raw value after `"key":` (a quoted string, a list or a bare word), tolerating single quotes,
     * bare keys and missing commas; null when the key is absent.
     */
    private fun rawValue(json: String, key: String): String? {
        val pattern = Regex(
            """(?<![A-Za-z0-9_])["']?${Regex.escape(key)}["']?\s*:\s*("(?:[^"\\]|\\.)*"?|'[^']*'?|\[[^\]]*]?|[^,}\n]+)""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(json)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isTemplate(raw: String): Boolean {
        val lower = raw.lowercase()
        return listOf("one of", "true or false", "a number", "how sure", "a list of", "2 to 4 words").any { it in lower }
    }

    private fun string(raw: String): String? {
        val value = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim('"', '\'')
            .replace("\\\"", "\"").replace("\\n", " ").trim()
        return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun strings(raw: String): List<String> {
        val body = raw.trim()
        if (!body.startsWith("[")) return listOfNotNull(string(body))
        return Regex(""""((?:[^"\\]|\\.)*)"|'([^']*)'""").findAll(body)
            .mapNotNull { match -> string(match.groupValues[1].ifEmpty { match.groupValues[2] }) }
            .toList()
    }

    private fun booleanOf(raw: String): Boolean? = when (string(raw)?.lowercase()) {
        "true", "yes", "y" -> true
        "false", "no", "n" -> false
        else -> null
    }

    private fun numberOf(raw: String): Float? = number.find(raw)?.value?.replace(',', '.')?.toFloatOrNull()

    private fun modeOf(value: String): CaptureMode? {
        val upper = value.uppercase()
        val scan = "360" in upper || "SCAN" in upper
        val photo = "SINGLE" in upper || "PHOTO" in upper
        return when {
            scan && !photo -> CaptureMode.SCAN_360
            photo && !scan -> CaptureMode.SINGLE_PHOTO
            else -> null
        }
    }

    /** The one family [value] names; null when it names none, or several (an echoed list of options). */
    private fun <T> familyOf(value: String, families: List<Pair<T, List<String>>>): T? {
        val upper = value.uppercase()
        return families.filter { (_, words) -> words.any { upper.contains(it) } }.map { it.first }.singleOrNull()
    }

    private fun surfaceOf(value: String): SurfaceType? = familyOf(value, surfaceWords)

    private fun parseLines(text: String): ObjectInsight {
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
                    thickness = Regex("""\d{1,3}""").find(value)?.value?.toIntOrNull()?.coerceIn(5, 120)
                }
                "TIP" -> if (tip == null) tip = value.take(MAX_TIP)
            }
        }
        return ObjectInsight(name, shape, thickness, tip)
    }

    private fun shapeOf(value: String): ShapeHint? = familyOf(value, shapeWords)

    private const val MAX_NAME = 40
    private const val MAX_TIP = 160
    private const val MAX_ADVICE = 3
}
