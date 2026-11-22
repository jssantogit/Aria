package com.music.bitchord.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.NativeSettingsPrimaryAction
import com.music.bitchord.ui.components.NativeSettingsSecondaryAction
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.appleSettingsSwitchColors
import kotlinx.coroutines.launch

/** Routes live inside the Providers navigation stack, never in Settings. */
internal enum class ProviderAccountDetail(val title: String) {
    LASTFM("Last.fm"),
    SOULSEEK("Soulseek"),
}

/**
 * A compact non-Material3-outlined input shared by the Providers editors.
 * The placeholder identifies the field; do not repeat it with a second
 * label above and a verbose explanation beneath.
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
    Box(modifier.fillMaxWidth().testTag("providers-inline-field-${label.lowercase().replace(" ", "-")}")) {
        PillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = label,
            enabled = enabled,
            isPassword = secret,
            accessibilityLabel = label,
        )
    }
}

/** Do not expose typed credentials through screenshots or Android Recents. */
@Composable
internal fun ProviderSecureEntryWindow() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val wasSecure = window?.attributes?.flags
            ?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * Reuses LastFM.getMobileSession + AppSettings session persistence verbatim.
 * The password is held only in composition memory and cleared after login;
 * the provider's switches/consents remain distinct from authentication.
 */
@Composable
internal fun ProviderLastFmAccountForm() {
    ProviderSecureEntryWindow()
    val scope = rememberCoroutineScope()
    val sessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val sessionUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val scrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val nowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val primaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
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
}
