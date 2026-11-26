package com.music.aria.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.settings.AppSettings
import com.music.aria.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState

/**
 * One neutral material language for the Library filter reference, selection,
 * ratings and held-action menus. The rating Dialog may sample a dedicated
 * player Haze source; without one, it retains the exact neutral solid fallback.
 * Artwork may color the PAGE, never contextual action plates.
 */
internal fun nativeContextPlateColor(dark: Boolean): Color =
    nativeCompactPlateColor(isDark = dark, artworkElevation = null)

/** Explicit player source wins; other dialogs inherit the active page capture. */
internal fun nativeContextPlateHazeSource(
    explicit: HazeState?, inherited: HazeState?,
): HazeState? = explicit ?: inherited

internal enum class NativeActionCardTreatment { FROSTED, SOLID, NEUTRAL_FROSTED, PLAYER_NEUTRAL_FROSTED }

/** The centered rating modal uses the exact neutral popup surface, without artwork. */
internal fun neutralRatingPlateUsesArtwork(treatment: NativeActionCardTreatment): Boolean =
    treatment == NativeActionCardTreatment.FROSTED

/** The rating modal is rounder; existing library/held cards stay unchanged. */
internal fun nativeContextPlateCornerRadiusDp(treatment: NativeActionCardTreatment): Int =
    if (treatment == NativeActionCardTreatment.NEUTRAL_FROSTED) 30 else 18

/** Rating and player overflow have no outline; preserve legacy borders elsewhere. */
internal fun nativeContextPlateBorder(
    treatment: NativeActionCardTreatment,
    dark: Boolean,
): BorderStroke? {
    if (treatment == NativeActionCardTreatment.NEUTRAL_FROSTED ||
        treatment == NativeActionCardTreatment.PLAYER_NEUTRAL_FROSTED) return null
    return BorderStroke(
        0.5.dp,
        (if (dark) Color.White else Color.Black).copy(
            alpha = if (dark) 0.13f else 0.08f,
        ),
    )
}

internal fun expandableActionCardUsesHaze(
    treatment: NativeActionCardTreatment,
    hazeAvailable: Boolean,
    reduceBlur: Boolean,
): Boolean = (treatment == NativeActionCardTreatment.FROSTED ||
    treatment == NativeActionCardTreatment.NEUTRAL_FROSTED ||
    treatment == NativeActionCardTreatment.PLAYER_NEUTRAL_FROSTED) && hazeAvailable && !reduceBlur

@Composable
internal fun NativeContextActionPlate(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    /** Already verified album art in a standalone rating Dialog. */
    artworkUrl: String? = null,
    treatment: NativeActionCardTreatment = NativeActionCardTreatment.FROSTED,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(nativeContextPlateCornerRadiusDp(treatment).dp)
    val base = nativeContextPlateColor(dark)
    val palette = rememberArtworkPalette(artworkUrl)
    // Standalone rating and entity dialogs inherit the page's real Haze source
    // rather than silently switching to a solid plate when the caller omits it.
    val effectiveHaze = nativeContextPlateHazeSource(
        hazeState, LocalNeutralOverlayHazeState.current,
    )
    val sampledHaze = if (expandableActionCardUsesHaze(
        treatment = treatment, hazeAvailable = effectiveHaze != null, reduceBlur = reduceBlur,
    )) effectiveHaze else null
    val useArtwork = neutralRatingPlateUsesArtwork(treatment) &&
        effectiveHaze == null && artworkUrl != null && !reduceBlur
    Surface(
        modifier = modifier.shadow(12.dp, shape, clip = false),
        shape = shape,
        color = Color.Transparent,
        contentColor = if (dark) Color.White else Color.Black,
        border = nativeContextPlateBorder(treatment, dark),
    ) {
        Box(
            Modifier.neutralFrostedMaterial(
                shape = shape,
                neutralColor = base,
                hazeState = sampledHaze,
                reduceBlur = reduceBlur,
            ),
        ) {
            if (useArtwork) {
                ArtworkBackdrop(
                    palette = palette,
                    imageUrl = requireNotNull(artworkUrl),
                    modifier = Modifier.matchParentSize(),
                    washFraction = 1f,
                )
                // The underlying art is still diffused, but never vibrant
                // enough to make white actions visually disappear.
                Box(Modifier.matchParentSize().background(base.copy(alpha = 0.78f)))
            }
            content()
        }
    }
}
