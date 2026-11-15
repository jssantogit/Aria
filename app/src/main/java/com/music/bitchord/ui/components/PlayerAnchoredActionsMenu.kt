package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.ui.icons.PlaylistEditorIcons

/**
 * Draw the visible selected ⋯ over the exact launcher, even though the menu
 * owns a separate dialog window. Bounds and origin are window pixels.
 */
internal fun playerAnchoredMenuHighlightPosition(
    anchor: IntRect,
    overlayOrigin: IntOffset,
    overlaySize: IntSize,
    highlightSizePx: Int = 44,
): IntOffset {
    val diameter = highlightSizePx.coerceAtLeast(1)
    val x = ((anchor.left + anchor.right) / 2 - overlayOrigin.x - diameter / 2)
        .coerceIn(0, (overlaySize.width - diameter).coerceAtLeast(0))
    val y = ((anchor.top + anchor.bottom) / 2 - overlayOrigin.y - diameter / 2)
        .coerceIn(0, (overlaySize.height - diameter).coerceAtLeast(0))
    return IntOffset(x, y)
}

/**
 * Player actions are not a bottom sheet. The player occupies a separate M3
 * dialog window, so an ordinary Popup from the activity may land behind it.
 * A fresh, full-width dialog stays above Now Playing and aligns its native
 * plate to the actual overflow button bounds captured in that player window.
 *
 * Both layers use the existing artwork palette. The plate's background
 * contains the same wide, artwork-derived blur used in the old player sheet;
 * the player itself is separately blurred by its host while this dialog is up.
 */
@Composable
fun PlayerAnchoredActionsMenu(
    anchorBounds: IntRect?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    highlightLauncher: Boolean = true,
    maxWidth: Dp = 340.dp,
    content: @Composable () -> Unit,
) {
    val palette = rememberArtworkPalette(imageUrl = artworkUrl)
    val plate = nativeCompactPlateColor(
        MaterialTheme.colorScheme.background.luminance() < 0.5f,
        artworkElevation = null,
    )
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var plateSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val margin = with(density) { 14.dp.roundToPx() }
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    val entrance = remember { Animatable(0f) }
    // Respect the dialog's actual width, which can be much smaller than the
    // activity width in split-screen. A fixed 248dp minimum clipped menus.
    val availableWidth = if (rootSize.width > 0) with(density) {
        (rootSize.width - 2 * margin).coerceAtLeast(1).toDp()
    } else maxWidth
    val menuMaxWidth = minOf(maxWidth, availableWidth)
    // The Now Playing actions expand downward from the original launcher.
    // Bound their scrolling plate to space under it instead of flipping above.
    val maxPlateHeight = if (highlightLauncher && anchorBounds != null && rootSize.height > 0) {
        with(density) {
            (rootSize.height - (anchorBounds.bottom - rootOrigin.y + gap) - margin)
                .coerceAtLeast(1).toDp().coerceAtMost(570.dp)
        }
    } else 570.dp
    val measured = rootSize.width > 0 && rootSize.height > 0 &&
        plateSize.width > 0 && plateSize.height > 0
    LaunchedEffect(measured, reduceMotion) {
        if (!measured) return@LaunchedEffect
        if (reduceMotion) entrance.snapTo(1f)
        else entrance.animateTo(1f, tween(durationMillis = 170))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    rootOrigin = IntOffset(pos.x.toInt(), pos.y.toInt())
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.matchParentSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss))
            val anchor = anchorBounds ?: IntRect(
                rootSize.width - margin - with(density) { 44.dp.roundToPx() },
                rootOrigin.y + margin + with(density) { 72.dp.roundToPx() },
                rootSize.width - margin,
                rootOrigin.y + margin + with(density) { 116.dp.roundToPx() },
            )
            val place = Modifier.align(Alignment.TopStart)
                .offset {
                    anchoredFrostedMenuPosition(
                        anchor = anchor,
                        popup = plateSize,
                        overlay = rootSize,
                        overlayOrigin = rootOrigin,
                        gap = gap,
                        margin = margin,
                        preferBelow = highlightLauncher,
                    )
                }
            Surface(
                modifier = place
                    .widthIn(min = minOf(248.dp, menuMaxWidth), max = menuMaxWidth)
                    .heightIn(max = maxPlateHeight)
                    .onSizeChanged { plateSize = it }
                    // No translation or spring tied to popup measurement: the
                    // dialog gets its own coordinate system and the old motion
                    // visibly jumped when the plate acquired its real size.
                    .graphicsLayer {
                        alpha = if (measured) entrance.value else 0f
                        scaleX = 0.975f + 0.025f * entrance.value
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(1f, 0f)
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .testTag("player-anchored-actions-menu")
                    // Absorb taps on blank plate space without marking the
                    // entire editor clickable. A parent clickable merges the
                    // child's PaneTitle semantics and crashes Compose UI tests
                    // (and can break TalkBack). Child actions keep priority.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                color = plate,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 22.dp,
            ) {
                Box {
                    ArtworkBackdrop(
                        palette = palette,
                        imageUrl = artworkUrl,
                        modifier = Modifier.matchParentSize(),
                        washFraction = 0.78f,
                    )
                    // Diffused sleeve remains subtle, but the Library's
                    // neutral filter tint owns the player menu's text surface.
                    Box(Modifier.background(plate.copy(alpha = 0.88f))) {
                        content()
                    }
                }
            }

            // The activity's original puck is behind this dialog and blurred
            // with the player. A selected copy in the SAME screen-space bounds
            // makes the expanding menu visibly originate at its ⋯ launcher.
            if (highlightLauncher && anchorBounds != null) {
                val highlightSize = 44.dp
                val highlightPx = with(density) { highlightSize.roundToPx() }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            playerAnchoredMenuHighlightPosition(
                                anchor = anchorBounds,
                                overlayOrigin = rootOrigin,
                                overlaySize = rootSize,
                                highlightSizePx = highlightPx,
                            )
                        }
                        .size(highlightSize)
                        .testTag("player-overflow-highlight")
                        .clickable(onClick = onDismiss),
                    color = plate,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    shadowElevation = 8.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = PlaylistEditorIcons.More,
                            contentDescription = "Fechar opções do player",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
