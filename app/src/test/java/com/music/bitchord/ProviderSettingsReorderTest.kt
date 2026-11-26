package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityStatus
import com.music.bitchord.data.providers.ProviderInventoryEntry
import com.music.bitchord.data.providers.ProviderOperationalHealth
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderTabRowState
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.reorderCapabilityChoices
import com.music.bitchord.ui.screens.previewProviderRows
import com.music.bitchord.ui.screens.visibleProviderRows
import com.music.bitchord.ui.screens.searchProviderRows
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSettingsReorderTest {
    private val apple = ProviderKey("itunes")
    private val musicBrainz = ProviderKey("musicbrainz")
    private val unknown = ProviderKey("remote", "offline-account")

    @Test fun dragChangesOnlyExposedSlotsAndPreservesAuthorization() {
        val original = listOf(
            ProviderChoice(apple, enabled = true, order = 0),
            ProviderChoice(unknown, enabled = false, order = 1),
            ProviderChoice(musicBrainz, enabled = false, order = 2),
        )
        val sorted = reorderCapabilityChoices(original, listOf(musicBrainz, apple))
        assertEquals(listOf(musicBrainz, unknown, apple), sorted.map(ProviderChoice::key))
        assertEquals(listOf(false, false, true), sorted.map(ProviderChoice::enabled))
        assertEquals(listOf(0, 1, 2), sorted.map(ProviderChoice::order))
    }

    @Test fun cancelledOrUnchangedDragKeepsOriginalContent() {
        val original = listOf(
            ProviderChoice(musicBrainz, true, 0),
            ProviderChoice(apple, false, 1),
        )
        assertEquals(original, reorderCapabilityChoices(original, listOf(musicBrainz, apple)))
    }


    @Test fun previewKeepsNonReorderableSlotsAndSeparatesCapabilityOrders() {
        val fixed = ProviderKey("catalog", "fixed")
        val artA = ProviderKey("image", "a")
        val artB = ProviderKey("image", "b")
        fun row(key: ProviderKey, capability: ProviderCapability, draggable: Boolean, order: Int) =
            ProviderTabRowState(
                entry = ProviderInventoryEntry(
                    key = key,
                    capability = capability,
                    tab = ProviderSettingsTab.CATALOG,
                    displayName = key.providerId + key.instanceId,
                    readiness = ProviderReadiness.READY,
                    supportsReordering = draggable,
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
                order = order,
            )
        val before = listOf(
            row(apple, ProviderCapability.CATALOG_SEARCH, true, 0),
            row(fixed, ProviderCapability.CATALOG_SEARCH, false, 1),
            row(musicBrainz, ProviderCapability.CATALOG_SEARCH, true, 2),
            row(artA, ProviderCapability.RELEASE_ARTWORK, true, 0),
            row(artB, ProviderCapability.RELEASE_ARTWORK, true, 1),
        )
        val preview = previewProviderRows(before, mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz, apple),
            ProviderCapability.RELEASE_ARTWORK to listOf(artB, artA),
        ))
        assertEquals(
            listOf(musicBrainz, fixed, apple, artB, artA),
            preview.map { it.entry.key },
        )
        assertEquals(before, previewProviderRows(before, emptyMap()))
        assertEquals(listOf(artA, artB),
            previewProviderRows(before, mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz, apple),
            )).takeLast(2).map { it.entry.key })
    }

    @Test fun organizeShowsOnlyActionableScopesAndNormalModeKeepsInventory() {
        fun row(key: ProviderKey, capability: ProviderCapability, draggable: Boolean) =
            ProviderTabRowState(
                entry = ProviderInventoryEntry(
                    key = key,
                    capability = capability,
                    tab = ProviderSettingsTab.CATALOG,
                    displayName = key.providerId,
                    readiness = ProviderReadiness.READY,
                    supportsReordering = draggable,
                    notes = "",
                ),
                status = ProviderCapabilityStatus(
                    capability = capability,
                    key = key,
                    implemented = true,
                    prerequisitesMet = true,
                    userAuthorized = true,
                    operationalHealth = ProviderOperationalHealth.UNKNOWN,
                    effectiveEligible = true,
                ),
                order = 0,
            )
        val fixed = ProviderKey("catalog", "fixed")
        val standalone = ProviderKey("catalog", "standalone")
        val rows = listOf(
            row(apple, ProviderCapability.CATALOG_SEARCH, true),
            row(fixed, ProviderCapability.CATALOG_SEARCH, false),
            row(musicBrainz, ProviderCapability.CATALOG_SEARCH, true),
            row(standalone, ProviderCapability.IDENTITY, false),
            row(ProviderKey("itunes"), ProviderCapability.RELEASE_ARTWORK, true),
        )
        assertEquals(rows, visibleProviderRows(rows, editing = false))
        assertEquals(listOf(apple, musicBrainz),
            visibleProviderRows(rows, editing = true).map { it.entry.key })
        assertEquals(emptyList<ProviderTabRowState>(),
            visibleProviderRows(rows.take(1), editing = true))
    }

    @Test fun searchWithinCategoryPreservesVisibleOrderAndNeverMutatesRows() {
        fun row(name: String, key: ProviderKey) = ProviderTabRowState(
            entry = ProviderInventoryEntry(
                key = key,
                capability = ProviderCapability.CATALOG_SEARCH,
                tab = ProviderSettingsTab.CATALOG,
                displayName = name,
                readiness = ProviderReadiness.READY,
                supportsReordering = true,
                notes = "",
            ),
            status = ProviderCapabilityStatus(
                capability = ProviderCapability.CATALOG_SEARCH,
                key = key,
                implemented = true,
                prerequisitesMet = true,
                userAuthorized = true,
                operationalHealth = ProviderOperationalHealth.UNKNOWN,
                effectiveEligible = true,
            ),
            order = 0,
        )
        val all = listOf(
            row("MusicBrainz (Busca)", musicBrainz),
            row("Apple / iTunes (Busca)", apple),
            row("AcoustID", ProviderKey("acoustid")),
        )
        assertEquals(all, searchProviderRows(all, ""))
        assertEquals(all.take(2), searchProviderRows(all, " BUSCA "))
        assertEquals(listOf(all[1]), searchProviderRows(all, "itunes"))
        assertEquals(emptyList<ProviderTabRowState>(), searchProviderRows(all, "sem resultado"))
        assertEquals(3, all.size)
    }

    @Test fun missingOrRepeatedKeysCannotInventAProvider() {
        val original = listOf(
            ProviderChoice(musicBrainz, true, 0),
            ProviderChoice(apple, true, 1),
        )
        assertEquals(original, reorderCapabilityChoices(original, listOf(unknown, apple, apple)))
    }
}
