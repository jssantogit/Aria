package com.music.aria.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.aria.sharedui.resources.*
import com.music.aria.ui.components.PlayingAccent
import com.music.aria.ui.components.SHELF_CARD_WIDTH
import com.music.aria.ui.components.NativeMediaCardMetrics
import com.music.aria.ui.components.contextClick
import com.music.aria.ui.components.longPressMenuClickable
import com.music.aria.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The original Home/Explore shelf presentation, with no ShelfItem, browseId,
 * Song.videoId or service identity. Artwork is supplied by the source adapter,
 * allowing special local folders, canonical covers and future providers to
 * share one card instead of rebuilding visually similar cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeShelfCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    isPinned: Boolean = false,
    isCurrent: Boolean = false,
    artwork: @Composable () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    var heldActionOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) heldActionOpen = false
    }
    // A normal tap gets tactile darkening. Once the long press has selected
    // this card, the artwork must return to its untouched original pixels.
    val heldAction = onLongPress?.let { action ->
        { heldActionOpen = true; action() }
    }
    Column(
        modifier = modifier
            .longPressMenuClickable(
                onClick = onClick, onLongClick = heldAction,
                interactionSource = pressSource, suppressTapIndication = true,
            )
            .contextClick(heldAction),
    ) {
        // Full card remains clickable, but feedback paints only the clipped cover.
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(NativeMediaCardMetrics.squareCoverShape)) {
            artwork()
            if (nativeShelfPressInkVisible(pressed, heldActionOpen)) {
                Box(Modifier.matchParentSize()
                    .background(Color.Black.copy(alpha = NativeMediaCardMetrics.pressedArtworkAlpha)))
            }
        }
        Spacer(Modifier.height(NativeMediaCardMetrics.shelfCaptionGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = BitChordIcons.Pin,
                    contentDescription = stringResource(Res.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Holding is a selection, not a continuing pressed-artwork animation. */
internal fun nativeShelfPressInkVisible(pressed: Boolean, heldActionOpen: Boolean): Boolean =
    pressed && !heldActionOpen
