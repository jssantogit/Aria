package com.music.bitchord

import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.allowedCanonicalLyricsSources
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
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderAuthorityCoordinator
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPreferenceStore
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderSingleFlight
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
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

/**
 * End-to-end integration proof for Issue #218 across:
 * - Real Room database + MusicBrainzCatalogIndex + MusicBrainzCachedSearchRepository
 * - Real SharedPreferences + ProviderPreferenceStore + ProviderCapabilitySession
 * - ProviderAuthorityCoordinator + allowedCanonicalLyricsSources
 * - ProviderCapabilityRegistry honest NOT_IMPLEMENTED vs READY inventory
 * - Real MusicBrainzClient HTTP boundary + MusicBrainzCatalogSearch + MusicBrainzCatalogGraph
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

        // Verify allowedCanonicalLyricsSources honors the single-authority snapshot.
        val allowedAfterMigration = allowedCanonicalLyricsSources(
            requested = setOf(LyricsSource.GENIUS, LyricsSource.LRCLIB, LyricsSource.YOUTUBE_MUSIC),
            canonicalDiscovery = true,
            configs = listOf(addonA, addonB),
            snapshot = session.state.value,
        )
        assertEquals(setOf(LyricsSource.GENIUS), allowedAfterMigration)

        // Turn off ALL lyric providers including the last one -> zero active, zero hidden fallback.
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

        // Disable addon-1 while keeping addon-2 disabled; verify retired sources never appear.
        assertTrue(coordinator.setExtensionEnabled("addon-1", enabled = false, rawSources = listOf(addonA, addonB, retiredJio, retiredYt)))
        val projectedExtensions = coordinator.currentExtensionsProjection(listOf(addonA, addonB, retiredJio, retiredYt))
        assertEquals(2, projectedExtensions.size)
        assertTrue(projectedExtensions.none { it.enabled })
        assertFalse(prefs.all.toString().contains("private-a.example"))

        // Simulate app restart with stale legacy inputs: opt-outs must NEVER resurrect!
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
    fun endToEndRoomCatalogAndHttpBoundaryRespectPerCapabilitySwitchesAndPreserveLocalMedia(): Unit = runBlocking {
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

            // Seed Room with an artist, a release group, a local-backed recording, and an editorial-only recording.
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
                    id = "local-asset-1",
                    recordingId = recIds.getValue(localRecMbid),
                    providerKey = "local-mediastore",
                    providerMediaId = "media-1",
                    uri = "content://media/external/audio/media/1",
                    mimeType = "audio/flac",
                    bitrateKbps = null,
                    sampleRateHz = 44100,
                    channelCount = 2,
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

            // 1) Network search is denied before touching transport.
            assertTrue(searchClient.searchArtists("ILLIT") is MusicBrainzCatalogSearchResult.Failure)
            assertEquals(0, networkCalls)

            // 2) Cached search suppresses editorial-only rows, but preserves local-asset recording!
            val offlineSnapshot = cachedSearchRepo.search("Magnetic")
            assertTrue(offlineSnapshot.artists.isEmpty())
            assertEquals(listOf(localRecMbid), offlineSnapshot.recordings.map { it.musicBrainzId })
            assertEquals(setOf(localRecMbid), offlineSnapshot.recordingMbidsWithAssets)

            // 3) Discography cache and network remain active independently.
            assertEquals(listOf(groupMbid), index.cachedArtistGroups(artistMbid).map { it.musicBrainzId })
            assertTrue(graphClient.browseReleaseGroupsForArtist(artistMbid) is MusicBrainzCatalogGraphResult.Success)
            assertEquals(1, networkCalls)

            // 4) Unimplemented capabilities report NOT_IMPLEMENTED and never become eligible.
            val chartsRows = ProviderCapabilityRegistry.projectTab(
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                entries = inventory,
                snapshot = session.state.value,
                policy = session.gate.let {
                    com.music.bitchord.data.providers.ProviderCapabilityPolicy(
                        descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory),
                        choices = session.state.value.configured,
                    )
                },
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
