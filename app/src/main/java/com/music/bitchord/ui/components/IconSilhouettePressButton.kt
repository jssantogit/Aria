package com.music.bitchord.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * Icon-only actions must light/contract their actual glyph pixels, not paint a
 * circular Material ripple over an unrelated play/next/search silhouette.
 * The hit box remains unchanged and is never scaled or tinted.
 */
internal fun iconSilhouettePressScale(pressed: Boolean, reducedMotion: Boolean): Float =
    GlobalMotionPolicy.pressedScale(
        pressed = pressed, reduced = reducedMotion, activeScale = 0.90f,
    )

/** Opacity is an immediate reduced-motion-safe response on the drawn glyph. */
internal fun iconSilhouettePressAlpha(pressed: Boolean): Float =
    if (pressed) 0.78f else 1f

@Composable
internal fun IconSilhouettePressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = iconSilhouettePressScale(pressed && enabled, reducedMotion),
        animationSpec = if (reducedMotion) snap() else spring(
            dampingRatio = 0.80f, stiffness = 510f,
        ),
        label = "iconSilhouettePressScale",
    )
    val glyphAlpha by animateFloatAsState(
        targetValue = iconSilhouettePressAlpha(pressed && enabled),
        animationSpec = if (reducedMotion) snap() else tween(90),
        label = "iconSilhouettePressAlpha",
    )
    Box(
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        // Only the composited icon (or its spinner) moves. No circle, rounded
        // highlight, or scale is ever applied to the transparent hit target.
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = glyphAlpha
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
