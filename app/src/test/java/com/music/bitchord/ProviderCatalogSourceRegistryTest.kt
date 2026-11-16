package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogSourceRegistryTest {
    @Test fun addonDownloadRevocationPreservesPlaybackAndCannotUseLegacyEnabledFallback() {
        val key = ProviderKey("addon", "addon-one")
        val provider = ProviderDescriptor(
            key = key,
            capabilities = setOf(
                ProviderCapability.EXTENSION_PLAYBACK,
                ProviderCapability.DOWNLOAD,
            ),
            readiness = ProviderReadiness.READY,
        )
        fun policy(download: Boolean) = ProviderCapabilityPolicy(
            listOf(provider), mapOf(
                ProviderCapability.EXTENSION_PLAYBACK to listOf(ProviderChoice(key, true, 0)),
                ProviderCapability.DOWNLOAD to listOf(ProviderChoice(key, download, 0)),
            ),
        )
        val addon = SourceConfig(
            id = "addon-one",
            kind = SourceKind.ADDON,
            baseUrl = "https://example.org/manifest.json",
            enabled = true,
            allowDownloads = true,
        )
        val gate = ProviderPolicyGate(policy(false))
        assertTrue(SourceRegistry.downloadEligibleConfigs(listOf(addon), gate).isEmpty())
        assertTrue(gate.open(ProviderCapability.EXTENSION_PLAYBACK, key) != null)
        gate.replace(policy(true))
        assertEquals(listOf(addon), SourceRegistry.downloadEligibleConfigs(listOf(addon), gate))
        assertTrue(SourceRegistry.downloadEligibleConfigs(listOf(addon), null).isEmpty())
        assertTrue(SourceRegistry.downloadEligibleConfigs(
            listOf(addon.copy(allowDownloads = false)), gate,
        ).isEmpty())
    }
}
