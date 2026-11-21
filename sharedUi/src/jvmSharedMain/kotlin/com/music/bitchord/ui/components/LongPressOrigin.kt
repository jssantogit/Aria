package com.music.bitchord.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * Provenance of a held item: its unchanged position in the real composition.
 *
 * No bitmap copy or relocated item. A temporary transparent display list
 * of the source's ACTUAL pixels serves as an alpha stencil for the scrim;
 * the original card/artwork/text stay in their native composition and z-order.
 */
@Stable
class HeldItem internal constructor(
    val bounds: Rect,
    /**
     * Display list of the actual held composable, without the page's opaque
     * background. Kept only during the menu to erase dimming over PAINTED
     * pixels, not the transparent area of a wide row.
     */
    val paintLayer: GraphicsLayer? = null,
    private val onRelease: () -> Unit = {},
) {
    /** Release the temporary recording when the overlay's exit completes. */
    fun releaseLift() = onRelease()
}

/**
 * The most recently held native row/card. The caller consumes this synchronously
 * to distinguish a long press from the ordinary three-dot bottom-sheet action.
 */
object LongPressOrigin {
    private var held: HeldItem? = null
    private var recordedAt = 0L

    internal fun record(item: HeldItem) {
        // A previous hold that no menu claimed must stop recording its source.
        held?.takeIf { it !== item }?.releaseLift()
        held = item
        recordedAt = System.nanoTime()
    }

    fun consume(): HeldItem? {
        val pending = held
        held = null
        val claimed = pending?.takeIf { System.nanoTime() - recordedAt < FRESH_NANOS }
        if (claimed == null) pending?.releaseLift()
        return claimed
    }

    /** A sheet-style or refused action has no lifted menu to own the layer. */
    internal fun discardUnconsumed(item: HeldItem) {
        if (held === item) {
            held = null
            item.releaseLift()
        }
    }

    private const val FRESH_NANOS = 500_000_000L
}

/**
 * Long-press registration that preserves the real item without a screenshot.
 * Recording its native pixels in a temporary GraphicsLayer only while held
 * allows the scrim to leave text and artwork untouched, without lightening
 * the empty background of a transparent row or changing its geometry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressMenuClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    suppressTapIndication: Boolean = false,
): Modifier {
    val pressSource = interactionSource ?: remember { MutableInteractionSource() }
    val capture = remember { HoldOriginCoordinates() }
    // Normal tap-only rows never allocate a display list. Eligible holds
    // create a layer, but its content is only recorded while their menu is up.
    // It preserves alpha and is NOT a screenshot of the entire page.
    val paintedItem = if (onLongClick != null) rememberGraphicsLayer() else null
    val recording = remember { mutableStateOf(false) }
    val longClick = onLongClick?.let { held ->
        longClick@{
            val coordinates = capture.coordinates?.takeIf { it.isAttached }
                ?: return@longClick
            val bounds = coordinates.boundsInWindow()
            if (!heldOriginFullyVisible(bounds, coordinates.size.width, coordinates.size.height)) {
                return@longClick
            }
            recording.value = paintedItem != null
            val origin = HeldItem(bounds, paintedItem) { recording.value = false }
            LongPressOrigin.record(origin)
            try {
                held()
            } finally {
                // All lifted-menu hosts consume synchronously in the callback.
                // An ordinary sheet or ignored action must not keep drawing
                // into a temporary layer until another gesture happens.
                LongPressOrigin.discardUnconsumed(origin)
            }
        }
    }
    // No pressed-ripple bitmap should ever obscure artwork or captions.
    val gesture = when {
        onClick != null && longClick != null -> Modifier.combinedClickable(
            interactionSource = pressSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = longClick,
        )
        onClick != null && suppressTapIndication -> Modifier.combinedClickable(
            interactionSource = pressSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = null,
        )
        onClick != null -> Modifier.combinedClickable(
            enabled = enabled, onClick = onClick, onLongClick = null,
        )
        longClick != null -> Modifier
            .pointerInput(enabled, longClick) {
                if (enabled) detectTapGestures(onLongPress = { longClick() })
            }
            .semantics {
                if (enabled) onLongClick(label = "Abrir ações") {
                    longClick()
                    true
                }
            }
        else -> Modifier
    }
    return this
        .onPlaced { capture.coordinates = it }
        .drawWithContent {
            val layer = paintedItem
            if (recording.value && layer != null) {
                // Record the row's OWN content/alpha, not the opaque window.
                // The exact same commands still paint in the original place.
                layer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            } else {
                drawContent()
            }
        }
        .then(gesture)
}

private class HoldOriginCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Ignore clipped or detached carousel entries. The spotlight should never
 * show an incomplete slice of a cover as though it were the whole selected item.
 */
internal fun heldOriginFullyVisible(
    bounds: Rect,
    sourceWidth: Int,
    sourceHeight: Int,
): Boolean = sourceWidth > 0 && sourceHeight > 0 &&
    bounds.width > 0f && bounds.height > 0f &&
    kotlin.math.abs(bounds.width - sourceWidth) <= 1f &&
    kotlin.math.abs(bounds.height - sourceHeight) <= 1f
