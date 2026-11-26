package com.music.aria.ui.components

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.music.aria.ui.theme.ariaAccentColor
import com.music.aria.ui.theme.ariaOnAccentColor

/**
 * Native amber affirmative action palette, shared by error, playlist, and
 * collaboration flows. Button remains a Compose accessibility primitive;
 * filled actions use the contrasting Aria foreground and disabled states stay neutral.
 */
@Composable
fun applePrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = ariaAccentColor(),
    contentColor = ariaOnAccentColor(),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
