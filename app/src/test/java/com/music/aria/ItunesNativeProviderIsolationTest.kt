package com.music.aria

import com.music.aria.data.providers.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The first-party iTunes storefront is not a facade for a MusicBrainz MBID.
 * Artist search/discography are independently selectable capabilities.
 */
class ItunesNativeProviderIsolationTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val discogs = BuiltInProviderKeys.DISCOGS

    private fun gate(search: Boolean, discography: Boolean) =
        ProviderPolicyGate(ProviderCapabilityPolicy(
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
                    ProviderChoice(apple, search, 1),
                    ProviderChoice(discogs, false, 2),
                ),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                    ProviderChoice(mb, false, 0),
                    ProviderChoice(apple, discography, 1),
                    ProviderChoice(discogs, false, 2),
                ),
            ),
        ))

    @Test fun appleSearchAndDetailWorkWithMusicBrainzAndDiscogsDisabled() {
        val policy = gate(search = true, discography = true)
        val sources = setOf(mb, apple, discogs)
        assertEquals(listOf(apple),
            orderedCatalogProviders(policy, ProviderCapability.CATALOG_SEARCH, sources))
        assertEquals(listOf(apple),
            orderedCatalogProviders(policy, ProviderCapability.EDITORIAL_DISCOGRAPHY, sources))
        assertNull(policy.open(ProviderCapability.CATALOG_SEARCH, mb))
        assertNull(policy.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, mb))
        assertNotNull(policy.open(ProviderCapability.CATALOG_SEARCH, apple))
        assertNotNull(policy.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, apple))
    }

    @Test fun searchConsentDoesNotSilentlyGrantArtistDiscography() {
        val policy = gate(search = true, discography = false)
        assertNotNull(policy.open(ProviderCapability.CATALOG_SEARCH, apple))
        assertNull(policy.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, apple))
    }
}
