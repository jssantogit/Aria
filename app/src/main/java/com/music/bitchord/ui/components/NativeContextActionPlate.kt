package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * One neutral material language for the Library filter reference, selection,
 * ratings and held-action menus. Artwork may color the PAGE, never the text
 * plate of a contextual action card.
 */
internal fun nativeContextPlateColor(dark: Boolean): Color =
    nativeCompactPlateColor(isDark = dark, artworkElevation = null)

internal enum class NativeActionCardTreatment { FROSTED, SOLID, NEUTRAL_FROSTED }

/** A centered rating panel uses a neutral, blurred surface; never album colors. */
internal fun neutralRatingPlateUsesArtwork(treatment: NativeActionCardTreatment): Boolean =
    treatment == NativeActionCardTreatment.FROSTED

/** The rating modal is rounder; existing library/held cards stay unchanged. */
internal fun nativeContextPlateCornerRadiusDp(treatment: NativeActionCardTreatment): Int =
    if (treatment == NativeActionCardTreatment.NEUTRAL_FROSTED) 30 else 18

internal fun expandableActionCardUsesHaze(
    treatment: NativeActionCardTreatment,
    hazeAvailable: Boolean,
    reduceBlur: Boolean,
): Boolean = treatment == NativeActionCardTreatment.FROSTED && hazeAvailable && !reduceBlur

@OptIn(ExperimentalHazeMaterialsApi::class)
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
    val isRating = treatment == NativeActionCardTreatment.NEUTRAL_FROSTED
    val base = nativeContextPlateColor(dark)
    val palette = rememberArtworkPalette(artworkUrl)
    val useHaze = expandableActionCardUsesHaze(
        treatment = treatment, hazeAvailable = hazeState != null, reduceBlur = reduceBlur,
    )
    val useArtwork = neutralRatingPlateUsesArtwork(treatment) &&
        hazeState == null && artworkUrl != null && !reduceBlur
    val neutralFrosted = treatment == NativeActionCardTreatment.NEUTRAL_FROSTED && !reduceBlur
    Surface(
        modifier = modifier.shadow(12.dp, shape, clip = false),
        shape = shape,
        color = Color.Transparent,
        contentColor = if (dark) Color.White else Color.Black,
        border = BorderStroke(
            if (isRating) 0.8.dp else 0.5.dp,
            (if (dark) Color.White else Color.Black).copy(
                alpha = if (isRating) (if (dark) 0.30f else 0.18f)
                    else (if (dark) 0.13f else 0.08f),
            ),
        ),
    ) {
        Box(
            Modifier.clip(shape)
                // Never rely on an asynchronous Haze texture to make text legible.
                .background(base)
                .then(
                    if (useHaze) Modifier.optimizedHazeEffect(
                        state = requireNotNull(hazeState),
                        style = HazeMaterials.regular(base.copy(alpha = 0.58f)),
                    ) else Modifier,
                ),
        ) {
            if (neutralFrosted) {
                // Dialogs use a separate window and cannot sample the host Haze
                // layer. Blur a muted, neutral texture INSIDE the plate instead,
                // preserving the frosted look without reintroducing album hues.
                val neutralShades = if (dark) listOf(
                    Color(0xFF414149), Color(0xFF303037), Color(0xFF46454B),
                ) else listOf(
                    Color(0xFFF2F2F4), Color(0xFFE5E5E9), Color(0xFFF4F4F6),
                )
                Box(Modifier.matchParentSize()
                    .blur(24.dp)
                    .background(Brush.linearGradient(neutralShades)))
                Box(Modifier.matchParentSize().background(base.copy(alpha = 0.58f)))
            }
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
