package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifiedCoverageTrackerTest {

    @Test
    fun photoAloneDoesNotQualifyCoverage() {
        val coverage = QualifiedCoverageTracker()
        assertFalse(coverage.markPhoto(4))
        assertFalse(coverage.covered[4])
        assertEquals(1, coverage.photoCells)
        assertEquals(0, coverage.depthCells)
        assertEquals(0, coverage.qualifiedCells)
    }

    @Test
    fun depthAloneDoesNotQualifyCoverage() {
        val coverage = QualifiedCoverageTracker()
        assertFalse(coverage.markDepth(4))
        assertFalse(coverage.covered[4])
        assertEquals(0, coverage.photoCells)
        assertEquals(1, coverage.depthCells)
        assertEquals(0, coverage.qualifiedCells)
    }

    @Test
    fun photoThenDepthQualifiesExactlyOnce() {
        val coverage = QualifiedCoverageTracker()
        assertFalse(coverage.markPhoto(4))
        assertTrue(coverage.markDepth(4))
        assertTrue(coverage.covered[4])
        assertFalse(coverage.markDepth(4))
        assertFalse(coverage.markPhoto(4))
        assertEquals(1, coverage.qualifiedCells)
    }

    @Test
    fun depthThenPhotoAlsoQualifies() {
        val coverage = QualifiedCoverageTracker()
        assertFalse(coverage.markDepth(9))
        assertTrue(coverage.markPhoto(9))
        assertTrue(coverage.covered[9])
    }

    @Test
    fun resetClearsAllThreeCoverageStates() {
        val coverage = QualifiedCoverageTracker()
        coverage.markPhoto(2)
        coverage.markDepth(2)
        coverage.markPhoto(3)
        coverage.markDepth(5)

        coverage.reset()

        assertEquals(0, coverage.photoCells)
        assertEquals(0, coverage.depthCells)
        assertEquals(0, coverage.qualifiedCells)
        assertEquals(0f, coverage.fraction, 0f)
    }
}
