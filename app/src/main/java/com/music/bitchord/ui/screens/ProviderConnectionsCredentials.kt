package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Credentials live at the provider / account level, never on a capability.
 *
 * A single DiscogsSearchTokenVault entry is consulted by whichever implemented
 * Discogs adapters need it, while capability enablement stays independent.
 * ListenBrainz keeps its existing account preference and credential lifecycle;
 * Last.fm and Soulseek retain their dedicated account authentication.
 */
@Composable
internal fun ProviderConnectionsCredentials(
    showDiscogs: Boolean,
    showListenBrainz: Boolean,
    showLastFm: Boolean,
    showSoulseek: Boolean,
    discogsConfigured: Boolean,
    listenBrainzConfigured: Boolean,
    discogsToken: String,
    listenBrainzToken: String,
    saving: Boolean,
    onDiscogsTokenChange: (String) -> Unit,
    onListenBrainzTokenChange: (String) -> Unit,
    onSaveDiscogsToken: () -> Unit,
    onSaveListenBrainzToken: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSoulseek: () -> Unit,
) {
    if (!showDiscogs && !showListenBrainz && !showLastFm && !showSoulseek) return

    Column(
        modifier = Modifier.fillMaxWidth()
            .testTag("providers-connections-credentials"),
    ) {
        SettingsGroupSurface {
            var rowShown = false
            if (showDiscogs) {
                SettingsRow(
                    icon = Icons.Rounded.Key,
                    title = "Discogs",
                    subtitle = if (discogsConfigured) {
                        "Token configurado · compartilhado entre as funções Discogs"
                    } else {
                        "Token pessoal usado pelas funções Discogs compatíveis"
                    },
                    minHeight = 60.dp,
                )
                NativeProviderSecretField(
                    value = discogsToken,
                    onValueChange = onDiscogsTokenChange,
                    enabled = !saving,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                NativeProviderCredentialSaveAction(
                    enabled = !saving && discogsToken.isNotBlank(),
                    onClick = onSaveDiscogsToken,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                )
                rowShown = true
            }
            if (showListenBrainz) {
                if (rowShown) RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Cloud,
                    title = "ListenBrainz",
                    subtitle = if (listenBrainzConfigured) {
                        "Token configurado · reutilizado nas funções ListenBrainz"
                    } else {
                        "Token pessoal para os recursos compatíveis do ListenBrainz"
                    },
                    minHeight = 60.dp,
                )
                NativeProviderSecretField(
                    value = listenBrainzToken,
                    onValueChange = onListenBrainzTokenChange,
                    enabled = !saving,
                    providerId = "listenbrainz",
                    providerLabel = "ListenBrainz",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                NativeProviderCredentialSaveAction(
                    enabled = !saving && listenBrainzToken.isNotBlank(),
                    onClick = onSaveListenBrainzToken,
                    providerId = "listenbrainz",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                )
                rowShown = true
            }
            if (showLastFm) {
                if (rowShown) RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Lock,
                    title = "Last.fm",
                    subtitle = "Autorizar a conta no fluxo de login existente",
                    onClick = onOpenAccounts,
                    minHeight = 60.dp,
                    modifier = Modifier.testTag("providers-connections-lastfm"),
                )
                rowShown = true
            }
            if (showSoulseek) {
                if (rowShown) RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Settings,
                    title = "Soulseek",
                    subtitle = "Gerenciar a autenticação da conta",
                    onClick = onOpenSoulseek,
                    minHeight = 60.dp,
                    modifier = Modifier.testTag("providers-connections-soulseek"),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
