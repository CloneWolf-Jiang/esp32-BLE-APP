package com.clonewolf.esphomeble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBandFilterTest {
    @Test
    fun classifiesCommonWifiBandsAndUnknownFrequency() {
        assertEquals(WifiBandFilter.Band.GHZ_2_4, WifiBandFilter.bandForFrequency(2412))
        assertEquals(WifiBandFilter.Band.GHZ_5, WifiBandFilter.bandForFrequency(5180))
        assertEquals(WifiBandFilter.Band.UNKNOWN, WifiBandFilter.bandForFrequency(0))
        assertEquals(WifiBandFilter.Band.OTHER, WifiBandFilter.bandForFrequency(5975))
    }

    @Test
    fun defaultsToOnlyTwoPointFourGhzAndShowsUnknownWithMixedBands() {
        assertTrue(WifiBandFilter.isVisible(WifiBandFilter.Band.GHZ_2_4, false))
        assertFalse(WifiBandFilter.isVisible(WifiBandFilter.Band.GHZ_5, false))
        assertFalse(WifiBandFilter.isVisible(WifiBandFilter.Band.UNKNOWN, false))
        assertTrue(WifiBandFilter.isVisible(WifiBandFilter.Band.GHZ_5, true))
        assertTrue(WifiBandFilter.isVisible(WifiBandFilter.Band.UNKNOWN, true))
        assertFalse(WifiBandFilter.isVisible(WifiBandFilter.Band.OTHER, true))
    }
}
