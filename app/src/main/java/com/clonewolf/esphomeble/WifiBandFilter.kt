package com.clonewolf.esphomeble

internal object WifiBandFilter {
    enum class Band { GHZ_2_4, GHZ_5, UNKNOWN, OTHER }

    fun bandForFrequency(frequencyMHz: Int): Band = when (frequencyMHz) {
        in 2400..2500 -> Band.GHZ_2_4
        in 5000..5924 -> Band.GHZ_5
        0 -> Band.UNKNOWN
        else -> Band.OTHER
    }

    fun isVisible(band: Band, showFiveGhz: Boolean): Boolean =
        band == Band.GHZ_2_4 ||
            (showFiveGhz && (band == Band.GHZ_5 || band == Band.UNKNOWN))
}
