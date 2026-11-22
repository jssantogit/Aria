package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.ui.components.NativeAlertHost
import com.music.bitchord.ui.components.NativeSettingsSecondaryAction
import com.music.bitchord.ui.components.NativeSettingsPrimaryAction
import com.music.bitchord.ui.theme.appleMusicKeyColor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.BuildConfig
import com.music.bitchord.acquisition.a0.SoulseekAccountProbe
import com.music.bitchord.acquisition.a0.SoulseekCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reuse the same secure account workflow in Provedores > Conexões; the
 * legacy Settings route is retained for compatibility without duplicating storage.
 * "Authenticated" means a one-shot check succeeded, NOT a live TCP session.
 * The devDebug A0 manual picker is the only authorized transfer executor.
 */
@Composable
internal fun SoulseekAccountSettingsScreen(
    contentPadding: PaddingValues,
    embeddedInProviders: Boolean = false,
) {
    val context = LocalContext.current
    val vault = remember(context.applicationContext) {
        SoulseekCredentialVault(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var savedUsername by remember {
        mutableStateOf(vault.read()?.let { saved ->
            saved.username.also { saved.erase() }
        })
    }
    var username by remember { mutableStateOf(savedUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (savedUsername != null) "Credenciais protegidas; nenhuma sessão TCP ativa."
            else "Nenhuma conta Soulseek configurada."
        )
    }
    val pilot = BuildConfig.FLAVOR == "dev" && BuildConfig.DEBUG

    // Screenshots are permitted in provider forms. The native password field
    // still uses PasswordVisualTransformation and never prints the secret.

    fun connectAndSave() {
        if (busy) return
        if (!pilot) {
            status = "Autenticação disponível somente na variante devDebug."
            return
        }
        // The primary action can be pressed after filling the account;
        // consent is still mandatory before ANY credential leaves the device.
        if (!acknowledged) {
            status = "Marque a autorização de autenticação TCP sem TLS para conectar."
            return
        }
        val requestedUser = username.trim()
        val typed = password
        if (requestedUser.isBlank()) {
            status = "Informe o usuário da conta dedicada."
            return
        }
        password = ""
        busy = true
        status = "Autenticando…"
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    val existing = if (typed.isEmpty() && savedUsername == requestedUser) {
                        vault.read()
                    } else null
                    val secret = if (existing?.username == requestedUser) {
                        existing.password.copyOf()
                    } else typed.toCharArray()
                    existing?.erase()
                    try {
                        if (secret.isEmpty()) false
                        else {
                            val connected = SoulseekAccountProbe.test(requestedUser, secret)
                            if (connected) vault.save(requestedUser, secret)
                            connected
                        }
                    } catch (_: Exception) {
                        false
                    } finally {
                        secret.fill('\u0000')
                    }
                }
                if (success) {
                    savedUsername = requestedUser
                    status = "Conta autenticada e salva com Android Keystore."
                } else {
                    status = "Autenticação ou rede indisponível. Nenhuma credencial alterada."
                }
            } catch (_: Exception) {
                status = "Não foi possível autenticar. Nenhuma credencial alterada."
            } finally {
                busy = false
            }
        }
    }

    // Provider pages own the title and scroll container; the credentials
    // card uses exactly the same 16dp inset as Appearance and Connections.
    val accountFields: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (savedUsername != null) {
                Text(
                    "Conta salva: $savedUsername",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!pilot) {
                Text(
                    "A autenticação experimental está disponível somente em devDebug.",
                    style = MaterialTheme.typography.bodyMedium,
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
                Text(
                    "O Soulseek envia credenciais por TCP sem TLS. Use uma conta exclusiva de testes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(
                            checkedColor = appleMusicKeyColor(),
                            checkmarkColor = Color.White,
                        ),
                    )
                    Text(
                        "Autorizo testar autenticação sem TLS",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (status.isNotBlank() && !status.startsWith("Nenhuma conta") &&
                    !status.startsWith("Credenciais protegidas")
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                NativeSettingsPrimaryAction(
                    label = "Conectar",
                    onClick = ::connectAndSave,
                    enabled = !busy && username.isNotBlank() &&
                        (password.isNotBlank() || savedUsername == username.trim()),
                    loading = busy,
                    tag = "providers-soulseek-save",
                )
                if (savedUsername != null) {
                    NativeSettingsSecondaryAction(
                        label = "Esquecer conta",
                        onClick = { confirmForget = true },
                        enabled = !busy,
                    )
                }
            }
        }
    }

    if (embeddedInProviders) {
        SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
            accountFields()
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(contentPadding).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Soulseek", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 10.dp))
            SettingsGroupSurface { accountFields() }
        }
    }

    if (confirmForget) {
        NativeAlertHost(onDismiss = { confirmForget = false }) {
            ConfirmationAlert(
                hazeState = null,
                title = "Esquecer conta Soulseek?",
                description = "A credencial cifrada e sua chave serão apagadas. Os arquivos locais já salvos serão preservados.",
                confirmLabel = "Esquecer",
                destructive = true,
                onConfirm = {
                    vault.forget()
                    savedUsername = null
                    username = ""
                    password = ""
                    acknowledged = false
                    status = "Conta esquecida. Nenhuma sessão ativa."
                    confirmForget = false
                },
                onDismiss = { confirmForget = false },
            )
        }
    }
}
