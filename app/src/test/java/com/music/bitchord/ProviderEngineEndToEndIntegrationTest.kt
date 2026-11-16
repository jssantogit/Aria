package com.music.bitchord

import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.allowedCanonicalLyricsSources
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveResponse
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtStore
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtCacheEntry
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCachedSearchRepository
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.PolicyGuardedCoverArtArchiveTransport
import com.music.bitchord.data.providers.ProviderAuthorityCoordinator
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPreferenceStore
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderSingleFlight
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.ScrobbleManager
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * End-to-end integration proof for Issue #218 across:
 * - Real Room database + MusicBrainzCatalogIndex + MusicBrainzCachedSearchRepository
 * - Real SharedPreferences + ProviderPreferenceStore + ProviderCapabilitySession
 * - ProviderAuthorityCoordinator + allowedCanonicalLyricsSources
 * - ProviderCapabilityRegistry honest NOT_IMPLEMENTED vs READY inventory
 * - Real MusicBrainzClient HTTP boundary + CoverArtArchive + AppleArtistArtRepository + ScrobbleManager + ListenBrainzManager
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderEngineEndToEndIntegrationTest {
    private val prefs by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "provider-engine-e2e-test", Context.MODE_PRIVATE,
        )
    }

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
        AppleArtistArtRepository.clearCacheForTests()
    }

    @After
    fun restoreTestHooks() {
        AppleArtistArtRepository.setPolicyGateForTests(null)
        AppleArtistArtRepository.setHttpGetForTests(null)
        AppleArtistArtRepository.clearCacheForTests()
        ListenBrainzManager.policyGateForTests = null
    }

    @Test
    fun migratedScrobblingIsDenyByDefaultAndPreservesExplicitConfiguredOptIn(): Unit = runBlocking {
        val session = ProviderCapabilitySession(
            ProviderPreferenceStore(prefs),
            ProviderCapabilityRegistry.descriptorsFrom(ProviderCapabilityRegistry.inventory(
                lastfmConfigured = true,
                listenBrainzConfigured = true,
            )),
        )
        val coordinator = ProviderAuthorityCoordinator(session)
        assertTrue(coordinator.migrateOnce(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = emptyList(),
            lastfmConfigured = true,
            listenBrainzConfigured = true,
            lastfmEnabled = true,
            listenBrainzEnabled = false,
        ))
        assertTrue(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM) != null)
        assertNull(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LISTENBRAINZ))
    }

    @Test
    fun listeningAccountBecomesReadyAfterLoginWithoutReenablingExplicitOptOut(): Unit = runBlocking {
        val session = ProviderCapabilitySession(
            ProviderPreferenceStore(prefs),
            ProviderCapabilityRegistry.descriptorsFrom(ProviderCapabilityRegistry.inventory()),
        )
        val coordinator = ProviderAuthorityCoordinator(session)
        assertTrue(coordinator.migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            lastfmEnabled = true, listenBrainzEnabled = false,
        ))
        assertNull(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM))
        assertTrue(coordinator.updateListeningSettings(
            lastfmConfigured = true, listenBrainzConfigured = false,
            lastfmEnabled = true, listenBrainzEnabled = false,
        ))
        assertTrue(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM) != null)
        assertNull(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LISTENBRAINZ))
        assertTrue(coordinator.updateListeningSettings(
            lastfmConfigured = true, listenBrainzConfigured = true,
            lastfmEnabled = false, listenBrainzEnabled = false,
        ))
        assertNull(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM))
        assertNull(session.gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LISTENBRAINZ))
    }

    @Test
    fun extensionPriorityRestoresFromCanonicalPreferencesAfterRestart(): Unit = runBlocking {
        val first = SourceConfig(
            id = "first", kind = SourceKind.ADDON,
            baseUrl = "https://first.example/manifest.json",
        )
        val second = SourceConfig(
            id = "second", kind = SourceKind.ADDON,
            baseUrl = "https://second.example/manifest.json",
        )
        val sources = listOf(first, second)
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(sources = sources),
        )
        val session = ProviderCapabilitySession(ProviderPreferenceStore(prefs), descriptors)
        val coordinator = ProviderAuthorityCoordinator(session)
        assertTrue(coordinator.migrateOnce(emptyList(), emptySet(), sources))
        assertTrue(coordinator.reorderExtensions(listOf(second.id, first.id), sources))
        assertEquals(listOf("second", "first"),
            coordinator.currentExtensionsProjection(sources).map { it.id })
        val restarted = ProviderCapabilitySession(ProviderPreferenceStore(prefs), descriptors)
        val restored = ProviderAuthorityCoordinator(restarted)
        assertTrue(restored.migrateOnce(emptyList(), emptySet(), sources))
        assertEquals(listOf("second", "first"),
            restored.currentExtensionsProjection(sources).map { it.id })
    }

    @Test
    fun newExtensionBecomesEligibleAndPreviouslyDisabledInstanceStaysOffAfterRefresh(): Unit = runBlocking {
        val session = ProviderCapabilitySession(
            ProviderPreferenceStore(prefs),
            ProviderCapabilityRegistry.descriptorsFrom(ProviderCapabilityRegistry.inventory()),
        )
        val coordinator = ProviderAuthorityCoordinator(session)
        assertTrue(coordinator.migrateOnce(emptyList(), emptySet(), emptyList()))
        val addon = SourceConfig(
            id = "later-addon", kind = SourceKind.ADDON,
            baseUrl = "https://extension.example/manifest.json",
            enabled = true,
        )
        coordinator.onSourcesChanged(listOf(addon))
        val key = com.music.bitchord.data.providers.ProviderKey("addon", addon.id)
        assertTrue(session.gate.open(ProviderCapability.EXTENSION_PLAYBACK, key) != null)
        assertTrue(coordinator.setExtensionEnabled(addon.id, false, listOf(addon)))
        coordinator.onSourcesChanged(emptyList())
        coordinator.onSourcesChanged(listOf(addon))
        assertNull(session.gate.open(ProviderCapability.EXTENSION_PLAYBACK, key))
    }

    @Test
    fun endToEndSingleAuthorityMigrationPreservesOptOutsExcludesRetiredAndSurvivesRestart(): Unit = runBlocking {
        val store = ProviderPreferenceStore(prefs)
        val addonA = SourceConfig(
            id = "addon-1",
            kind = SourceKind.ADDON,
            baseUrl = "https://private-a.example/manifest.json",
            enabled = true,
            allowDownloads = true,
        )
        val addonB = SourceConfig(
            id = "addon-2",
            kind = SourceKind.ADDON,
            baseUrl = "https://private-b.example/manifest.json",
            enabled = false,
            allowDownloads = false,
        )
        val retiredJio = SourceConfig(id = "jio", kind = SourceKind.JIOSAAVN, enabled = true)
        val retiredYt = SourceConfig(id = "yt", kind = SourceKind.YOUTUBE, enabled = true)

        val initialInventory = ProviderCapabilityRegistry.inventory(
            sources = listOf(addonA, addonB, retiredJio, retiredYt),
        )
        val session = ProviderCapabilitySession(
            store = store,
            initialDescriptors = ProviderCapabilityRegistry.descriptorsFrom(initialInventory),
        )
        val coordinator = ProviderAuthorityCoordinator(session)

        assertTrue(
            coordinator.migrateOnce(
                lyricsOrder = listOf(LyricsSource.GENIUS, LyricsSource.LRCLIB),
                enabledLyrics = setOf(LyricsSource.GENIUS),
                sources = listOf(addonA, addonB, retiredJio, retiredYt),
            ),
        )

        val allowedAfterMigration = allowedCanonicalLyricsSources(
            requested = setOf(LyricsSource.GENIUS, LyricsSource.LRCLIB, LyricsSource.YOUTUBE_MUSIC),
            canonicalDiscovery = true,
            configs = listOf(addonA, addonB),
            snapshot = session.state.value,
        )
        assertEquals(setOf(LyricsSource.GENIUS), allowedAfterMigration)

        assertTrue(coordinator.setSingleLyricSourceEnabled(
            source = LyricsSource.GENIUS,
            enabled = false,
            fallbackOrder = listOf(LyricsSource.GENIUS, LyricsSource.LRCLIB),
            fallbackEnabled = setOf(LyricsSource.GENIUS),
        ))
        val allowedWhenAllOff = allowedCanonicalLyricsSources(
            requested = LyricsSource.offered.toSet(),
            canonicalDiscovery = true,
            configs = listOf(addonA, addonB),
            snapshot = session.state.value,
        )
        assertTrue(allowedWhenAllOff.isEmpty())

        assertTrue(coordinator.setExtensionEnabled("addon-1", enabled = false, rawSources = listOf(addonA, addonB, retiredJio, retiredYt)))
        val projectedExtensions = coordinator.currentExtensionsProjection(listOf(addonA, addonB, retiredJio, retiredYt))
        assertEquals(2, projectedExtensions.size)
        assertTrue(projectedExtensions.none { it.enabled })
        assertFalse(prefs.all.toString().contains("private-a.example"))

        val restartedSession = ProviderCapabilitySession(
            store = ProviderPreferenceStore(prefs),
            initialDescriptors = ProviderCapabilityRegistry.descriptorsFrom(initialInventory),
        )
        val restartedCoordinator = ProviderAuthorityCoordinator(restartedSession)
        assertTrue(
            restartedCoordinator.migrateOnce(
                lyricsOrder = listOf(LyricsSource.GENIUS, LyricsSource.LRCLIB),
                enabledLyrics = setOf(LyricsSource.GENIUS, LyricsSource.LRCLIB),
                sources = listOf(addonA.copy(enabled = true), addonB.copy(enabled = true), retiredJio, retiredYt),
            ),
        )
        assertTrue(
            allowedCanonicalLyricsSources(
                requested = LyricsSource.offered.toSet(),
                canonicalDiscovery = true,
                configs = listOf(addonA, addonB),
                snapshot = restartedSession.state.value,
            ).isEmpty(),
        )
        assertTrue(restartedCoordinator.currentExtensionsProjection(listOf(addonA, addonB)).none { it.enabled })
    }

    @Test
    fun endToEndRoomCatalogHttpArtworkAndScrobblingRespectPerCapabilitySwitches(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val store = ProviderPreferenceStore(prefs)
            val inventory = ProviderCapabilityRegistry.inventory()
            val session = ProviderCapabilitySession(
                store = store,
                initialDescriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory),
            )
            val coordinator = ProviderAuthorityCoordinator(session)
            assertTrue(
                coordinator.migrateOnce(
                    lyricsOrder = emptyList(),
                    enabledLyrics = emptySet(),
                    sources = emptyList(),
                ),
            )

            val artistMbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"
            val localRecMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val editorialRecMbid = "3e619ba2-34f0-4649-83d8-05483e2fabe5"

            val cacheRead = ProviderCachedReadAccess { session.gate }
            val index = MusicBrainzCatalogIndex(db, cacheRead = cacheRead)
            index.persistArtists(listOf(MusicBrainzArtistHit(artistMbid, "ILLIT", null, "KR")))
            index.persistArtistGroups(artistMbid, listOf(
                MusicBrainzReleaseGroupHit(groupMbid, "SUPER REAL ME", "EP", "2024-03-25", "ILLIT"),
            ))
            val recIds = index.persistRecordings(listOf(
                MusicBrainzRecordingHit(localRecMbid, "Magnetic", "ILLIT", 160_000L),
                MusicBrainzRecordingHit(editorialRecMbid, "Magnetic (Editorial)", "ILLIT", 162_000L),
            ))
            db.catalogDao().upsertMediaAsset(
                MediaAssetEntity(
                    "local-asset-1",
                    recIds.getValue(localRecMbid),
                    "local-mediastore",
                    "media-1",
                    "content://media/external/audio/media/1",
                    null,
                    null,
                    null,
                    null,
                ),
            )

            var networkCalls = 0
            val client = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                        networkCalls++
                        return """{"count":0,"offset":0,"artists":[],"release-groups":[]}"""
                    }
                },
                rateLimiter = MusicBrainzRateLimiter(nowMillis = { 10_000L }, delayMillis = {}),
                admission = { session.gate },
                singleFlight = ProviderSingleFlight(),
            )
            val searchClient = MusicBrainzCatalogSearch(client)
            val graphClient = MusicBrainzCatalogGraph(client)
            val cachedSearchRepo = MusicBrainzCachedSearchRepository(db, cacheRead = cacheRead)

            // Turn OFF CATALOG_SEARCH while keeping EDITORIAL_DISCOGRAPHY and IDENTITY ON.
            assertTrue(session.update(
                ProviderCapability.CATALOG_SEARCH,
                listOf(ProviderChoice(BuiltInProviderKeys.MUSICBRAINZ, enabled = false, order = 0)),
            ))

            assertTrue(searchClient.searchArtists("ILLIT") is MusicBrainzCatalogSearchResult.Failure)
            assertEquals(0, networkCalls)

            val offlineSnapshot = cachedSearchRepo.search("Magnetic")
            assertTrue(offlineSnapshot.artists.isEmpty())
            assertEquals(listOf(localRecMbid), offlineSnapshot.recordings.map { it.musicBrainzId })
            assertEquals(setOf(localRecMbid), offlineSnapshot.recordingMbidsWithAssets)

            assertEquals(listOf(groupMbid), index.cachedArtistGroups(artistMbid).map { it.musicBrainzId })
            assertTrue(graphClient.browseReleaseGroupsForArtist(artistMbid, limit = 10) is MusicBrainzCatalogGraphResult.Success)
            assertEquals(1, networkCalls)

            // CoverArtArchive guarded transport blocks CAA network calls when RELEASE_ARTWORK is off.
            var caaCalls = 0
            val caa = CoverArtArchive(
                transport = PolicyGuardedCoverArtArchiveTransport(
                    delegate = object : CoverArtArchiveTransport {
                        override suspend fun get(path: String): CoverArtArchiveResponse {
                            caaCalls++
                            return CoverArtArchiveResponse(200, """{"images":[]}""")
                        }
                    },
                    gateProvider = { session.gate },
                ),
            )
            assertTrue(session.update(
                ProviderCapability.RELEASE_ARTWORK,
                listOf(ProviderChoice(BuiltInProviderKeys.COVER_ART_ARCHIVE, enabled = false, order = 0)),
            ))
            assertNull(caa.findCover("271b31f8-8ab6-483c-97d9-8f0fabb2d4f4", null))
            assertEquals(0, caaCalls)

            // Revocation also hides already verified CAA artwork from offline cache.
            val cachedArtwork = CoverArtArchive(
                transport = PolicyGuardedCoverArtArchiveTransport(
                    delegate = object : CoverArtArchiveTransport {
                        override suspend fun get(path: String) = CoverArtArchiveResponse(200, null)
                    },
                    gateProvider = { session.gate },
                ),
                store = object : MusicBrainzCoverArtStore {
                    override suspend fun read(type: String, mbid: String) =
                        MusicBrainzCoverArtCacheEntry(
                            "verified-artist-group", groupMbid,
                            "https://archive.org/verified.jpg",
                            System.currentTimeMillis() - 1_000L,
                        )
                    override suspend fun write(value: MusicBrainzCoverArtCacheEntry) = Unit
                },
            )
            assertNull(cachedArtwork.cachedVerifiedGroupCover(groupMbid))

            // AppleArtistArtRepository honors ARTIST_ARTWORK toggle without contacting HTTP when disabled.
            AppleArtistArtRepository.setPolicyGateForTests { session.gate }
            var appleArtFetches = 0
            AppleArtistArtRepository.setHttpGetForTests {
                appleArtFetches++
                """{"results":[]}"""
            }
            assertTrue(session.update(
                ProviderCapability.ARTIST_ARTWORK,
                listOf(ProviderChoice(BuiltInProviderKeys.APPLE_ARTIST_ART, enabled = false, order = 0)),
            ))
            assertNull(AppleArtistArtRepository.artFor("ILLIT"))
            assertEquals(0, appleArtFetches)

            // ScrobbleManager and ListenBrainzManager honor LISTENING_STATISTICS opt-out.
            ListenBrainzManager.policyGateForTests = { session.gate }
            val song = Song(videoId = "v1", title = "Magnetic", artist = "ILLIT", thumbnailUrl = null)
            val scrobbleManager = ScrobbleManager(
                scope = this,
                policyGate = { session.gate },
            )
            scrobbleManager.onSongStart(song, durationMs = 160_000L)
            scrobbleManager.destroy()
            assertFalse(ListenBrainzManager.submitPlayingNow("token-123", song, 0L, 160_000L))

            val chartsRows = ProviderCapabilityRegistry.projectTab(
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                entries = inventory,
                snapshot = session.state.value,
                policy = ProviderCapabilityPolicy(
                    descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory),
                    choices = session.state.value.configured,
                ),
            )
            val mostPlayed = chartsRows.first { it.entry.capability == ProviderCapability.CHART_MOST_PLAYED }
            assertEquals(ProviderReadiness.NOT_IMPLEMENTED, mostPlayed.entry.readiness)
            assertFalse(mostPlayed.status.implemented)
            assertFalse(mostPlayed.status.effectiveEligible)
        } finally {
            db.close()
        }
    }
}
