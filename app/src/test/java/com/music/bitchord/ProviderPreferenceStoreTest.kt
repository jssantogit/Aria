package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderPreferenceStoreTest {
    private val apple = ProviderKey("itunes")
    private val nasA = ProviderKey("opensubsonic", "nas-a")
    private val nasB = ProviderKey("opensubsonic", "nas-b")
    private val preferences by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "provider-preference-store-test", Context.MODE_PRIVATE,
        )
    }
    private val store by lazy { ProviderPreferenceStore(preferences) }

    @Before fun reset() {
        preferences.edit().clear().commit()
    }

    @Test fun freshInstallIsUnconfiguredRatherThanImplicitlyEnablingAnyProvider() {
        assertEquals(ProviderPreferenceStore.Read.Missing, store.read())
    }

    @Test fun disablingTheOnlyProviderSurvivesReloadWithoutImplicitFallback() = runBlocking {
        assertTrue(store.replaceCapability(
            ProviderCapability.CATALOG_SEARCH,
            listOf(ProviderChoice(apple, enabled = false, order = 0)),
        ))
        val saved = ProviderPreferenceStore(preferences).read()
        assertTrue(saved is ProviderPreferenceStore.Read.Loaded)
        saved as ProviderPreferenceStore.Read.Loaded
        assertEquals(listOf(ProviderChoice(apple, false, 0)),
            saved.choices.getValue(ProviderCapability.CATALOG_SEARCH))
        assertFalse(saved.choices.containsKey(ProviderCapability.RELEASE_ARTWORK))
    }

    @Test fun editingOneCapabilityCannotClobberOtherCapabilityOrServerInstance() = runBlocking {
        assertTrue(store.replaceCapability(ProviderCapability.AUDIO_PLAYBACK, listOf(
            ProviderChoice(nasA, true, 20),
            ProviderChoice(nasB, false, 10),
        )))
        assertTrue(store.replaceCapability(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(apple, true, 0),
        )))
        assertTrue(store.replaceCapability(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(apple, false, 0),
        )))
        val choices = (store.read() as ProviderPreferenceStore.Read.Loaded).choices
        assertEquals(listOf(ProviderChoice(nasA, true, 20), ProviderChoice(nasB, false, 10)),
            choices.getValue(ProviderCapability.AUDIO_PLAYBACK))
        assertEquals(listOf(ProviderChoice(apple, false, 0)),
            choices.getValue(ProviderCapability.RELEASE_ARTWORK))
    }

    @Test fun unknownFutureCapabilitySurvivesAnEditToKnownCapability() = runBlocking {
        preferences.edit().putString(ProviderPreferenceStore.STORAGE_KEY,
            """{"version":1,"rows":[{"capability":"FUTURE_CAPABILITY","providerId":"future","instanceId":"default","enabled":false,"priority":3}]}"""
        ).commit()
        assertTrue(store.replaceCapability(ProviderCapability.LYRICS_SYNCED, listOf(
            ProviderChoice(ProviderKey("lrclib"), true, 0),
        )))
        assertTrue(preferences.getString(ProviderPreferenceStore.STORAGE_KEY, "")
            ?.contains("FUTURE_CAPABILITY") == true)
    }

    @Test fun corruptedPreferencesAreNotSilentlyResetOrOverwritten() = runBlocking {
        preferences.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{").commit()
        assertTrue(store.read() is ProviderPreferenceStore.Read.Unreadable)
        assertFalse(store.replaceCapability(ProviderCapability.CATALOG_SEARCH, emptyList()))
        assertEquals("{", preferences.getString(ProviderPreferenceStore.STORAGE_KEY, null))
    }

    @Test fun newerSchemaIsNotOverwrittenByOlderAppVersion() = runBlocking {
        val future = """{"version":99,"rows":[]}"""
        preferences.edit().putString(ProviderPreferenceStore.STORAGE_KEY, future).commit()
        assertTrue(store.read() is ProviderPreferenceStore.Read.Unreadable)
        assertFalse(store.replaceCapability(ProviderCapability.CATALOG_SEARCH, emptyList()))
        assertEquals(future, preferences.getString(ProviderPreferenceStore.STORAGE_KEY, null))
    }

    @Test fun explicitEmptyCategoryIsDistinctFromUnmigratedCategory() = runBlocking {
        assertTrue(store.replaceCapability(ProviderCapability.CATALOG_SEARCH, emptyList()))
        val saved = store.read() as ProviderPreferenceStore.Read.Loaded
        assertTrue(saved.choices.containsKey(ProviderCapability.CATALOG_SEARCH))
        assertEquals(emptyList<ProviderChoice>(),
            saved.choices.getValue(ProviderCapability.CATALOG_SEARCH))
        assertFalse(saved.choices.containsKey(ProviderCapability.RELEASE_ARTWORK))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateSelectionIsRejectedBeforeWriting() = runBlocking {
        store.replaceCapability(ProviderCapability.AUDIO_PLAYBACK, listOf(
            ProviderChoice(nasA, true, 0), ProviderChoice(nasA, false, 1),
        ))
    }
}
