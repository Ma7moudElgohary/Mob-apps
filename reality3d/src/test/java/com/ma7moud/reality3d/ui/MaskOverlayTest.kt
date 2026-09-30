package com.ma7moud.reality3d.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskOverlayTest {

    @Test
    fun dimsWhatIsLeftOutAndTintsTheRest() {
        assertEquals(0xAA000000.toInt(), MaskOverlay.color(0f))
        assertEquals(0x2850E3FF, MaskOverlay.color(1f))
        assertEquals(0x6E9A7CFF, MaskOverlay.color(0f, other = 1f))
        // The chosen mask wins over another object at the same pixel.
        assertEquals(MaskOverlay.color(1f), MaskOverlay.color(1f, other = 1f))
    }
}
