package com.music.bitchord.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.catalog.metadata.AppleChartTerritory

/** Only a country chart navigation key. No fake album/recording identity. */
internal class CanonicalCountryChartNavigation {
    var active by mutableStateOf<AppleChartTerritory?>(null)
        private set

    fun open(territory: AppleChartTerritory) {
        active = territory
    }

    fun close() {
        active = null
    }
}
