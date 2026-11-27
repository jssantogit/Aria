package com.music.aria.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderInventoryEntry
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.ui.components.appleSettingsSwitchColors

/**
 * A pushed Settings page, not an AlertDialog or a second provider inventory.
 * Reuses the native Appearance page's SettingsGroupSurface, SettingsRow,
 * RowDivider, switches and the same REA top header as the parent.
 *
 * Rendering is independent of the provider's origin/context. Every switch
 * still writes only the original (capability, provider instance) choice.
 */
@Composable
internal fun ProviderCapabilitiesScreen(
    group: ProviderDisplayGroup,
    configured: Map<ProviderCapability, List<ProviderChoice>>,
    saving: Boolean,
    loading: Boolean,
    error: String?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onToggle: (ProviderInventoryEntry, Boolean) -> Unit,
    onConfigure: (ProviderInventoryEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 20.dp)
            .testTag("providers-capability-page"),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            ProviderHeader(
                title = group.title,
                editing = false,
                saving = saving,
                onBack = onBack,
                onOrganize = {},
                showOrganize = false,
                onConnections = {},
                onCancel = {},
                onDone = {},
            )
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
            presentableProviderFunctions(group).forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                val capability = row.entry.capability
                val choiceEnabled = configured[capability].orEmpty().any {
                    it.key == row.entry.key && it.enabled
                }
                val ready = row.entry.readiness == ProviderReadiness.READY
                val details = capability.functionSettingsText()
                val warning = row.entry.readiness.functionSettingsWarning()
                SettingsRow(
                    icon = capability.providerFunctionIcon(),
                    title = details.title,
                    subtitle = if (warning == null) details.description
                        else "${details.description}\n${warning}",
                    // Prerequisites first: do not show a grey, inoperable
                    // switch until the credential/server makes this exact
                    // capability eligible. An existing opt-out is preserved.
                    trailing = if (ready) ({
                        Switch(
                            checked = choiceEnabled,
                            onCheckedChange = { onToggle(row.entry, it) },
                            enabled = !saving && !loading,
                            colors = appleSettingsSwitchColors(),
                            modifier = Modifier
                                .testTag("providers-function-switch-${capability.name}")
                                .semantics {
                                    contentDescription = "${group.title}: ${details.title}"
                                },
                        )
                    }) else null,
                    onClick = if (ready && !saving && !loading) {
                        { onToggle(row.entry, !choiceEnabled) }
                    } else null,
                    minHeight = 68.dp,
                    modifier = Modifier.testTag("providers-function-${capability.name}"),
                )
            }
        }

        // Local library configuration belongs to the actual MediaStore
        // provider, below its independent engine capability switch.
        if (group.key.providerId == "local") {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.testTag("providers-local-library-settings")) {
                ProviderLocalLibrarySettings()
            }
        }

        // Soulseek's optional A1 destination is a DOWNLOAD setting, not
        // another account/credential field. Keep the original persisted
        // SAF read/write grant and reset flow; the download publisher remains
        // governed independently by its existing feature gates.
        if (group.key.providerId == "soulseek" &&
            group.rows.any { it.entry.capability == ProviderCapability.DOWNLOAD }
        ) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.testTag("providers-soulseek-folder-settings")) {
                SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
                    A1AcquisitionDestinationSettingsRow()
                }
            }
        }

        // Credential management belongs exclusively to Provedores ⋮ →
        // Conexões, which reuses the shared token vault. Never render a
        // second Discogs/ListenBrainz/Last.fm/Soulseek connection row here.
        // OpenSubsonic is different: configuring its remote server is part of
        // its actual Audio provider, not a duplicate credential editor.
        val serverEntry = group.rows.firstOrNull {
            it.entry.key.providerId == "opensubsonic"
        }?.entry
        if (serverEntry != null) {
            Spacer(Modifier.height(22.dp))
            SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Settings,
                    title = "Servidor",
                    subtitle = "Configurar, testar e sincronizar a biblioteca remota.",
                    onClick = if (!saving && !loading) {
                        { onConfigure(serverEntry) }
                    } else null,
                    minHeight = 68.dp,
                    modifier = Modifier.testTag("providers-function-server"),
                )
            }
        }
    }
}

private fun ProviderCapability.providerFunctionIcon(): ImageVector = when (this) {
    ProviderCapability.CATALOG_SEARCH -> Icons.Rounded.Search
    ProviderCapability.EDITORIAL_DISCOGRAPHY -> Icons.Rounded.LibraryMusic
    ProviderCapability.IDENTITY -> Icons.Rounded.Fingerprint
    ProviderCapability.EDITION_METADATA -> Icons.Rounded.LibraryMusic
    ProviderCapability.ACOUSTIC_IDENTIFICATION -> Icons.Rounded.GraphicEq
    ProviderCapability.RELEASE_ARTWORK, ProviderCapability.ARTIST_ARTWORK ->
        Icons.Rounded.Image
    ProviderCapability.LYRICS_LOOKUP,
    ProviderCapability.LYRICS_SYNCED,
    ProviderCapability.LYRICS_PLAIN -> Icons.Rounded.GraphicEq
    ProviderCapability.CHART_MOST_PLAYED,
    ProviderCapability.CHART_SALES,
    ProviderCapability.LISTENING_STATISTICS,
    ProviderCapability.PERSONAL_RECOMMENDATIONS,
    ProviderCapability.PUBLIC_PLAYLISTS -> Icons.Rounded.Tune
    ProviderCapability.PERSONAL_LIBRARY -> Icons.Rounded.LibraryMusic
    ProviderCapability.AUDIO_PLAYBACK, ProviderCapability.EXTENSION_PLAYBACK ->
        Icons.Rounded.Headphones
    ProviderCapability.DOWNLOAD -> Icons.Rounded.Download
}
