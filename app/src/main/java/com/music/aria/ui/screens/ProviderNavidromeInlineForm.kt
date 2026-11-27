package com.music.aria.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.music.aria.data.catalog.provider.opensubsonic.AndroidOpenSubsonicAccountSession
import com.music.aria.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.aria.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.aria.ui.components.ConfirmationAlert
import com.music.aria.ui.components.NativeAlertHost
import com.music.aria.ui.components.NativeSettingsPrimaryAction
import com.music.aria.ui.components.NativeSettingsSecondaryAction
import kotlinx.coroutines.launch

/**
 * A valid editor needs a server and a credential for the selected mode.
 * Existing secrets can only be kept in the same server/authentication realm.
 */
internal fun navidromeAccountReady(
    url: String,
    mode: OpenSubsonicAuthMode,
    username: String,
    secret: String,
    canKeepCredential: Boolean,
): Boolean = url.trim().isNotEmpty() &&
    (mode != OpenSubsonicAuthMode.PASSWORD || username.trim().isNotEmpty()) &&
    (secret.isNotEmpty() || canKeepCredential)

/**
 * Inline Navidrome editor in Providers → Audio. This uses the original
 * AndroidOpenSubsonicAccountSession, auth-mode rules, encrypted credential
 * handling, live connection probe, sync and forget methods — no parallel
 * token store or extension protocol is introduced.
 */
@Composable
internal fun ProviderNavidromeInlineForm() {
    val context = LocalContext.current
    val session = remember(context.applicationContext) {
        AndroidOpenSubsonicAccountSession(context.applicationContext)
    }
    val account by session.account.collectAsStateWithLifecycle()
    val hasCredential by session.hasCredential.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var url by remember(account?.accountId) { mutableStateOf(account?.serverUrl.orEmpty()) }
    var mode by remember(account?.accountId) {
        mutableStateOf(account?.authMode ?: OpenSubsonicAuthMode.API_KEY)
    }
    var username by remember(account?.accountId) { mutableStateOf(account?.username.orEmpty()) }
    var secret by remember(account?.accountId) { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val busy = connecting || syncing
    val canKeepCredential = account != null && hasCredential &&
        mode == account?.authMode &&
        url.trim().trimEnd('/') == account?.serverUrl &&
        (mode != OpenSubsonicAuthMode.PASSWORD || username.trim() == account?.username)
    val valid = navidromeAccountReady(url, mode, username, secret, canKeepCredential)

    fun draft() = OpenSubsonicAccountDraft(
        serverUrl = url,
        authMode = mode,
        username = username,
        secretReplacement = secret.takeIf(String::isNotEmpty),
    )

    // One main action: authenticate against the server, then persist only
    // verified credentials in the existing encrypted OpenSubsonic session.
    // Never claim login success from a local save alone.
    fun connect() {
        if (busy || !valid) return
        val attempted = draft()
        connecting = true
        status = "Conectando…"
        failed = false
        scope.launch {
            try {
                session.testConnection(attempted).fold(
                    onSuccess = {
                        session.save(attempted).fold(
                            onSuccess = {
                                secret = ""
                                status = "Conectado ao Navidrome"
                                failed = false
                            },
                            onFailure = {
                                status = "Não foi possível salvar a conexão"
                                failed = true
                            },
                        )
                    },
                    onFailure = {
                        status = "Servidor indisponível ou credenciais inválidas"
                        failed = true
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Não foi possível conectar ao Navidrome"
                failed = true
            } finally {
                connecting = false
            }
        }
    }

    SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderInlineField(
                label = "URL do servidor",
                value = url,
                onValueChange = { url = it; status = null },
                enabled = !busy,
            )
            SegmentedControl(
                options = listOf("Chave API", "Senha"),
                selectedIndex = if (mode == OpenSubsonicAuthMode.API_KEY) 0 else 1,
                onSelect = {
                    mode = if (it == 0) OpenSubsonicAuthMode.API_KEY else OpenSubsonicAuthMode.PASSWORD
                    secret = ""
                    status = null
                },
                enabled = !busy,
            )
            if (mode == OpenSubsonicAuthMode.PASSWORD) {
                ProviderInlineField(
                    label = "Usuário",
                    value = username,
                    onValueChange = { username = it; status = null },
                    enabled = !busy,
                )
            }
            ProviderInlineField(
                label = if (mode == OpenSubsonicAuthMode.API_KEY) "Chave API" else "Senha",
                value = secret,
                onValueChange = { secret = it; status = null },
                enabled = !busy,
                secret = true,
            )
            if (canKeepCredential && secret.isEmpty()) {
                Text(
                    "Credencial salva será mantida",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
            NativeSettingsPrimaryAction(
                label = if (account == null) "Conectar" else "Salvar conexão",
                enabled = valid && !busy,
                loading = connecting,
                onClick = ::connect,
                tag = "providers-navidrome-save",
            )
            if (account != null) {
                NativeSettingsSecondaryAction(
                    label = if (syncing) "Sincronizando…" else "Sincronizar biblioteca",
                    enabled = !busy && hasCredential,
                    onClick = {
                        if (busy) return@NativeSettingsSecondaryAction
                        syncing = true
                        scope.launch {
                            try {
                                session.syncNow()
                                status = "Sincronização solicitada"
                                failed = false
                            } catch (_: Exception) {
                                status = "Não foi possível sincronizar a biblioteca"
                                failed = true
                            } finally {
                                syncing = false
                            }
                        }
                    },
                )
                NativeSettingsSecondaryAction(
                    label = "Esquecer servidor",
                    onClick = { confirmForget = true },
                    enabled = !busy,
                )
            }
        }
    }

    if (confirmForget) {
        NativeAlertHost(onDismiss = { confirmForget = false }) {
            ConfirmationAlert(
                hazeState = null,
                title = "Esquecer Navidrome?",
                description = "A conexão e sua credencial serão removidas deste dispositivo.",
                confirmLabel = "Esquecer",
                destructive = true,
                onConfirm = {
                    session.forget()
                    url = ""
                    username = ""
                    secret = ""
                    status = null
                    confirmForget = false
                },
                onDismiss = { confirmForget = false },
            )
        }
    }
}
