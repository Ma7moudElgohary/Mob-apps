package com.ma7moud.reality3d.ai

import com.ma7moud.reality3d.mesh.MeshDetail
import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.ShapeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightParserTest {

    @Test
    fun readsTheFourLines() {
        val insight = InsightParser.parse(
            """
            NAME: White ceramic mug
            SHAPE: ROUND
            THICKNESS: 90
            TIP: Shoot from slightly above in soft light.
            """.trimIndent(),
        )
        assertEquals(ObjectInsight("White ceramic mug", ShapeHint.ROUND, 90, "Shoot from slightly above in soft light"), insight)
    }

    @Test
    fun toleratesMarkdownAndSynonyms() {
        val insight = InsightParser.parse(
            """
            Here is the analysis:
            **NAME:** Running shoe
            - **Shape**: rectangular, like a box.
            * Thickness = about 35% of the width
            > TIP: "Use a plain background"
            """.trimIndent(),
        )
        assertEquals("Running shoe", insight.name)
        assertEquals(ShapeHint.BOXY, insight.shape)
        assertEquals(35, insight.thicknessPercent)
        assertEquals("Use a plain background", insight.tip)
    }

    @Test
    fun mapsShapeWords() {
        assertEquals(ShapeHint.ROUND, InsightParser.parse("SHAPE: cylindrical").shape)
        assertEquals(ShapeHint.FLAT, InsightParser.parse("SHAPE: flat disc").shape)
        assertEquals(ShapeHint.THIN, InsightParser.parse("SHAPE: thin rod").shape)
        assertNull(InsightParser.parse("SHAPE: irregular").shape)
    }

    @Test
    fun clampsNumbersAndKeepsFirstAnswer() {
        val insight = InsightParser.parse("THICKNESS: 250\nTHICKNESS: 40\nNAME: A\nNAME: B")
        assertEquals(120, insight.thicknessPercent)
        assertEquals("A", insight.name)
        assertEquals(5, InsightParser.parse("DEPTH: 1%").thicknessPercent)
    }

    @Test
    fun unrelatedTextIsEmpty() {
        assertTrue(InsightParser.parse("I cannot help with that.").isEmpty)
        assertTrue(InsightParser.parse("").isEmpty)
    }

    @Test
    fun readsTheJsonAnswer() {
        val insight = InsightParser.parse(
            """
            {"objectType": "Leather office chair", "shape": "BOXY", "thicknessPercent": 85,
             "surfaceType": "GLOSSY", "reflective": true, "transparent": false, "thinStructures": true,
             "holes": true, "recommendedMode": "SCAN_360", "confidence": 0.82,
             "captureAdvice": ["Walk all the way round it", "Avoid direct sunlight on the leather."]}
            """.trimIndent(),
        )
        assertEquals(
            ObjectInsight(
                name = "Leather office chair", shape = ShapeHint.BOXY, thicknessPercent = 85, surface = SurfaceType.GLOSSY,
                reflective = true, transparent = false, thinParts = true, holes = true,
                recommendedMode = CaptureMode.SCAN_360, confidence = 0.82f,
                advice = listOf("Walk all the way round it", "Avoid direct sunlight on the leather"),
            ),
            insight,
        )
        assertEquals(insight.advice, insight.tips)
    }

    @Test
    fun copesWithTheSlipsSmallModelsMake() {
        // A code fence, single quotes, a bare key, yes/no, a percentage and a trailing comma, cut off early.
        val insight = InsightParser.parse(
            """
            Sure! Here is the JSON:
            ```json
            { 'objectType': 'Glass vase', shape: "round", "transparent": "yes", "reflective": no,
              "confidence": "70%", "recommendedMode": "single photo", "thicknessPercent": 0.9,
              "captureAdvice": "Put it on a dark cloth",
            """.trimIndent(),
        )
        assertEquals("Glass vase", insight.name)
        assertEquals(ShapeHint.ROUND, insight.shape)
        assertEquals(true, insight.transparent)
        assertEquals(false, insight.reflective)
        assertEquals(0.7f, insight.confidence!!, 1e-6f)
        assertEquals(CaptureMode.SINGLE_PHOTO, insight.recommendedMode)
        assertEquals(90, insight.thicknessPercent)
        assertEquals(listOf("Put it on a dark cloth"), insight.advice)
        assertNull(insight.holes)
    }

    @Test
    fun echoedOptionsAreNotAnswers() {
        val insight = InsightParser.parse(
            """{"objectType": "Mug", "shape": "one of ROUND, BOXY, FLAT, THIN", "reflective": "true or false",
            "surfaceType": "MATTE or GLOSSY", "recommendedMode": "SINGLE_PHOTO or SCAN_360", "confidence": "how sure you are, from 0.0 to 1.0"}""",
        )
        assertEquals("Mug", insight.name)
        assertNull(insight.shape)
        assertNull(insight.reflective)
        assertNull(insight.surface)
        assertNull(insight.recommendedMode)
        assertNull(insight.confidence)
    }

    @Test
    fun mixedAnswersStillRead() {
        val insight = InsightParser.parse("{ }\nNAME: Teapot\nSHAPE: round")
        assertEquals("Teapot", insight.name)
        assertEquals(ShapeHint.ROUND, insight.shape)
    }

    @Test
    fun insightSetsShapeAndThickness() {
        val base = MeshSettings(profile = ShapeProfile.ROUND, thickness = 0.8f)
        assertEquals(ShapeProfile.BOXY, base.withInsight(ObjectInsight(shape = ShapeHint.BOXY)).profile)
        assertEquals(0.7f, base.withInsight(ObjectInsight(shape = ShapeHint.BOXY)).thickness, 1e-6f)
        assertEquals(0.2f, base.withInsight(ObjectInsight(shape = ShapeHint.FLAT)).thickness, 1e-6f)
        assertEquals(0.35f, base.withInsight(ObjectInsight(shape = ShapeHint.ROUND, thicknessPercent = 35)).thickness, 1e-6f)
        assertEquals(MeshSettings.MAX_THICKNESS, base.withInsight(ObjectInsight(thicknessPercent = 120)).thickness, 1e-6f)
        // Thin parts need the finer mesh.
        assertEquals(MeshDetail.HIGH, base.withInsight(ObjectInsight(thinParts = true)).detail)
        assertEquals(base.detail, base.withInsight(ObjectInsight(thinParts = false)).detail)
        // Nothing useful: settings stay as they were.
        assertEquals(base, base.withInsight(ObjectInsight(name = "Mug")))
    }
}
