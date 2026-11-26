package com.music.aria.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import com.music.aria.ui.theme.ariaAccentColor
import com.music.aria.ui.theme.ariaOnAccentColor

/**
 * Shared Settings toggle palette, using Aria's accent and contrasting thumb.
 * Keep Material's semantics, gestures and accessibility.
 */
@Composable
fun appleSettingsSwitchColors(): SwitchColors {
    val checkedAccent = ariaAccentColor()
    return SwitchDefaults.colors(
        checkedTrackColor = checkedAccent,
        checkedBorderColor = checkedAccent,
        checkedThumbColor = ariaOnAccentColor(),
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
