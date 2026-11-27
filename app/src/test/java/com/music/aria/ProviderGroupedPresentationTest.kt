package com.music.aria

import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderCapabilityStatus
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderInventoryEntry
import com.music.aria.data.providers.ProviderKey
import com.music.aria.data.providers.ProviderOperationalHealth
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.data.providers.ProviderSettingsTab
import com.music.aria.data.providers.ProviderTabRowState
import com.music.aria.ui.screens.groupProviderRows
import com.music.aria.ui.screens.orderProviderGroups
import com.music.aria.ui.screens.planProviderGroupToggle
import com.music.aria.ui.screens.presentableProviderGroups
import com.music.aria.ui.screens.presentableProviderFunctions
import com.music.aria.ui.screens.providerGroupAllEnabled
import com.music.aria.ui.screens.providerGroupDraftOrders
import com.music.aria.ui.screens.providerGroupWarning
import com.music.aria.ui.screens.reorderCapabilityChoices
import com.music.aria.ui.screens.searchProviderGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGroupedPresentationTest {
    private val mb = ProviderKey("musicbrainz")
    private val apple = ProviderKey("itunes")
    private val discogs = ProviderKey("discogs")
    private val search = ProviderCapability.CATALOG_SEARCH
    private val discography = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val identity = ProviderCapability.IDENTITY

    private fun row(
        key: ProviderKey,
        capability: ProviderCapability,
        title: String,
        readiness: ProviderReadiness = ProviderReadiness.READY,
        tab: ProviderSettingsTab = ProviderSettingsTab.CATALOG,
    ) = ProviderTabRowState(
        entry = ProviderInventoryEntry(
            key = key,
            capability = capability,
            tab = tab,
            displayName = title,
            readiness = readiness,
            supportsReordering = capability != identity,
            notes = "",
        ),
        status = ProviderCapabilityStatus(
            capability = capability,
            key = key,
            implemented = readiness != ProviderReadiness.NOT_IMPLEMENTED,
            prerequisitesMet = readiness == ProviderReadiness.READY,
            userAuthorized = false,
            operationalHealth = ProviderOperationalHealth.UNKNOWN,
            effectiveEligible = false,
        ),
        order = 0,
    )

    private val catalog = listOf(
        row(mb, search, "MusicBrainz (Busca)"),
        row(apple, search, "Apple / iTunes (Busca)"),
        row(mb, discography, "MusicBrainz (Discografia)"),
        row(apple, discography, "Apple / iTunes (Discografia)"),
        row(mb, identity, "MusicBrainz (Identidade / ISRC)"),
        row(discogs, ProviderCapability.EDITION_METADATA, "Discogs (Edições)",
            readiness = ProviderReadiness.NEEDS_CONFIGURATION),
    )

    @Test fun catalogShowsOneVendorPerInstanceWithoutFunctionSuffixes() {
        val groups = groupProviderRows(catalog)
        assertEquals(listOf("MusicBrainz", "Apple / iTunes", "Discogs"),
            groups.map { it.title })
        assertEquals(listOf(3, 2, 1), groups.map { it.rows.size })
        assertEquals(listOf(search, discography, identity),
            groups.first().rows.map { it.entry.capability })
        assertEquals(groups.take(1), searchProviderGroups(groups, " ISRC "))
        assertEquals(groups.take(2), searchProviderGroups(groups, "busca"))
        assertEquals(groups, searchProviderGroups(groups, ""))
        assertEquals(catalog.size, groups.sumOf { it.rows.size })
    }

    @Test fun unimplementedOnlyProvidersAreHiddenButConfigurableSourcesRemainVisible() {
        val acoustid = ProviderKey("acoustid")
        val rows = catalog + listOf(
            row(acoustid, ProviderCapability.ACOUSTIC_IDENTIFICATION,
                "AcoustID", readiness = ProviderReadiness.NOT_IMPLEMENTED),
            row(mb, ProviderCapability.ACOUSTIC_IDENTIFICATION,
                "MusicBrainz (Identificação)", readiness = ProviderReadiness.NOT_IMPLEMENTED),
        )
        val fullGroups = groupProviderRows(rows)
        val visible = presentableProviderGroups(fullGroups)
        assertEquals(listOf("MusicBrainz", "Apple / iTunes", "Discogs"),
            visible.map { it.title })
        assertEquals(listOf(search, discography, identity),
            presentableProviderFunctions(visible.first()).map { it.entry.capability })
        // Preserve the full internal group for authorization revocation and future
        // readiness changes; filtering must NEVER mutate ProviderRuntime's input.
        assertEquals(4, fullGroups.first().rows.size)
        assertEquals(4, fullGroups.size)
        assertTrue(searchProviderGroups(visible, "Identificação").isEmpty())
        assertEquals("Discogs", visible.last().title)
    }

    @Test fun onlyWithinSameTabAndExactProviderInstanceAreCapabilitiesGrouped() {
        val serverA = ProviderKey("opensubsonic", "account-a")
        val serverB = ProviderKey("opensubsonic", "account-b")
        val entries = listOf(
            row(serverA, ProviderCapability.PERSONAL_LIBRARY, "Navidrome (Biblioteca)",
                tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS),
            row(serverA, ProviderCapability.AUDIO_PLAYBACK, "Navidrome (Reprodução)",
                tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS),
            row(serverB, ProviderCapability.PERSONAL_LIBRARY, "Navidrome (Biblioteca)",
                tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS),
            row(apple, ProviderCapability.RELEASE_ARTWORK, "Apple / iTunes (Capas)",
                tab = ProviderSettingsTab.ARTWORK),
            row(apple, ProviderCapability.CATALOG_SEARCH, "Apple / iTunes (Busca)",
                tab = ProviderSettingsTab.CATALOG),
        )
        assertEquals(listOf(serverA, serverB, apple, apple),
            groupProviderRows(entries).map { it.key })
        assertEquals(
            listOf(ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
                ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
                ProviderSettingsTab.ARTWORK, ProviderSettingsTab.CATALOG),
            groupProviderRows(entries).map { it.tab },
        )
        assertEquals(2, groupProviderRows(entries).first().rows.size)
        assertEquals(listOf(apple), groupProviderRows(entries.takeLast(1)).map { it.key })
        assertEquals(1, groupProviderRows(entries).last().rows.size)
        val custom = ProviderKey("addon", "extension-a")
        assertEquals("Minha Fonte (Especial)",
            groupProviderRows(listOf(row(custom, ProviderCapability.EXTENSION_PLAYBACK,
                "Minha Fonte (Especial)", tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS)
            )).single().title)
    }

    @Test fun enablingVendorIsAtomicAndPreservesUnrelatedCapabilitiesAndRanks() {
        val group = groupProviderRows(catalog).first()
        val configured = mapOf(
            search to listOf(ProviderChoice(apple, true, 0), ProviderChoice(mb, false, 1)),
            discography to listOf(ProviderChoice(mb, false, 0), ProviderChoice(apple, true, 1)),
            identity to listOf(ProviderChoice(mb, false, 0)),
        )
        val changes = planProviderGroupToggle(group, configured, enabled = true)
        assertEquals(setOf(search, discography, identity), changes.keys)
        assertEquals(listOf(apple, mb), changes.getValue(search).map { it.key })
        assertEquals(listOf(0, 1), changes.getValue(search).map { it.order })
        assertTrue(changes.values.flatten().all { it.enabled })
        assertFalse(providerGroupAllEnabled(group, configured))
        assertTrue(providerGroupAllEnabled(group, configured + changes))
        assertEquals(emptyMap<ProviderCapability, List<ProviderChoice>>(),
            planProviderGroupToggle(group, configured + changes, enabled = true))
        assertFalse(configured.getValue(search)[1].enabled)
        assertTrue(configured.getValue(search)[0].enabled)
    }

    @Test fun mixedAuthorizationsAreExplicitAndDisablingRevokesEvenUnreadyPrivileges() {
        val mixed = groupProviderRows(listOf(
            row(mb, search, "MusicBrainz (Busca)"),
            row(mb, identity, "MusicBrainz (Identidade)",
                readiness = ProviderReadiness.NEEDS_CONFIGURATION),
            row(mb, discography, "MusicBrainz (Discografia)"),
        )).single()
        val configured = mapOf(
            search to listOf(ProviderChoice(mb, true, 0)),
            identity to listOf(ProviderChoice(mb, true, 0)),
            discography to listOf(ProviderChoice(mb, false, 0)),
        )
        assertEquals("Parcialmente ativado", providerGroupWarning(mixed, configured))
        val on = planProviderGroupToggle(mixed, configured, enabled = true)
        assertEquals(setOf(discography), on.keys)
        assertTrue(providerGroupAllEnabled(mixed, configured + on))
        val off = planProviderGroupToggle(mixed, configured + on, enabled = false)
        assertEquals(setOf(search, identity, discography), off.keys)
        assertTrue(off.values.flatten().none { it.enabled })
        val blocked = groupProviderRows(listOf(
            row(discogs, ProviderCapability.EDITION_METADATA, "Discogs",
                readiness = ProviderReadiness.NEEDS_CONFIGURATION),
        )).single()
        assertEquals(emptyMap<ProviderCapability, List<ProviderChoice>>(),
            planProviderGroupToggle(blocked, emptyMap(), enabled = true))
        assertEquals("Configuração necessária", providerGroupWarning(blocked, emptyMap()))
    }

    @Test fun singleDragKeepsPrioritiesIndependentAndFixedSlotsUntouched() {
        val groups = groupProviderRows(catalog)
        val reordered = orderProviderGroups(groups, listOf(apple, mb, discogs))
        assertEquals(listOf(apple, mb, discogs), reordered.map { it.key })
        val frozen = ProviderKey("legacy", "fixed")
        val drafts = mapOf(
            search to listOf(mb, apple),
            discography to listOf(mb, apple),
            identity to listOf(mb),
        )
        val updated = providerGroupDraftOrders(reordered.map { it.key }, drafts)
        assertEquals(listOf(apple, mb), updated.getValue(search))
        assertEquals(listOf(apple, mb), updated.getValue(discography))
        assertEquals(listOf(mb), updated.getValue(identity))
        val original = listOf(
            ProviderChoice(mb, false, 0),
            ProviderChoice(frozen, true, 1),
            ProviderChoice(apple, true, 2),
        )
        val saved = reorderCapabilityChoices(original, updated.getValue(search))
        assertEquals(listOf(apple, frozen, mb), saved.map { it.key })
        assertEquals(listOf(true, true, false), saved.map { it.enabled })
        assertEquals(listOf(0, 1, 2), saved.map { it.order })
    }
}
