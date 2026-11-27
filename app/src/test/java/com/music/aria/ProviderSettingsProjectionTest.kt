package com.music.aria

import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderCapabilityPolicy
import com.music.aria.data.providers.ProviderCapabilityRegistry
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderInventoryEntry
import com.music.aria.data.providers.ProviderKey
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.data.providers.ProviderSessionSnapshot
import com.music.aria.data.providers.ProviderSettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless acceptance checks for the approved Settings > Provedores interface.
 * UI categories are not priority domains; only exact capabilities are.
 */
class ProviderSettingsProjectionTest {
    private val apple = ProviderKey("itunes")
    private val mb = ProviderKey("musicbrainz")

    private fun entry(
        key: ProviderKey,
        capability: ProviderCapability,
        label: String,
        readiness: ProviderReadiness = ProviderReadiness.READY,
        reorderable: Boolean = true,
        tab: ProviderSettingsTab = ProviderSettingsTab.CATALOG,
    ) = ProviderInventoryEntry(
        key = key,
        capability = capability,
        tab = tab,
        displayName = label,
        readiness = readiness,
        supportsReordering = reorderable,
        notes = "",
    )

    private fun project(
        entries: List<ProviderInventoryEntry>,
        configured: Map<ProviderCapability, List<ProviderChoice>>,
        tab: ProviderSettingsTab = ProviderSettingsTab.CATALOG,
    ) = ProviderCapabilityRegistry.projectTab(
        tab = tab,
        entries = entries,
        snapshot = ProviderSessionSnapshot(configured = configured),
        policy = ProviderCapabilityPolicy(
            descriptors = ProviderCapabilityRegistry.descriptorsFrom(entries),
            choices = configured,
        ),
    )

    @Test fun preferenceOrderNeverInterleavesDifferentCapabilitiesInsideOneCategory() {
        val entries = listOf(
            entry(mb, ProviderCapability.CATALOG_SEARCH, "MB Search"),
            entry(mb, ProviderCapability.EDITORIAL_DISCOGRAPHY, "MB Discography"),
            entry(apple, ProviderCapability.CATALOG_SEARCH, "Apple Search"),
            entry(apple, ProviderCapability.EDITORIAL_DISCOGRAPHY, "Apple Discography"),
            entry(mb, ProviderCapability.IDENTITY, "MB Identity", reorderable = false),
        )
        val configured = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(mb, enabled = true, order = 1),
                ProviderChoice(apple, enabled = true, order = 0),
            ),
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(mb, enabled = true, order = 0),
                ProviderChoice(apple, enabled = true, order = 1),
            ),
            ProviderCapability.IDENTITY to listOf(
                ProviderChoice(mb, enabled = true, order = 0),
            ),
        )
        val rows = project(entries, configured)
        assertEquals(
            listOf(
                "Apple Search", "MB Search",
                "MB Discography", "Apple Discography",
                "MB Identity",
            ),
            rows.map { it.entry.displayName },
        )
        assertEquals(
            listOf(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                ProviderCapability.IDENTITY,
            ),
            rows.map { it.entry.capability },
        )
    }

    @Test fun switchAuthorizationIsScopedToCapabilityEvenForSameProvider() {
        val entries = listOf(
            entry(mb, ProviderCapability.CATALOG_SEARCH, "MusicBrainz Search"),
            entry(mb, ProviderCapability.IDENTITY, "MusicBrainz Identity", reorderable = false),
        )
        val rows = project(entries, mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(mb, false, 0)),
            ProviderCapability.IDENTITY to listOf(ProviderChoice(mb, true, 0)),
        ))
        val search = rows.single { it.entry.capability == ProviderCapability.CATALOG_SEARCH }
        val identity = rows.single { it.entry.capability == ProviderCapability.IDENTITY }
        assertFalse(search.status.userAuthorized)
        assertFalse(search.status.effectiveEligible)
        assertTrue(identity.status.userAuthorized)
        assertTrue(identity.status.effectiveEligible)
    }

    @Test fun authorizedButUnconfiguredProviderDoesNotClaimItCanRun() {
        val entries = listOf(
            entry(apple, ProviderCapability.CATALOG_SEARCH, "Apple Search",
                readiness = ProviderReadiness.NEEDS_CONFIGURATION),
            entry(apple, ProviderCapability.EDITORIAL_DISCOGRAPHY, "Apple Discography"),
        )
        val rows = project(entries, mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(apple, true, 0)),
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(ProviderChoice(apple, true, 0)),
        ))
        val search = rows.single { it.entry.capability == ProviderCapability.CATALOG_SEARCH }
        val editorial = rows.single { it.entry.capability == ProviderCapability.EDITORIAL_DISCOGRAPHY }
        assertTrue(search.status.userAuthorized)
        assertFalse(search.status.prerequisitesMet)
        assertFalse(search.status.effectiveEligible)
        assertTrue(editorial.status.prerequisitesMet)
        assertTrue(editorial.status.effectiveEligible)
    }
}
