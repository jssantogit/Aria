package com.music.bitchord.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * Keeps Haze's visual style while allowing it to reduce its sampling resolution.
 * Haze 1.x otherwise processes every effect at full resolution, even when a
 * large blur makes those extra source pixels invisible.
 *
 * A third rather than [HazeInputScale.Auto]. Auto only steps the resolution down
 * once the blur radius is large enough for it to be confident, which left the
 * mini player and the nav bar sampling at full resolution on every frame of
 * every scroll. A third is what the liquid glass surfaces already sample at, and
 * what [com.music.bitchord.ui.components.TopFadeBlur] was moved to for the same
 * reason: the blur is what hides the upscale, so the pixels that were being paid
 * for could not be seen either way. [block] can still override it per call site.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.optimizedHazeEffect(
    state: HazeState,
    style: HazeStyle = HazeStyle.Unspecified,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier = hazeEffect(state, style) {
    inputScale = HazeInputScale.Fixed(0.33f)
    block?.invoke(this)
}

/**
 * Shared neutral frosted material, owned by the *card*, never the page scrim.
 * Source-backed Haze samples the real page even for separate dialog/popup
 * windows when their owner supplies the same HazeState. The opaque neutral
 * base remains the accessibility/failure fallback, never a fabricated blur.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
fun Modifier.neutralFrostedMaterial(
    shape: Shape,
    neutralColor: Color,
    hazeState: HazeState?,
    reduceBlur: Boolean,
): Modifier = this
    .clip(shape)
    .background(neutralColor)
    .then(
        if (nativeNeutralOverlayCanBlur(hazeState != null, reduceBlur))
            Modifier.optimizedHazeEffect(
                state = requireNotNull(hazeState),
                style = HazeMaterials.regular(neutralColor.copy(alpha = 0.58f)),
            )
        else Modifier,
    )
