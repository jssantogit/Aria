package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityStatus
import com.music.bitchord.data.providers.ProviderInventoryEntry
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderOperationalHealth
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderTabRowState
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProviderCapabilitiesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerToolbarUsesOneOverflowWithOrganizeAndConnections() {
        var organizeCalls = 0
        var connectionsCalls = 0
        compose.setContent {
            BitChordTheme {
                ProviderHeader(
                    title = "Provedores",
                    editing = false,
                    saving = false,
                    onBack = {},
                    onOrganize = { organizeCalls++ },
                    showOrganize = true,
                    onConnections = { connectionsCalls++ },
                    onCancel = {},
                    onDone = {},
                )
            }
        }
        compose.onAllNodesWithTag("providers-top-organize", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithTag("providers-top-connections", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("providers-top-overflow", useUnmergedTree = true)
            .performClick()
        // The provider command plate inherits the native frosted, rounded
        // expandable-card treatment, rather than a default Material3 menu.
        compose.onNodeWithTag("native-compact-neutral-material", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("Conexões").performClick()
        compose.runOnIdle {
            assertEquals(0, organizeCalls)
            assertEquals(1, connectionsCalls)
        }
        compose.onNodeWithTag("providers-top-overflow", useUnmergedTree = true)
            .performClick()
        compose.onNodeWithText("Organizar").performClick()
        compose.runOnIdle {
            assertEquals(1, organizeCalls)
            assertEquals(1, connectionsCalls)
        }
    }

    @Test fun musicBrainzGetsNativeSettingsPageWithThreeIndependentRowsNotDialog() {
        val key = ProviderKey("musicbrainz")
        fun row(capability: ProviderCapability) = ProviderTabRowState(
            entry = ProviderInventoryEntry(
                key = key,
                capability = capability,
                tab = ProviderSettingsTab.CATALOG,
                displayName = capability.name,
                readiness = ProviderReadiness.READY,
                supportsReordering = capability != ProviderCapability.IDENTITY,
                notes = "",
            ),
            status = ProviderCapabilityStatus(
                capability = capability,
                key = key,
                implemented = true,
                prerequisitesMet = true,
                userAuthorized = false,
                operationalHealth = ProviderOperationalHealth.UNKNOWN,
                effectiveEligible = false,
            ),
            order = 0,
        )
        val group = ProviderDisplayGroup(
            key = key,
            tab = ProviderSettingsTab.CATALOG,
            title = "MusicBrainz",
            rows = listOf(
                row(ProviderCapability.CATALOG_SEARCH),
                row(ProviderCapability.EDITORIAL_DISCOGRAPHY),
                row(ProviderCapability.IDENTITY),
            ),
        )
        val calls = mutableListOf<Pair<ProviderCapability, Boolean>>()
        var backCalls = 0
        compose.setContent {
            BitChordTheme {
                ProviderCapabilitiesScreen(
                    group = group,
                    configured = emptyMap(),
                    saving = false,
                    loading = false,
                    error = null,
                    contentPadding = PaddingValues(0.dp),
                    onBack = { backCalls++ },
                    onToggle = { entry, checked -> calls += entry.capability to checked },
                    onConfigure = {},
                )
            }
        }
        compose.onNodeWithTag("providers-capability-page", useUnmergedTree = true)
            .assertExists()
        compose.onAllNodesWithTag("native-settings-group-surface", useUnmergedTree = true)
            .assertCountEquals(1)
        for (capability in group.rows.map { it.entry.capability }) {
            compose.onNodeWithTag("providers-function-${capability.name}", useUnmergedTree = true)
                .assertExists()
        }
        compose.onNodeWithText("MusicBrainz").assertExists()
        compose.onNodeWithText("Discografia").assertExists()
        compose.onNodeWithText("Identidade e ISRC").assertExists()
        compose.onNodeWithText("Busca").performClick()
        compose.onNodeWithTag("apple-toolbar-back-button", useUnmergedTree = true)
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProviderCapability.CATALOG_SEARCH to true), calls)
            assertEquals(1, backCalls)
        }
    }

    @Test fun discogsCapabilityPageNeverDuplicatesGlobalConnections() {
        val key = ProviderKey("discogs")
        val cap = ProviderCapability.EDITION_METADATA
        val entry = ProviderInventoryEntry(
            key = key,
            capability = cap,
            tab = ProviderSettingsTab.CATALOG,
            displayName = "Discogs (Edições)",
            readiness = ProviderReadiness.NEEDS_CONFIGURATION,
            supportsReordering = false,
            notes = "",
        )
        val group = ProviderDisplayGroup(
            key = key,
            tab = ProviderSettingsTab.CATALOG,
            title = "Discogs",
            rows = listOf(ProviderTabRowState(
                entry = entry,
                status = ProviderCapabilityStatus(
                    capability = cap, key = key, implemented = true,
                    prerequisitesMet = false, userAuthorized = false,
                    operationalHealth = ProviderOperationalHealth.UNKNOWN,
                    effectiveEligible = false,
                ), order = 0,
            )),
        )
        var connections = 0
        compose.setContent {
            BitChordTheme {
                ProviderCapabilitiesScreen(
                    group = group,
                    configured = emptyMap(),
                    saving = false, loading = false, error = null,
                    contentPadding = PaddingValues(0.dp),
                    onBack = {},
                    onToggle = { _, _ -> },
                    onConfigure = { connections++ },
                )
            }
        }
        compose.onAllNodesWithTag("providers-discogs-token-input", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithTag("providers-function-configure", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithTag("providers-function-server", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Conexões", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, connections) }
    }

    @Test fun openSubsonicStillHasItsActualRemoteServerSettings() {
        val key = ProviderKey("opensubsonic")
        val entry = ProviderInventoryEntry(
            key = key,
            capability = ProviderCapability.PERSONAL_LIBRARY,
            tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
            displayName = "Navidrome",
            readiness = ProviderReadiness.READY,
            supportsReordering = false,
            notes = "",
        )
        val group = ProviderDisplayGroup(
            key = key,
            tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
            title = "Navidrome",
            rows = listOf(ProviderTabRowState(
                entry = entry,
                status = ProviderCapabilityStatus(
                    capability = entry.capability, key = key, implemented = true,
                    prerequisitesMet = true, userAuthorized = false,
                    operationalHealth = ProviderOperationalHealth.UNKNOWN,
                    effectiveEligible = false,
                ),
                order = 0,
            )),
        )
        var serverOpens = 0
        compose.setContent {
            BitChordTheme {
                ProviderCapabilitiesScreen(
                    group = group, configured = emptyMap(),
                    saving = false, loading = false, error = null,
                    contentPadding = PaddingValues(0.dp),
                    onBack = {},
                    onToggle = { _, _ -> },
                    onConfigure = { serverOpens++ },
                )
            }
        }
        compose.onNodeWithTag("providers-function-server", useUnmergedTree = true)
            .performClick()
        compose.onAllNodesWithText("Conexões", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, serverOpens) }
    }

    @Test fun connectionsHaveTwoIndependentNativeTokenFieldsAndNoExtensionsSection() {
        val discogs = mutableStateOf("")
        val listen = mutableStateOf("")
        val saved = mutableListOf<String>()
        compose.setContent {
            BitChordTheme {
                ProviderConnectionsCredentials(
                    showDiscogs = true,
                    showListenBrainz = true,
                    showLastFm = false,
                    showSoulseek = false,
                    discogsConfigured = false,
                    listenBrainzConfigured = true,
                    discogsToken = discogs.value,
                    listenBrainzToken = listen.value,
                    saving = false,
                    onDiscogsTokenChange = { discogs.value = it },
                    onListenBrainzTokenChange = { listen.value = it },
                    onSaveDiscogsToken = { saved += "discogs" },
                    onSaveListenBrainzToken = { saved += "listenbrainz" },
                    onOpenAccounts = {},
                    onOpenSoulseek = {},
                )
            }
        }
        compose.onNodeWithTag("providers-discogs-token-input", useUnmergedTree = true)
            .performTextInput("discogs-test-token")
        compose.onNodeWithTag("providers-listenbrainz-token-input", useUnmergedTree = true)
            .performTextInput("listen-test-token")
        compose.onNodeWithTag("providers-discogs-token-save", useUnmergedTree = true)
            .performClick()
        compose.onNodeWithTag("providers-listenbrainz-token-save", useUnmergedTree = true)
            .performClick()
        compose.onAllNodesWithTag("providers-function-configure", useUnmergedTree = true)
            .assertCountEquals(0)
        // Connections has exactly one page title, never a second card header.
        compose.onAllNodesWithText("Credenciais dos provedores", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.runOnIdle {
            assertEquals("discogs-test-token", discogs.value)
            assertEquals("listen-test-token", listen.value)
            assertEquals(listOf("discogs", "listenbrainz"), saved)
        }
    }

    @Test fun lastFmAuthenticationOpensDedicatedAccountFlow() {
        var opened = 0
        compose.setContent {
            BitChordTheme {
                ProviderConnectionsCredentials(
                    showDiscogs = false,
                    showListenBrainz = false,
                    showLastFm = true,
                    showSoulseek = false,
                    discogsConfigured = false,
                    listenBrainzConfigured = false,
                    discogsToken = "",
                    listenBrainzToken = "",
                    saving = false,
                    onDiscogsTokenChange = {},
                    onListenBrainzTokenChange = {},
                    onSaveDiscogsToken = {},
                    onSaveListenBrainzToken = {},
                    onOpenAccounts = { opened++ },
                    onOpenSoulseek = {},
                )
            }
        }
        compose.onNodeWithTag("providers-connections-lastfm", useUnmergedTree = true)
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun soulseekConnectionUsesProvidersOwnedNavigationCallback() {
        var opened = 0
        compose.setContent {
            BitChordTheme {
                ProviderConnectionsCredentials(
                    showDiscogs = false,
                    showListenBrainz = false,
                    showLastFm = false,
                    showSoulseek = true,
                    discogsConfigured = false,
                    listenBrainzConfigured = false,
                    discogsToken = "",
                    listenBrainzToken = "",
                    saving = false,
                    onDiscogsTokenChange = {},
                    onListenBrainzTokenChange = {},
                    onSaveDiscogsToken = {},
                    onSaveListenBrainzToken = {},
                    onOpenAccounts = {},
                    onOpenSoulseek = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("providers-connections-soulseek", useUnmergedTree = true)
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun remoteLibrariesAreAudioDestinationsNotFakeCapabilitySwitches() {
        assertEquals(
            listOf(
                ProviderRemoteLibraryTarget.WEBDAV,
                ProviderRemoteLibraryTarget.SMB,
                ProviderRemoteLibraryTarget.OPEN_SUBSONIC,
            ),
            providerRemoteLibraryEntries(hasOpenSubsonicProvider = false),
        )
        assertEquals(
            listOf(ProviderRemoteLibraryTarget.WEBDAV, ProviderRemoteLibraryTarget.SMB),
            providerRemoteLibraryEntries(hasOpenSubsonicProvider = true),
        )
        var opened = 0
        compose.setContent {
            BitChordTheme {
                ProviderRemoteLibraryRow(
                    target = ProviderRemoteLibraryTarget.WEBDAV,
                    number = 1,
                    configured = false,
                    onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("providers-remote-webdav", useUnmergedTree = true)
            .performClick()
        compose.onAllNodesWithTag("providers-function-configure", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Configurar", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Configuração necessária").assertExists()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun audioRemoteLibraryUsesCleanNavidromeNameAndNoTrailingSetupLabel() {
        assertEquals("Navidrome", ProviderRemoteLibraryTarget.OPEN_SUBSONIC.displayName)
        var opened = 0
        compose.setContent {
            BitChordTheme {
                ProviderRemoteLibraryRow(
                    target = ProviderRemoteLibraryTarget.OPEN_SUBSONIC,
                    number = 4,
                    configured = false,
                    onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Navidrome").assertExists()
        compose.onNodeWithText("Configuração necessária").assertExists()
        compose.onAllNodesWithText("Configurar", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Navidrome / OpenSubsonic", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("providers-remote-open_subsonic", useUnmergedTree = true)
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
