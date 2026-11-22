package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.music.bitchord.R
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.ui.components.NativeSettingsPrimaryAction
import com.music.bitchord.ui.components.NativeSettingsSecondaryAction
import com.music.bitchord.ui.components.appleSettingsSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Routes live inside the Providers navigation stack, never in Settings. */
internal enum class ProviderAccountDetail(val title: String) {
    LASTFM("Last.fm"),
    SOULSEEK("Soulseek"),
}

/**
 * A real editable field using the native Search bar's rounded, inset and
 * outlined surface. Password text stays masked even though this screen now
 * permits screenshots (requested for physical UI review).
 *
 * Own the BasicTextField directly: do not wrap PillTextField, whose passive
 * visual treatment was indistinguishable from a label in the provider cards.
 */
@Composable
internal fun ProviderInlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    var focused by remember { mutableStateOf(false) }
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val outline = if (focused) accent else foreground.copy(alpha = 0.24f)
    val background = MaterialTheme.colorScheme.surface

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = foreground),
        visualTransformation = if (secret) PasswordVisualTransformation()
            else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                label == "URL do servidor" -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            imeAction = ImeAction.Done,
        ),
        cursorBrush = SolidColor(accent),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, outline, shape)
            .onFocusChanged { focused = it.isFocused }
            .testTag("providers-inline-field-${label.lowercase().replace(" ", "-")}")
            .padding(horizontal = 16.dp, vertical = 13.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = foreground.copy(alpha = 0.55f),
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * Reuses LastFM.getMobileSession + AppSettings session persistence verbatim.
 * The password is held only in composition memory and cleared after login;
 * the provider's switches/consents remain distinct from authentication.
 */
@Composable
internal fun ProviderLastFmAccountForm() {
    val scope = rememberCoroutineScope()
    val sessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val sessionUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val scrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val nowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val primaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val connected = sessionKey.isNotBlank()

    fun signIn() {
        if (busy || username.isBlank() || password.isBlank()) return
        val requestPassword = password
        password = ""
        feedback = null
        busy = true
        scope.launch {
            try {
                LastFM.initialize(
                    apiKey = AppSettings.lastfmApiKey.value,
                    secret = AppSettings.lastfmSecret.value,
                )
                LastFM.getMobileSession(username.trim(), requestPassword)
                    .onSuccess { auth ->
                        AppSettings.setLastfmSessionKey(auth.session.key)
                        AppSettings.setLastfmUsername(auth.session.name)
                        AppSettings.setLastfmEnabled(true)
                        // Login supplies readiness, never silently rewrites
                        // the Provider Engine's independent opt-in choice.
                        val refreshed = withContext(Dispatchers.IO) {
                            ProviderRuntime.refreshListeningCredentialsFromLegacyUi()
                        }
                        if (refreshed == false) {
                            feedback = "Conta salva; a disponibilidade do provedor não pôde ser atualizada."
                        }
                        username = ""
                    }
                    .onFailure { error ->
                        feedback = error.message ?: "Não foi possível autenticar a conta."
                    }
            } catch (_: Exception) {
                feedback = "Não foi possível autenticar a conta."
            } finally {
                busy = false
            }
        }
    }

    SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (connected) {
                Text(
                    "Conectado como $sessionUsername",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("providers-lastfm-connected"),
                )
                NativeSettingsSecondaryAction(
                    label = "Desconectar conta",
                    onClick = {
                        AppSettings.setLastfmSessionKey("")
                        AppSettings.setLastfmUsername("")
                        AppSettings.setLastfmEnabled(false)
                        AppSettings.setLastfmScrobbleEnabled(false)
                        AppSettings.setLastfmNowPlaying(false)
                        scope.launch(Dispatchers.IO) {
                            ProviderRuntime.refreshListeningCredentialsFromLegacyUi()
                        }
                    },
                    enabled = !busy,
                )
            } else {
                ProviderInlineField(
                    label = "Usuário",
                    value = username,
                    onValueChange = { username = it.take(256) },
                    enabled = !busy,
                )
                ProviderInlineField(
                    label = "Senha",
                    value = password,
                    onValueChange = { password = it.take(256) },
                    enabled = !busy,
                    secret = true,
                )
                NativeSettingsPrimaryAction(
                    label = "Entrar",
                    onClick = ::signIn,
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    loading = busy,
                    tag = "providers-lastfm-sign-in",
                )
            }
            feedback?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (connected) {
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = "Ativar Last.fm",
                trailing = {
                    Switch(
                        checked = lastfmEnabled,
                        onCheckedChange = AppSettings::setLastfmEnabled,
                        colors = appleSettingsSwitchColors(),
                    )
                },
                onClick = { AppSettings.setLastfmEnabled(!lastfmEnabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = "Scrobbling",
                trailing = {
                    Switch(
                        checked = scrobbleEnabled,
                        onCheckedChange = AppSettings::setLastfmScrobbleEnabled,
                        colors = appleSettingsSwitchColors(),
                    )
                },
                onClick = { AppSettings.setLastfmScrobbleEnabled(!scrobbleEnabled) },
            )
            RowDivider()
            SettingsRow(
                title = "Reprodução atual",
                trailing = {
                    Switch(
                        checked = nowPlayingEnabled,
                        onCheckedChange = AppSettings::setLastfmNowPlaying,
                        colors = appleSettingsSwitchColors(),
                    )
                },
                onClick = { AppSettings.setLastfmNowPlaying(!nowPlayingEnabled) },
            )
            RowDivider()
            SettingsRow(
                title = "Somente artista principal",
                trailing = {
                    Switch(
                        checked = primaryArtistOnly,
                        onCheckedChange = AppSettings::setLastfmPrimaryArtistOnly,
                        colors = appleSettingsSwitchColors(),
                    )
                },
                onClick = { AppSettings.setLastfmPrimaryArtistOnly(!primaryArtistOnly) },
            )
        }
    }
    if (connected) {
        SettingsGroupSurface(modifier = Modifier.padding(
            start = 16.dp, end = 16.dp, top = 12.dp,
        )) {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = "Tempos de scrobbling",
                onClick = { advanced = !advanced },
                value = if (advanced) "Ocultar" else "Ajustar",
                modifier = Modifier.testTag("providers-lastfm-advanced"),
            )
            if (advanced) {
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.min_song_duration),
                    value = "${scrobbleMinDuration}s",
                    sliderValue = scrobbleMinDuration.toFloat(),
                    onSliderValue = { AppSettings.setScrobbleMinDuration(it.roundToInt()) },
                    valueRange = 15f..120f,
                    steps = 20,
                )
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.scrobble_delay),
                    value = "${(scrobbleDelayPercent * 100).roundToInt()}%",
                    sliderValue = scrobbleDelayPercent,
                    onSliderValue = { AppSettings.setScrobbleDelayPercent(it) },
                    valueRange = 0.1f..1.0f,
                    steps = 8,
                )
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.max_delay),
                    value = "${scrobbleDelaySeconds}s",
                    sliderValue = scrobbleDelaySeconds.toFloat(),
                    onSliderValue = { AppSettings.setScrobbleDelaySeconds(it.roundToInt()) },
                    valueRange = 30f..300f,
                    steps = 26,
                )
            }
        }
    }
}
