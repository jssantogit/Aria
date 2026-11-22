package com.music.bitchord.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.smb.SmbRepository
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.ui.components.NativeSettingsPrimaryAction
import com.music.bitchord.ui.components.NativeSettingsSecondaryAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real WebDAV/SMB editors directly inside Provedores → Áudio, not dialogs.
 * The existing AppSettings keys and repository probes are the storage/
 * connectivity source of truth; no duplicate secrets or provider capabilities.
 */
@Composable
internal fun ProviderRemoteServerInlineForm(target: ProviderRemoteLibraryTarget) {
    require(target != ProviderRemoteLibraryTarget.OPEN_SUBSONIC)
    ProviderSecureEntryWindow()
    val scope = rememberCoroutineScope()

    val storedWebUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val storedSmbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val storedSmbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val configured = if (target == ProviderRemoteLibraryTarget.WEBDAV) {
        storedWebUrl.isNotBlank()
    } else {
        storedSmbHost.isNotBlank() && storedSmbShare.isNotBlank()
    }

    var address by remember(target) { mutableStateOf(
        if (target == ProviderRemoteLibraryTarget.WEBDAV) AppSettings.webdavUrl.value
        else AppSettings.smbHost.value
    ) }
    var share by remember(target) { mutableStateOf(AppSettings.smbShare.value) }
    var folder by remember(target) { mutableStateOf(AppSettings.smbBasePath.value) }
    var username by remember(target) { mutableStateOf(
        if (target == ProviderRemoteLibraryTarget.WEBDAV) AppSettings.webdavUsername.value
        else AppSettings.smbUsername.value
    ) }
    var password by remember(target) { mutableStateOf(
        if (target == ProviderRemoteLibraryTarget.WEBDAV) AppSettings.webdavPassword.value
        else AppSettings.smbPassword.value
    ) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val valid = address.trim().isNotBlank() &&
        (target == ProviderRemoteLibraryTarget.WEBDAV || share.trim().isNotBlank())

    fun save() {
        if (testing || !valid) return
        if (target == ProviderRemoteLibraryTarget.WEBDAV) {
            AppSettings.setWebDavUrl(address.trim())
            AppSettings.setWebDavUsername(username.trim())
            AppSettings.setWebDavPassword(password)
        } else {
            AppSettings.setSmbHost(address.trim())
            AppSettings.setSmbShare(share.trim())
            AppSettings.setSmbBasePath(folder.trim())
            AppSettings.setSmbUsername(username.trim())
            AppSettings.setSmbPassword(password)
        }
        failed = false
        status = "Configuração salva"
    }

    fun test() {
        if (testing || !valid) return
        testing = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (target == ProviderRemoteLibraryTarget.WEBDAV) {
                    WebDavRepository.testConnection(address.trim(), username.trim(), password)
                } else {
                    SmbRepository.testConnection(
                        address.trim(), share.trim(), folder.trim(),
                        username.trim(), password,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    failed = false
                    status = "Conexão confirmada"
                },
                onFailure = {
                    failed = true
                    status = "Não foi possível conectar ao servidor"
                },
            )
            testing = false
        }
    }

    SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderInlineField(
                label = if (target == ProviderRemoteLibraryTarget.WEBDAV)
                    "URL do servidor" else "Servidor SMB",
                value = address,
                onValueChange = { address = it; status = null },
                enabled = !testing,
            )
            if (target == ProviderRemoteLibraryTarget.SMB) {
                ProviderInlineField(
                    label = "Compartilhamento",
                    value = share,
                    onValueChange = { share = it; status = null },
                    enabled = !testing,
                )
                ProviderInlineField(
                    label = "Pasta (opcional)",
                    value = folder,
                    onValueChange = { folder = it; status = null },
                    enabled = !testing,
                )
            }
            ProviderInlineField(
                label = "Usuário",
                value = username,
                onValueChange = { username = it; status = null },
                enabled = !testing,
            )
            ProviderInlineField(
                label = "Senha",
                value = password,
                onValueChange = { password = it; status = null },
                enabled = !testing,
                secret = true,
            )
            status?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
            NativeSettingsPrimaryAction(
                label = "Salvar",
                enabled = valid && !testing,
                loading = testing,
                onClick = ::save,
                tag = "providers-server-save",
            )
            NativeSettingsSecondaryAction(
                label = "Testar conexão",
                onClick = ::test,
                enabled = valid && !testing,
            )
            if (configured) {
                NativeSettingsSecondaryAction(
                    label = "Desconectar",
                    enabled = !testing,
                    onClick = {
                        if (target == ProviderRemoteLibraryTarget.WEBDAV) AppSettings.clearWebDav()
                        else AppSettings.clearSmb()
                        address = ""
                        share = ""
                        folder = ""
                        username = ""
                        password = ""
                        status = null
                    },
                    modifier = Modifier.testTag("providers-server-disconnect"),
                )
            }
        }
    }
}
