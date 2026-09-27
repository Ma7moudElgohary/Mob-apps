package com.ma7moud.reality3d.ai

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
    fun insightSetsShapeAndThickness() {
        val base = MeshSettings(profile = ShapeProfile.ROUND, thickness = 0.8f)
        assertEquals(ShapeProfile.BOXY, base.withInsight(ObjectInsight(shape = ShapeHint.BOXY)).profile)
        assertEquals(0.7f, base.withInsight(ObjectInsight(shape = ShapeHint.BOXY)).thickness, 1e-6f)
        assertEquals(0.2f, base.withInsight(ObjectInsight(shape = ShapeHint.FLAT)).thickness, 1e-6f)
        assertEquals(0.35f, base.withInsight(ObjectInsight(shape = ShapeHint.ROUND, thicknessPercent = 35)).thickness, 1e-6f)
        assertEquals(MeshSettings.MAX_THICKNESS, base.withInsight(ObjectInsight(thicknessPercent = 120)).thickness, 1e-6f)
        // Nothing useful: settings stay as they were.
        assertEquals(base, base.withInsight(ObjectInsight(name = "Mug")))
    }
}
