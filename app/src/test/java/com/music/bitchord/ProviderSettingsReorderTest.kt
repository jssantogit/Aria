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

    @Test fun missingOrRepeatedKeysCannotInventAProvider() {
        val original = listOf(
            ProviderChoice(musicBrainz, true, 0),
            ProviderChoice(apple, true, 1),
        )
        assertEquals(original, reorderCapabilityChoices(original, listOf(unknown, apple, apple)))
    }
}
