package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCoachTest {
    @Test
    fun resumeSeedIsPreserved() {
        val coach = CoverageCoach()
        val seed = BooleanArray(9)
        seed[0] = true
        seed[3] = true
        coach.seed(seed)
        val state = coach.update(Vector3(0f, 0f, 1f), Vector3(0f, 0f, 0f), 1_000_000_000L, 0.9f, true)
        assertTrue(state.covered[0])
        assertTrue(state.covered[3])
        assertTrue(state.coveragePercent >= 22)
    }

    @Test
    fun topCaptureUsesTopBin() {
        val coach = CoverageCoach()
        val state = coach.update(Vector3(0f, 1.2f, 0.4f), Vector3(0f, 0f, 0f), 1_000_000_000L, 0.9f, true)
        assertTrue(state.covered[8])
        assertEquals(11, state.coveragePercent)
    }
}
