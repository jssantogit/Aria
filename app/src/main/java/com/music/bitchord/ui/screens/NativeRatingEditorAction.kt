package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.appleMusicKeyColor

/** The rating editor shares compact menu controls, not opaque dialog pills. */
internal data class RatingEditorActionColors(val background: Color, val ink: Color)

internal fun ratingEditorActionHeightDp(): Int = 48
internal fun ratingEditorActionCornerRadiusDp(): Int = 12

internal fun ratingEditorActionColors(
    darkTheme: Boolean,
    emphasized: Boolean,
    enabled: Boolean,
    loading: Boolean,
): RatingEditorActionColors {
    val ink = if (darkTheme) Color.White else Color.Black
    val surface = if (darkTheme) Color(0xFF36363B) else Color(0xFFE5E5E8)
    return RatingEditorActionColors(
        background = if (emphasized) surface else Color.Transparent,
        ink = ink.copy(alpha = if (enabled && !loading) 1f else 0.40f),
    )
}

/**
 * Compact, readable commands on the same frosted neutral plate as Library
 * filters and overflow. The confirm action is gently surfaced rather than a
 * bright white pill that visually detaches from the expandable card.
 */
@Composable
internal fun NativeRatingEditorAction(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tag: String,
) {
    val colors = ratingEditorActionColors(darkTheme, emphasized, enabled, loading)
    Box(
        modifier = modifier
            .height(ratingEditorActionHeightDp().dp)
            .clip(RoundedCornerShape(ratingEditorActionCornerRadiusDp().dp))
            .background(colors.background)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("native-rating-action-loading"),
                color = colors.ink,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = colors.ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}
