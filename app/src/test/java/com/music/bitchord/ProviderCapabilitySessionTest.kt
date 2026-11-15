package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.providers.MusicBrainzCapabilitySearch
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPreferenceStore
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderCapabilitySessionTest {
    private val musicBrainz = ProviderKey("musicbrainz")
    private val covers = ProviderKey("fanarttv")
    private val prefs by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "provider-capability-session-test", Context.MODE_PRIVATE,
        )
    }
    private val store by lazy { ProviderPreferenceStore(prefs) }
    private fun session() = ProviderCapabilitySession(
        store,
        listOf(
            ProviderDescriptor(musicBrainz,
                setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = ProviderReadiness.READY),
            ProviderDescriptor(covers,
                setOf(ProviderCapability.RELEASE_ARTWORK),
                readiness = ProviderReadiness.READY),
        ),
    )

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun noNetworkIsEligibleBeforeConfigurationIsLoaded(): Unit = runBlocking {
        val session = session()
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        var contacted = false
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                contacted = true
                throw IllegalStateException("network should be denied")
            },
        )
        assertEquals(ProviderCallResult.Denied, adapter.searchArtists("NewJeans"))
        assertFalse(contacted)
    }

    @Test fun cleanInstallWithNoLegacyProvidersCanEnableItsFirstProvider(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(emptyMap()))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, enabled = true, order = 0),
        )))
        assertEquals(listOf(musicBrainz),
            session.gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key })
        assertTrue(ProviderCapabilitySession(store, listOf(
            ProviderDescriptor(musicBrainz, setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = ProviderReadiness.READY),
        )).initialize(emptyMap()))
    }

    @Test fun explicitLegacyImportEnablesOnlyItsDeclaredCapability(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        assertEquals(listOf(musicBrainz),
            session.gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key })
        assertTrue(session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).isEmpty())
    }

    @Test fun turningOffTheOnlyProviderBlocksRealCatalogAdapterAndSurvivesRestart(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        var contacted = 0
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                contacted++
                MusicBrainzCatalogSearchResult.Success(
                    MusicBrainzCatalogPage(
                        listOf(MusicBrainzArtistHit(
                            "11111111-2222-4333-8444-555555555555",
                            "NewJeans", null, "KR",
                        )), 1, 0,
                    ),
                )
            },
        )
        assertTrue(adapter.searchArtists("NewJeans") is ProviderCallResult.Success)
        assertEquals(1, contacted)

        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, false, 0),
        )))
        assertEquals(ProviderCallResult.Denied, adapter.searchArtists("NewJeans"))
        assertEquals(1, contacted)

        val restored = session()
        assertTrue(restored.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0), // old legacy value must never reactivate it
            ),
        )))
        assertTrue(restored.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun providerDisableRevokesInFlightResponseWithoutAffectingArtwork(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
            ProviderCapability.RELEASE_ARTWORK to listOf(ProviderChoice(covers, true, 0)),
        )))
        val requested = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                requested.complete(Unit)
                resume.await()
                MusicBrainzCatalogSearchResult.Success(
                    MusicBrainzCatalogPage(emptyList<MusicBrainzArtistHit>(), 0, 0),
                )
            },
        )
        val result = async { adapter.searchArtists("NewJeans") }
        requested.await()
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        resume.complete(Unit)
        assertEquals(ProviderCallResult.Stale, result.await())
        assertEquals(listOf(covers),
            session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).map { it.key })
    }

    @Test fun repeatedIdenticalPreferenceDoesNotRevokeAnUnrelatedInflightRequest(): Unit = runBlocking {
        val session = session()
        val selected = listOf(ProviderChoice(musicBrainz, true, 0))
        assertTrue(session.initialize(mapOf(ProviderCapability.CATALOG_SEARCH to selected)))
        val ticket = session.gate.open(ProviderCapability.CATALOG_SEARCH, musicBrainz)
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, selected))
        assertTrue(session.gate.accept(requireNotNull(ticket)))
    }

    @Test fun failedPersistenceKeepsOptedOutCapabilityClosedAcrossOtherEdits(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
        )))
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{bad").commit()
        assertFalse(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, false, 0),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertFalse(session.update(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(covers, true, 0),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun corruptedNewPreferencesFailClosedAndAreNotOverwritten(): Unit = runBlocking {
        val bad = """{"version":999,"rows":[]}"""
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, bad).commit()
        val session = session()
        assertFalse(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertEquals(bad, prefs.getString(ProviderPreferenceStore.STORAGE_KEY, null))
    }
}
