package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.music.bitchord.ui.theme.rememberArtworkPalette
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The small lifted plate role shared by selection and rating editors.
 * This borrows the contextual menu's rounded silhouette and high-contrast
 * opaque surface; it does not add a second full-screen overlay when selecting
 * multiple rows, because the user must keep interacting with those rows.
 */
internal fun nativeContextPlateColor(dark: Boolean): Color =
    if (dark) Color(0xFF242427) else Color(0xFFF4F4F6)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun NativeContextActionPlate(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    /** Same album/track sleeve already visible behind a standalone rating dialog. */
    artworkUrl: String? = null,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val artworkPalette = rememberArtworkPalette(artworkUrl)
    val shape = RoundedCornerShape(18.dp)
    val base = nativeContextPlateColor(dark)
    Surface(
        modifier = modifier.shadow(12.dp, shape, clip = false),
        shape = shape,
        color = Color.Transparent,
        contentColor = if (dark) Color.White else Color.Black,
        border = BorderStroke(
            0.5.dp,
            (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.13f else 0.08f),
        ),
    ) {
        Box(
            Modifier.clip(shape).then(
                if (hazeState != null && !reduceBlur) {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(base.copy(alpha = 0.56f)),
                    )
                } else if (artworkUrl != null && !reduceBlur) {
                    // A Dialog cannot sample the activity's Haze layer across
                    // window boundaries. Use the same real blurred artwork as
                    // the player instead of an opaque charcoal placeholder.
                    Modifier
                } else Modifier.background(base)
            ),
        ) {
            if (hazeState == null && artworkUrl != null && !reduceBlur) {
                ArtworkBackdrop(
                    palette = artworkPalette,
                    imageUrl = artworkUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 1f,
                )
            }
            content()
        }
    }
}
