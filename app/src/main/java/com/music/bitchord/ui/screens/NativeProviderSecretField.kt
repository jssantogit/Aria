package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.NativeSettingsPrimaryAction

/**
 * The same compact rounded BasicTextField vocabulary as the app's native
 * SearchField, repurposed for a secret in Settings. No Material3 OutlinedTextField
 * and no copy of the token in a saveable state or navigation arguments.
 */
@Composable
internal fun NativeProviderSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    providerId: String = "discogs",
    providerLabel: String = "Discogs",
    savedHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    // Reset visibility whenever input is emptied/saved so the next token
    // can never inherit a previously enabled plaintext reveal state.
    var revealed by remember(value.isEmpty()) { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        visualTransformation = if (revealed) VisualTransformation.None
            else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .testTag("providers-${providerId}-token-input")
            .semantics { contentDescription = "Token do ${providerLabel}" }
            .padding(start = 14.dp, end = 4.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            // Presentation only: never make the mask the
                            // editable value or expose the saved credential
                            // through the reveal toggle.
                            savedHint ?: "Cole seu token pessoal",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(
                                "providers-${providerId}-token-hint",
                            ),
                        )
                    }
                    inner()
                }
                IconButton(
                    onClick = { revealed = !revealed },
                    // A stored masked hint cannot be revealed. The eye is
                    // available only while the user enters a new raw token.
                    enabled = enabled && value.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                        .testTag("providers-${providerId}-token-visibility"),
                ) {
                    Icon(
                        if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (revealed) "Ocultar token" else "Mostrar token",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

/** Canonical native blue 50dp pill, shared with the other Settings forms. */
@Composable
internal fun NativeProviderCredentialSaveAction(
    enabled: Boolean,
    onClick: () -> Unit,
    providerId: String = "discogs",
    modifier: Modifier = Modifier,
) {
    NativeSettingsPrimaryAction(
        label = "Salvar token",
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        tag = "providers-${providerId}-token-save",
    )
}
