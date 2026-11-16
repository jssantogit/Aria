package com.music.bitchord.ui.components

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/** The source row keeps painting until its bitmap replacement is ready. */
internal fun heldPreviewCanPaint(hasImage: Boolean, lifted: Boolean): Boolean = hasImage && lifted

/**
 * The real row is suppressed for as long as the snapshot is lifted. Fading
 * this snapshot with the menu's progress creates a blank-tinted frame on
 * return: both source and snapshot are partly/fully invisible at once.
 * Its position and scale still animate; only the swap of PAINT is atomic.
 */
internal fun heldPreviewAlpha(hasImage: Boolean, lifted: Boolean): Float =
    if (heldPreviewCanPaint(hasImage, lifted)) 1f else 0f

/** The active lifted item keeps the same bright outline over every artwork palette. */
internal fun heldContextSelectionOutline(): Color = Color.White.copy(alpha = 0.36f)

/** The original snapshot is the entire content. Never repaint it with a grey tile. */
internal fun heldPreviewBackingColor(): Color = Color.Transparent

/** Reuse the Library filter's frosted action surface; keep a solid fallback for reduced blur. */
internal fun heldActionsUseHaze(reduceDynamicBlur: Boolean): Boolean = !reduceDynamicBlur

/**
 * The menu a *held* item lifts into, in the shape Apple Music uses: the item
 * itself pops out of its list, the page behind it blurs, and the actions hang
 * off it.
 *
 * What lifts is the item exactly as it was drawn — see [HeldItem] — so a
 * grid tile comes up as that tile and a search row as that row. It sits on a
 * frosted plate so it reads as picked up rather than pasted over the blur.
 *
 * Only a hold opens this. The ⋮ keeps opening the bottom sheet — a tap on a
 * small button is a request for the full menu, while a hold on the item itself
 * is a gesture about *that item*, answered where the finger is.
 *
 * [item] and [held] come and go together: non-null while the menu is wanted,
 * null once anything has closed it. The last pair is kept here so the menu can
 * play its way back into the list after the caller has already let go of it.
 *
 * Both of the app's accessibility switches are honoured: "Reduce animation"
 * (or the system's animator scale at zero) turns the lift into a short fade
 * with nothing moving, and "Reduce dynamic blur" swaps the blurred page for a
 * plain dimming scrim, which is also the cost that setting exists to remove.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T : Any> HeldContextMenu(
    item: T?,
    held: HeldItem?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /** Tapping the lifted item — open what it stands for. Null leaves it inert. */
    onPreviewClick: ((T) -> Unit)?,
    /** Actual app mini-player + tab chrome; never place actions behind them. */
    bottomChromeClearance: Dp = 100.dp,
    /** Parent already blurs its REAL composed page, not a separate Haze plate. */
    hostProvidesBlur: Boolean = false,
    /** The page's already contrast-corrected artwork elevation, when present. */
    plateTint: Color? = null,
    actions: @Composable (T) -> Unit,
) {
    val requested = if (item != null && held != null) item to held else null
    var last by remember { mutableStateOf<Pair<T, HeldItem>?>(null) }
    if (requested != null && requested != last) {
        SideEffect { last = requested }
    }
    val shown = requested ?: last ?: return
    val (menuItem, heldItem) = shown
    val open = requested != null
    // Also restore the originating row if navigation tears down this overlay
    // while its reverse animation has not completed.
    DisposableEffect(heldItem) {
        onDispose { heldItem.releaseLift() }
    }

    val context = LocalContext.current
    val appReduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = appReduceAnimation || systemAnimationsOff
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            progress.animateTo(
                1f,
                if (reduceMotion) {
                    tween(durationMillis = 140, easing = LinearEasing)
                } else {
                    // Under-damped on purpose: the small overshoot is the "pop"
                    // of the item coming off the page.
                    spring(dampingRatio = 0.72f, stiffness = 420f)
                },
            )
        } else {
            progress.animateTo(
                0f,
                tween(durationMillis = if (reduceMotion) 110 else 230, easing = FastOutSlowInEasing),
            )
            heldItem.releaseLift()
            last = null
        }
    }

    BackHandler(enabled = open, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // The copied card and its actions must NEVER show the old row underneath.
    // A translucent 0.50 plate was duplicating track text and album art across
    // the actual row and its lifted preview. Match the solid catalog source menu.
    val plate = heldContextPlateColor(MaterialTheme.colorScheme.background, plateTint)
    val hairline = if (plate.luminance() < 0.5f)
        Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    // Do not put an opaque black plate above the already blurred content.
    // A gentle translucent lift keeps the real page legible behind the item
    // (and exposes the diffused artwork when one exists).
    val scrimColor = if (dark) Color.Black.copy(alpha = 0.30f)
        else Color.Black.copy(alpha = 0.18f)
    val cardShape = RoundedCornerShape(20.dp)
    val menuShape = RoundedCornerShape(16.dp)
    val platePadding = 2.dp

    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            // Once it is on its way out nothing on it answers any more — a
            // second tap on a row mid-exit would run its action twice.
            .then(if (open) Modifier else Modifier.pointerInput(Unit) { swallowAll() }),
        content = {
            // The page, pushed back. One alpha fades the whole of it rather
            // than a blur radius being animated per frame.
            val backdropAlpha = { progress.value.coerceIn(0f, 1f) }
            Spacer(
                Modifier
                    .fillMaxSize()
                    .testTag("held-context-backdrop")
                    // No full-page Haze re-sampling during the lifted animation.
                    .graphicsLayer { alpha = backdropAlpha() }
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onDismiss,
                    ),
            )
            // The held item, on its plate. Sized by the layout below to the
            // item's own bounds plus the plate's margin.
            Box(
                Modifier
                    .then(if (reduceMotion) Modifier else Modifier.shadow(20.dp, cardShape, clip = false))
                    .pointerInput(Unit) {}
                    .clip(cardShape)
                    // Lift the exact original bitmap; a neutral background here
                    // visibly repaints bright/dark album and artist tiles grey.
                    .background(heldPreviewBackingColor())
                    .border(0.75.dp, heldContextSelectionOutline(), cardShape)
                    .then(
                        if (onPreviewClick != null) {
                            Modifier.clickable(enabled = open) { onPreviewClick(menuItem) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(platePadding)
                    .testTag("held-context-preview"),
            ) {
                heldItem.image?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                Modifier
                    // Taps between rows land on the menu, not the scrim under it.
                    .pointerInput(Unit) {}
                    .clip(menuShape)
                    // Same glass recipe as NativeContextActionPlate (Library filters).
                    // The neutral paint is a fallback until the Haze source is ready;
                    // blur stays opt-out for accessibility and low-power devices.
                    .background(plate)
                    .then(
                        if (heldActionsUseHaze(reduceDynamicBlur)) Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(plate.copy(alpha = 0.58f)),
                        ) else Modifier,
                    )
                    .border(0.5.dp, hairline, menuShape)
                    .verticalScroll(rememberScrollState())
                    .testTag("held-context-actions"),
            ) {
                actions(menuItem)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val pad = platePadding.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = heldMenuVisibleBottom(
            viewportHeight = height,
            systemBottomInset = insets.getBottom(this) + 8.dp.roundToPx(),
            chromeReserve = bottomChromeClearance.roundToPx(),
        ).coerceAtLeast(top)

        val backdrop = measurables[0].measure(Constraints.fixed(width, height))

        // The held item, in this layout's own coordinates.
        val itemBounds = heldItem.bounds.translate(-overlayOffset)
        // The plate at the item's own size, so at rest the picture on it is
        // pixel for pixel what was held; a full-width row is then scaled down
        // just enough for the plate to clear the screen edges.
        val plateW = (itemBounds.width.roundToInt() + pad * 2).coerceAtLeast(1)
        val plateH = (itemBounds.height.roundToInt() + pad * 2).coerceAtLeast(1)
        val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
        val menuWidth = (width - margin * 2).coerceAtMost(280.dp.roundToPx()).coerceAtLeast(0)
        val availableHeight = (bottom - top).coerceAtLeast(0)
        // Keep the source item fixed. The ACTIONS must adapt to the remaining
        // space; lifting the item to make room caused the visible upward jump.
        val liftScale = (width - 2 * margin).toFloat().div(plateW.coerceAtLeast(1))
            .coerceIn(0.1f, 1f)
        val visualW = plateW * liftScale
        val visualH = plateH * liftScale
        val visualLeft = itemBounds.center.x - visualW / 2f
        val visualTop = itemBounds.center.y - visualH / 2f
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = visualTop,
            sourceHeight = visualH,
            viewportTop = top,
            viewportBottom = bottom,
            gap = gap,
            requestedMenuHeight = heldMenuMaxHeight(
                availableHeight = availableHeight, minPreview = 0, gap = gap,
            ),
        )
        val menu = measurables[2].measure(
            Constraints(
                minWidth = menuWidth, maxWidth = menuWidth,
                maxHeight = placement.menuMaxHeight,
            ),
        )
        val visualCenterX = visualLeft + visualW / 2f
        val visualCenterY = visualTop + visualH / 2f
        val alignEnd = visualCenterX > width / 2f
        val menuX = (if (alignEnd) visualLeft + visualW - menuWidth else visualLeft).roundToInt()
            .coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
        val menuY = if (placement.menuBelow) {
            (visualTop + visualH + gap).roundToInt()
        } else {
            (visualTop - gap - menu.height).roundToInt()
        }.coerceIn(top, (bottom - menu.height).coerceAtLeast(top))
        val cardX = (visualCenterX - plateW / 2f).roundToInt()
        val cardY = (visualCenterY - plateH / 2f).roundToInt()
        // No positional animation: the captured frame already occupies its
        // source coordinates. Only a subtle scale/shadow denotes elevation.
        val fromDx = itemBounds.center.x - visualCenterX
        val fromDy = itemBounds.center.y - visualCenterY

        layout(width, height) {
            backdrop.place(0, 0)
            card.placeWithLayer(cardX, cardY) {
                val p = progress.value
                if (reduceMotion) {
                    scaleX = liftScale
                    scaleY = liftScale
                } else {
                    val scale = lerp(1f, liftScale, p)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                }
                // The source row is already hidden while this lifted snapshot
                // exists. Never fade one before restoring the other: the image
                // remains opaque as it returns to its exact original position.
                alpha = heldPreviewAlpha(
                    hasImage = heldItem.image != null,
                    lifted = heldItem.isLifted,
                )
            }
            menu.placeWithLayer(menuX, menuY) {
                val p = progress.value
                alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                if (!reduceMotion) {
                    // Grows out of the corner nearest the item, and travels
                    // with it.
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (alignEnd) 1f else 0f,
                        pivotFractionY = if (placement.menuBelow) 0f else 1f,
                    )
                    val scale = lerp(0.55f, 1f, p)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                }
            }
        }
    }
}

/** Menu height is constrained to the available side; preview never moves. */
internal data class HeldAnchoredVerticalPlacement(
    val previewTop: Float,
    val menuTop: Int,
    val menuMaxHeight: Int,
    val menuBelow: Boolean,
)

internal fun heldMenuAnchoredVerticalPlacement(
    sourceTop: Float,
    sourceHeight: Float,
    viewportTop: Int,
    viewportBottom: Int,
    gap: Int,
    requestedMenuHeight: Int,
): HeldAnchoredVerticalPlacement {
    val belowSpace = (viewportBottom - (sourceTop + sourceHeight + gap)).toInt().coerceAtLeast(0)
    val aboveSpace = (sourceTop - gap - viewportTop).toInt().coerceAtLeast(0)
    val below = belowSpace >= requestedMenuHeight ||
        (belowSpace >= aboveSpace && belowSpace > 0)
    val available = if (below) belowSpace else aboveSpace
    val menuHeight = requestedMenuHeight.coerceAtLeast(1).coerceAtMost(available.coerceAtLeast(1))
    val y = if (below) (sourceTop + sourceHeight + gap).roundToInt()
        else (sourceTop - gap - menuHeight).roundToInt()
    return HeldAnchoredVerticalPlacement(
        previewTop = sourceTop,
        menuTop = y.coerceIn(viewportTop, (viewportBottom - menuHeight).coerceAtLeast(viewportTop)),
        menuMaxHeight = menuHeight,
        menuBelow = below,
    )
}

private suspend fun PointerInputScope.swallowAll() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * Held item and its action plate share the same neutral opaque material as
 * the Library filter. The caller's artwork tint is deliberately ignored:
 * dynamic colors behind white menu text were causing illegible green/purple
 * context menus and duplicated original-row content.
 */
@Suppress("UNUSED_PARAMETER")
internal fun heldContextPlateColor(themeBackground: Color, artworkElevation: Color?): Color =
    nativeCompactPlateColor(themeBackground.luminance() < 0.5f, artworkElevation = null)
