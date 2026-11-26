package com.music.aria.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import com.music.aria.ui.theme.ariaAccentColor

/**
 * Settings continuous controls use the Aria amber accent role.
 * Slider geometry remains Compose's accessible primitive: AM-DECOMP has
 * not verified slider track/thumb dimensions for these BitChord settings.
 */
@Composable
fun appleSettingsSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = ariaAccentColor(),
    activeTrackColor = ariaAccentColor(),
    inactiveTrackColor = MaterialTheme.colorScheme.outline,
)
