package com.music.aria

import com.music.aria.data.providers.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsNativeProviderIsolationTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val discogs = BuiltInProviderKeys.DISCOGS
    private val catalogue = setOf(mb, apple, discogs)

    @Test fun discogsOwnsBothBrowseCapabilitiesWithMusicBrainzAndAppleDisabled() {
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(
                BuiltInProviderDescriptors.musicBrainz(),
                BuiltInProviderDescriptors.itunes(ProviderReadiness.READY),
                BuiltInProviderDescriptors.discogs(
                    tokenConfigured = true, implemented = true,
                ),
            ),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(mb, false, 0),
                    ProviderChoice(apple, false, 1),
                    ProviderChoice(discogs, true, 2),
                ),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                    ProviderChoice(mb, false, 0),
                    ProviderChoice(apple, false, 1),
                    ProviderChoice(discogs, true, 2),
                ),
                ProviderCapability.EDITION_METADATA to listOf(
                    ProviderChoice(discogs, false, 0),
                ),
            ),
        ))
        assertEquals(listOf(discogs), orderedCatalogProviders(
            gate, ProviderCapability.CATALOG_SEARCH, catalogue,
        ))
        assertEquals(listOf(discogs), orderedCatalogProviders(
            gate, ProviderCapability.EDITORIAL_DISCOGRAPHY, catalogue,
        ))
        assertNull(gate.open(ProviderCapability.CATALOG_SEARCH, mb))
        assertNull(gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, mb))
        assertNull(gate.open(ProviderCapability.EDITION_METADATA, discogs))
        assertNotNull(gate.open(ProviderCapability.CATALOG_SEARCH, discogs))
        assertNotNull(gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, discogs))
    }

    @Test fun publicDiscographyMayBeReadyWithoutSearchTokenButSearchMayNot() {
        val entries = ProviderCapabilityRegistry.inventory(
            discogsImplemented = true,
            discogsTokenConfigured = false,
        ).filter { it.key == discogs }
        assertEquals(ProviderReadiness.NEEDS_CONFIGURATION,
            entries.single { it.capability == ProviderCapability.CATALOG_SEARCH }.readiness)
        assertEquals(ProviderReadiness.READY,
            entries.single {
                it.capability == ProviderCapability.EDITORIAL_DISCOGRAPHY
            }.readiness)
        assertTrue(entries.all { it.tab == ProviderSettingsTab.CATALOG })
        val enabled = ProviderCapabilityRegistry.inventory(
            discogsImplemented = true,
            discogsTokenConfigured = true,
        ).filter { it.key == discogs &&
            it.capability == ProviderCapability.CATALOG_SEARCH }
        assertEquals(ProviderReadiness.READY, enabled.single().readiness)
    }
}
