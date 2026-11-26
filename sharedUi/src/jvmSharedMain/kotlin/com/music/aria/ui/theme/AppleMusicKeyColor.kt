package com.music.aria.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Aria Notation 2.1 accents, sampled from the approved logo gradient.
 * Burnt amber for light surfaces; luminous amber for dark surfaces.
 * Neutral text, destructive red and data-specific colors are independent.
 */
val AriaLightAccent = Color(0xFFB74D0A)
val AriaDarkAccent = Color(0xFFFFB747)

fun ariaAccentColor(dark: Boolean): Color =
    if (dark) AriaDarkAccent else AriaLightAccent

fun ariaOnAccentColor(dark: Boolean): Color =
    if (dark) Color.Black else Color.White

@Composable
fun ariaAccentColor(): Color =
    ariaAccentColor(dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

@Composable
fun ariaOnAccentColor(): Color =
    ariaOnAccentColor(dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

// Compatibility names for existing Apple-inspired UI call sites. These are
// Aria branding roles, not Apple palette constants; there is one source of truth.
val AppleMusicLightKey = AriaLightAccent
val AppleMusicDarkKey = AriaDarkAccent

fun appleMusicKeyColor(dark: Boolean): Color = ariaAccentColor(dark)

@Composable
fun appleMusicKeyColor(): Color = ariaAccentColor()

fun appleSelectionAccentColor(selected: Boolean, dark: Boolean, neutral: Color): Color =
    if (selected) ariaAccentColor(dark) else neutral

@Composable
fun appleSelectionAccentColor(selected: Boolean, neutral: Color): Color =
    appleSelectionAccentColor(
        selected = selected,
        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        neutral = neutral,
    )
