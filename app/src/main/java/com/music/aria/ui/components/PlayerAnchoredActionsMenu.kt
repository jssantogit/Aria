package com.music.aria.ui.components

import androidx.compose.foundation.border
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
import androidx.compose.material3.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.music.aria.ui.components.CompactRatingIndicator
import com.music.aria.ui.components.CompactRatingGeometry
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState

/**
 * The player button is still painted in the underlying Now Playing dialog.
 * Exclude its real circle from this dialog's scrim, rather than rendering a
 * second glyph/puck at guessed offsets (which creates double dots on devices
 * with different window insets or magnification).
 *
 * Both the launcher and the overlay are measured in SCREEN pixels; their
 * Compose Dialogs have independent window coordinate origins. Do not mix
 * boundsInWindow from the player with positionInWindow from the menu.
 */
internal fun playerOverflowScrimCutout(
    anchor: IntRect?,
    overlayOrigin: IntOffset,
    overlaySize: IntSize,
): androidx.compose.ui.geometry.Rect? {
    if (anchor == null || overlaySize.width <= 0 || overlaySize.height <= 0 ||
        anchor.width <= 0 || anchor.height <= 0) return null
    val left = (anchor.left - overlayOrigin.x).toFloat()
    val top = (anchor.top - overlayOrigin.y).toFloat()
    val right = (anchor.right - overlayOrigin.x).toFloat()
    val bottom = (anchor.bottom - overlayOrigin.y).toFloat()
    if (left >= overlaySize.width || top >= overlaySize.height ||
        right <= 0f || bottom <= 0f) return null
    return androidx.compose.ui.geometry.Rect(left, top, right, bottom)
}

/**
 * The player controls occupy the space directly behind the menu. Fading the
 * whole plate (including text) from 0 to 1 reveals those controls THROUGH its
 * rows, creating a transient double-exposure. Wait for measurement, then draw
 * an opaque plate and animate only its subtle anchored scale.
 */
internal fun playerAnchoredMenuPlateAlpha(measured: Boolean): Float =
    if (measured) 1f else 0f

internal fun playerAnchoredMenuPlateScale(progress: Float): Float =
    0.975f + 0.025f * progress.coerceIn(0f, 1f)

/** Both player overflow and rating use the shared contextual action plate. */
@Suppress("UNUSED_PARAMETER")
internal fun playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher: Boolean): Boolean = true

/**
 * Draw the visible selected ⋯ over the exact launcher, even though the menu
 * owns a separate dialog window. Bounds and origin are window pixels.
 */
internal fun playerAnchoredMenuHighlightPosition(
    anchor: IntRect,
    overlayOrigin: IntOffset,
    overlaySize: IntSize,
    highlightSizePx: Int = 40,
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
 * The panel reuses the borderless neutral contextual action plate with the
 * player's dedicated cross-window Haze source. When blur is reduced or the
 * source is absent, the same readable neutral solid color remains.
 * The panel never fades its content during entrance scaling.
 */
@Composable
fun PlayerAnchoredActionsMenu(
    anchorBounds: IntRect?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null,
    highlightLauncher: Boolean = true,
    highlightRatingLauncher: Boolean = false,
    ratingLauncherScore: Double? = null,
    maxWidth: Dp = 340.dp,
    content: @Composable () -> Unit,
) {
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var plateSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val margin = with(density) { 14.dp.roundToPx() }
    val reduceMotion = com.music.aria.ui.LocalReduceAnimations.current
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

    NeutralOverlayDialog(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // A second platform dialog must NOT dim the entire player behind its
        // own Compose scrim. Android's FLAG_DIM_BEHIND would darken even the
        // transparent spotlight cutout, so the selected original icon could
        // never retain its normal colors. Only the masked Compose layer dims.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            // HazeDialog may add a wrapper around the Compose view. Walk
            // ancestors rather than assuming the first parent is the Dialog.
            var ancestor: android.view.ViewParent? = dialogView.parent
            while (ancestor != null && ancestor !is DialogWindowProvider) {
                ancestor = ancestor.parent
            }
            val window = (ancestor as? DialogWindowProvider)?.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { }
        }
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionOnScreen()
                    rootOrigin = IntOffset(pos.x.toInt(), pos.y.toInt())
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                Modifier.matchParentSize()
                    // Punch the scrim out around the actual 34dp visible puck.
                    // The original glyph and puck retain their exact tint,
                    // geometry, alpha and position; never draw a second copy.
                    .drawBehind {
                        val spotlight = if (highlightLauncher) {
                            playerOverflowScrimCutout(
                                anchor = anchorBounds,
                                overlayOrigin = rootOrigin,
                                overlaySize = rootSize,
                            )
                        } else null
                        if (spotlight == null) {
                            drawRect(Color.Black.copy(alpha = 0.28f))
                        } else {
                            val outside = androidx.compose.ui.graphics.Path().apply {
                                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                                addRect(androidx.compose.ui.geometry.Rect(
                                    androidx.compose.ui.geometry.Offset.Zero, size,
                                ))
                                addOval(spotlight)
                            }
                            drawPath(outside, Color.Black.copy(alpha = 0.28f))
                        }
                    }
                    .clickable(onClick = onDismiss),
            )
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
            val plateModifier = place
                    .widthIn(min = minOf(248.dp, menuMaxWidth), max = menuMaxWidth)
                    .heightIn(max = maxPlateHeight)
                    .onSizeChanged { plateSize = it }
                    // No translation or spring tied to popup measurement: the
                    // dialog gets its own coordinate system and the old motion
                    // visibly jumped when the plate acquired its real size.
                    .graphicsLayer {
                        // The card is fully opaque whenever visible. Only its
                        // small scale transition animates; no player glyphs,
                        // seek bars or lyrics may show through action rows.
                        alpha = playerAnchoredMenuPlateAlpha(measured)
                        scaleX = playerAnchoredMenuPlateScale(entrance.value)
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(1f, 0f)
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .testTag("player-anchored-actions-menu")
                    // Absorb taps on blank plate space without marking the
                    // entire editor clickable. A parent clickable merges the
                    // child's PaneTitle semantics and crashes Compose UI tests
                    // (and can break TalkBack). Child actions keep priority.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            // Match the neutral frosted rating/Library material without the
            // player-only hairline. The real Haze source is captured behind
            // this separate dialog by Now Playing while the menu is open.
            NativeContextActionPlate(
                modifier = plateModifier,
                hazeState = hazeState,
                treatment = NativeActionCardTreatment.PLAYER_NEUTRAL_FROSTED,
            ) {
                content()
            }

            // The native rating star lives behind this separate dialog window.
            // Repaint its exact circular launcher above the scrim while the
            // editor is open, just as the player's overflow menu does for ⋯.
            if (highlightRatingLauncher && !highlightLauncher && anchorBounds != null) {
                val highlightSize = 40.dp
                val highlightPx = with(density) { highlightSize.roundToPx() }
                Box(
                    modifier = Modifier.align(Alignment.TopStart)
                        .offset {
                            playerAnchoredMenuHighlightPosition(
                                anchor = anchorBounds,
                                overlayOrigin = rootOrigin,
                                overlaySize = rootSize,
                                highlightSizePx = highlightPx,
                            )
                        }
                        .size(highlightSize)
                        .border(0.75.dp, heldContextSelectionOutline(), CircleShape)
                        .testTag("player-rating-launcher-highlight")
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactRatingIndicator(
                        value = ratingLauncherScore,
                        geometry = CompactRatingGeometry.CIRCULAR,
                        size = 34.dp,
                        showUnrated = true,
                        neutralColor = Color.White,
                    )
                }
            }

        }
    }
}
