package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Root page title glyphs share the same screen Y as the top-floating
 * Home/Library/Explore actions. The Android host passes a safe content inset
 * that already clears those circles; the root title intentionally rises
 * 52dp into their band. Each screen subtracts its own Text's inner top padding
 * (Home 8dp, Library 4dp, Explore 8dp) exactly once.
 *
 * No device-specific status-bar guess and no independent landing-page lifts.
 */
internal fun rootLandingTitleListTopInset(
    safeContentTop: Dp,
    titleInnerTop: Dp,
): Dp = (safeContentTop - 52.dp - titleInnerTop).coerceAtLeast(0.dp)
