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


// Keep these navigation effects out of the bytecode-limited BitChordApp root.
@androidx.compose.runtime.Composable
internal fun rememberCountryChartNavigation(
    authorized: Boolean,
    homeSelected: Boolean,
): CanonicalCountryChartNavigation {
    val navigation = androidx.compose.runtime.remember { CanonicalCountryChartNavigation() }
    androidx.compose.runtime.LaunchedEffect(authorized, homeSelected) {
        if (!authorized || !homeSelected) navigation.close()
    }
    return navigation
}
