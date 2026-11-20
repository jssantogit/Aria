package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt

/**
 * Reuses the existing native artwork tile and adds the visible Apple-style
 * title and removable editing overlay. All media sources share this wrapper.
 *
 * The original APK suggests an animated 0.87 scale, but its precise target,
 * spring, and drag threshold were not recovered; these are BitChord choices.
 */
@Composable
internal fun PinnedLibraryEditorTile(
    id: String,
    title: String,
    editing: Boolean,
    columns: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    onMoveBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gutterPx = with(density) { 12.dp.toPx() }
    val captionPitchPx = with(density) { 48.dp.toPx() }
    var dragOffset by remember(id) { mutableStateOf(Offset.Zero) }
    val imageScale by animateFloatAsState(
        targetValue = if (editing) 0.87f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "pinned-library-edit-scale",
    )
    val drag = if (editing) Modifier.pointerInput(id, columns) {
        detectDragGesturesAfterLongPress(
            onDragStart = { dragOffset = Offset.Zero },
            onDragCancel = { dragOffset = Offset.Zero },
            onDragEnd = {
                val dx = (dragOffset.x / (size.width + gutterPx)).roundToInt()
                val dy = (dragOffset.y / (size.width + captionPitchPx)).roundToInt()
                dragOffset = Offset.Zero
                if (dx != 0 || dy != 0) onMoveBy(dx + dy * columns)
            },
            onDrag = { change, amount ->
                change.consume()
                dragOffset += amount
            },
        )
    } else Modifier

    Column(
        modifier = modifier.then(drag)
            .graphicsLayer {
                translationX = if (editing) dragOffset.x else 0f
                translationY = if (editing) dragOffset.y else 0f
            }
            .semantics {
                if (editing) {
                    customActions = listOf(
                        CustomAccessibilityAction("Mover para antes") {
                            onMoveBy(-1)
                            true
                        },
                        CustomAccessibilityAction("Mover para depois") {
                            onMoveBy(1)
                            true
                        },
                        CustomAccessibilityAction("Desafixar $title") {
                            onRemove()
                            true
                        },
                    )
                }
            }
            .testTag("library-pin-editor-tile"),
    ) {
        Box(Modifier.fillMaxWidth()) {
            PinnedLibraryArtworkTile(
                title = title,
                onClick = onClick,
                onLongPress = onLongPress,
                interactive = !editing,
                modifier = Modifier.graphicsLayer {
                    scaleX = imageScale
                    scaleY = imageScale
                },
                artwork = artwork,
            )
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopStart)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8E8E93))
                        .clickable(onClick = onRemove)
                        .semantics { contentDescription = "Desafixar $title" }
                        .testTag("library-pin-editor-remove"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Remove, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("library-pin-editor-title"),
        )
    }
}
