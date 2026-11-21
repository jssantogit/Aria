package com.music.bitchord

import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.reorderCapabilityChoices
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

    @Test fun missingOrRepeatedKeysCannotInventAProvider() {
        val original = listOf(
            ProviderChoice(musicBrainz, true, 0),
            ProviderChoice(apple, true, 1),
        )
        assertEquals(original, reorderCapabilityChoices(original, listOf(unknown, apple, apple)))
    }
}
