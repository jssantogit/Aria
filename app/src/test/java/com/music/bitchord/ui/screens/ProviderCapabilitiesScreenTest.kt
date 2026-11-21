package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
                    discogsToken = "",
                    onDiscogsTokenChange = {},
                    onSaveDiscogsToken = {},
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
    @Test fun discogsSettingsEmbedNativeMaskedInputAndSaveWithoutOpeningLegacyModal() {
        val key = ProviderKey("discogs")
        val capability = ProviderCapability.EDITION_METADATA
        val entry = ProviderInventoryEntry(
            key = key,
            capability = capability,
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
                    capability = capability,
                    key = key,
                    implemented = true,
                    prerequisitesMet = false,
                    userAuthorized = false,
                    operationalHealth = ProviderOperationalHealth.UNKNOWN,
                    effectiveEligible = false,
                ),
                order = 0,
            )),
        )
        val credential = mutableStateOf("")
        val saved = mutableListOf<String>()
        compose.setContent {
            BitChordTheme {
                ProviderCapabilitiesScreen(
                    group = group,
                    configured = emptyMap(),
                    saving = false,
                    loading = false,
                    error = null,
                    contentPadding = PaddingValues(0.dp),
                    onBack = {},
                    onToggle = { _, _ -> },
                    onConfigure = {},
                    discogsToken = credential.value,
                    onDiscogsTokenChange = { credential.value = it },
                    onSaveDiscogsToken = { saved += credential.value },
                )
            }
        }
        compose.onAllNodesWithTag("native-settings-group-surface", useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithTag("providers-function-configure", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("providers-discogs-token-input", useUnmergedTree = true)
            .performTextInput("test-credential")
        compose.onNodeWithTag("providers-discogs-token-save", useUnmergedTree = true)
            .performClick()
        compose.runOnIdle {
            assertEquals("test-credential", credential.value)
            assertEquals(listOf("test-credential"), saved)
        }
    }

}
