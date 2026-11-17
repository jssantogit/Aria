package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCachedSearchLimits
import com.music.bitchord.data.catalog.metadata.MusicBrainzCachedSearchRepository
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import com.music.bitchord.data.providers.ProviderSessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzCachedSearchRepositoryTest {
    private val musicBrainz = ProviderKey("musicbrainz")

    private fun gate(search: Boolean, discography: Boolean = true) =
        ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(musicBrainz,
                setOf(ProviderCapability.CATALOG_SEARCH,
                    ProviderCapability.EDITORIAL_DISCOGRAPHY),
                readiness = ProviderReadiness.READY)),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(musicBrainz, search, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                    ProviderChoice(musicBrainz, discography, 0)),
            ),
        ))

    private fun policy(search: Boolean, discography: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(musicBrainz,
                setOf(ProviderCapability.CATALOG_SEARCH,
                    ProviderCapability.EDITORIAL_DISCOGRAPHY),
                readiness = ProviderReadiness.READY)),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(musicBrainz, search, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                    ProviderChoice(musicBrainz, discography, 0)),
            ),
        )

    @Test fun disabledSearchHidesEditorialOnlyRowsWhilePreservingLocalAssetMbids(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val artistMbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val localRecordingMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val editorialOnlyMbid = "3e619ba2-34f0-4649-83d8-05483e2fabe5"
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
            index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "ILLIT", null, "KR",
            )))
            val ids = index.persistRecordings(listOf(
                MusicBrainzRecordingHit(localRecordingMbid, "Magnetic", "ILLIT", 160000L),
                MusicBrainzRecordingHit(editorialOnlyMbid, "Magnetic (Editorial Only)", "ILLIT", 162000L),
            ))
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-local", ids.getValue(localRecordingMbid),
                "local-mediastore", "file-1",
                "content://media/external/audio/media/1", null, null, null, null,
            ))
            val gate = gate(search = true)
            val repository = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate },
            )
            val enabled = repository.search("IL", MusicBrainzCachedSearchLimits(
                artists = 4, recordings = 0, groups = 0, releases = 0,
            ))
            assertEquals(listOf(artistMbid),
                enabled.artists.map { it.musicBrainzId })

            val song = repository.search("Magnetic", MusicBrainzCachedSearchLimits(
                artists = 0, recordings = 4, groups = 0, releases = 0,
            ))
            assertEquals(
                setOf(localRecordingMbid, editorialOnlyMbid),
                song.recordings.map { it.musicBrainzId }.toSet(),
            )
            assertEquals(setOf(localRecordingMbid), song.recordingMbidsWithAssets)

            // Disabling MusicBrainz editorial search suppresses editorial-only rows,
            // while keeping recordings backed by local/personal MediaAssets visible.
            gate.replace(policy(search = false))
            val disabled = repository.search("Magnetic")
            assertTrue(disabled.artists.isEmpty())
            assertTrue(disabled.groups.isEmpty())
            assertTrue(disabled.releases.isEmpty())
            assertEquals(listOf(localRecordingMbid), disabled.recordings.map { it.musicBrainzId })
            assertEquals(setOf(localRecordingMbid), disabled.recordingMbidsWithAssets)
            assertEquals(ids.getValue(localRecordingMbid),
                db.catalogDao().entityIdForExternalIdentity(
                    "musicbrainz", "recording", localRecordingMbid,
                ))
            val localBrowse = com.music.bitchord.data.catalog.CanonicalMultiSourceBrowseRepository(
                com.music.bitchord.data.catalog.RoomCanonicalMultiSourceBrowseStore(db.catalogDao()),
            )
            val localTracks = localBrowse.observe(setOf("local-mediastore")).first().tracks
            assertEquals(listOf("asset-local"),
                localTracks.flatMap { it.availableAssets }.map { it.mediaAssetId })

            gate.replace(policy(search = true))
            assertEquals(
                setOf(localRecordingMbid, editorialOnlyMbid),
                repository.search("Magnetic").recordings.map { it.musicBrainzId }.toSet(),
            )
        } finally { db.close() }
    }

    @Test fun unrelatedDiscographySwitchNeverActivatesDisabledSearch(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
            index.persistArtists(listOf(MusicBrainzArtistHit(
                "6b128dd7-e998-4edb-a4f5-400fef190445", "ILLIT", null, "KR",
            )))
            val gate = gate(search = false, discography = true)
            val repository = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate },
            )
            assertTrue(repository.search("ILLIT").isEmpty)
        } finally { db.close() }
    }

    @Test fun emptyQuerySupportsOfflineHomeWhileZeroLimitsSkipAllQueries(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
            val mbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            index.persistArtists(listOf(MusicBrainzArtistHit(mbid, "ILLIT", null, "KR")))
            val repository = MusicBrainzCachedSearchRepository(db,
                cacheRead = ProviderCachedReadAccess { gate(search = true) })
            assertEquals(listOf(mbid), repository.search("").artists.map { it.musicBrainzId })
            assertTrue(repository.search("", MusicBrainzCachedSearchLimits(
                artists = 0, groups = 0, releases = 0, recordings = 0,
            )).isEmpty)
        } finally { db.close() }
    }

    @Test fun cacheFlowRevokesDisplayedSourceSnapshotWithoutDeletingStoredRecords(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val mbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests()).persistArtists(
                listOf(MusicBrainzArtistHit(mbid, "ILLIT", null, "KR")),
            )
            val gate = gate(search = true)
            val states = MutableStateFlow(ProviderSessionSnapshot(
                status = ProviderSessionStatus.READY,
                eligible = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz)),
            ))
            val repo = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate },
            )
            val received = Channel<List<String>>(Channel.UNLIMITED)
            val job = launch {
                repo.observe("ILLIT", providerState = states,
                    limits = MusicBrainzCachedSearchLimits(
                        artists = 6, groups = 0, releases = 0, recordings = 0,
                    ),
                ).collect { snapshot ->
                    received.send(snapshot.artists.map { it.musicBrainzId })
                }
            }
            try {
                assertEquals(listOf(mbid), withTimeout(5_000) { received.receive() })
                gate.replace(policy(search = false))
                states.value = ProviderSessionSnapshot(status = ProviderSessionStatus.READY)
                assertEquals(emptyList<String>(), withTimeout(5_000) { received.receive() })
                // Source-scope visibility may change, but the canonical claim remains.
                assertTrue(db.catalogDao().entityIdForExternalIdentity(
                    "musicbrainz", "artist", mbid,
                ) != null)
                gate.replace(policy(search = true))
                states.value = ProviderSessionSnapshot(
                    status = ProviderSessionStatus.READY,
                    eligible = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz)),
                )
                assertEquals(listOf(mbid), withTimeout(5_000) { received.receive() })
            } finally {
                job.cancelAndJoin()
            }
        } finally { db.close() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unreasonableQueryLimitsAreRejectedInsteadOfScanningTheDatabase() {
        MusicBrainzCachedSearchLimits(artists = 10000)
    }
    @Test fun reactiveOptOutKeepsLocalRecordingWithMusicBrainzMbid(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val personal = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val editorial = "3e619ba2-34f0-4649-83d8-05483e2fabe5"
            val index = MusicBrainzCatalogIndex(
                db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests(),
            )
            val ids = index.persistRecordings(listOf(
                MusicBrainzRecordingHit(personal, "Magnetic", "ILLIT", 160000L),
                MusicBrainzRecordingHit(editorial, "Magnetic Editorial", "ILLIT", 162000L),
            ))
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-personal-42", ids.getValue(personal),
                "local-mediastore", "local-42",
                "content://media/external/audio/media/42", null, null, null, null,
            ))
            val gate = gate(search = true)
            val providerState = MutableStateFlow(ProviderSessionSnapshot(
                status = ProviderSessionStatus.READY,
                eligible = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz)),
            ))
            val cache = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate },
            )
            val received = Channel<List<String>>(Channel.UNLIMITED)
            val observer = launch {
                cache.observe("Magnetic", providerState = providerState).collect { snapshot ->
                    received.send(snapshot.recordings.map { it.musicBrainzId })
                }
            }
            try {
                assertEquals(setOf(personal, editorial),
                    withTimeout(5_000) { received.receive() }.toSet())
                gate.replace(policy(search = false))
                providerState.value = ProviderSessionSnapshot(status = ProviderSessionStatus.READY)
                assertEquals(listOf(personal), withTimeout(5_000) { received.receive() })
                assertEquals(ids.getValue(personal),
                    db.catalogDao().entityIdForExternalIdentity(
                        "musicbrainz", "recording", personal))
                gate.replace(policy(search = true))
                providerState.value = ProviderSessionSnapshot(
                    status = ProviderSessionStatus.READY,
                    eligible = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(musicBrainz)),
                )
                assertEquals(setOf(personal, editorial),
                    withTimeout(5_000) { received.receive() }.toSet())
            } finally { observer.cancelAndJoin() }
        } finally { db.close() }
    }

    @Test fun cdnAssetCannotClaimPersonalOwnershipAfterMusicBrainzOptOut(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val localId = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val editorialId = "3e619ba2-34f0-4649-83d8-05483e2fabe5"
            val ids = MusicBrainzCatalogIndex(db,
                cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
                .persistRecordings(listOf(
                    MusicBrainzRecordingHit(localId, "Magnetic", "ILLIT", 160000L),
                    MusicBrainzRecordingHit(editorialId, "Magnetic Cover", "ILLIT", 160500L),
                ))
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-owned", ids.getValue(localId), "local-mediastore", "local-1",
                "content://media/external/audio/media/1", null, null, null, null,
            ))
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-cdn", ids.getValue(editorialId), "itunes", "store-preview",
                "https://cdn.example/audio-preview", null, null, null, null,
            ))
            val repo = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate(search = false) },
                personalProviderKeys = { setOf("local-mediastore", "itunes") },
            )
            val visible = repo.search("Magnetic")
            assertEquals(listOf(localId), visible.recordings.map { it.musicBrainzId })
            assertEquals(setOf(localId), visible.recordingMbidsWithAssets)
            val direct = db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                listOf(localId, editorialId),
            )
            assertEquals(setOf(localId, editorialId), direct.toSet())
        } finally { db.close() }
    }

    @Test fun onlyConfiguredOpenSubsonicAccountIsPersonalEvidence(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val id = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val rid = MusicBrainzCatalogIndex(db,
                cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
                .persistRecordings(listOf(
                    MusicBrainzRecordingHit(id, "Magnetic", "ILLIT", 160000L),
                )).getValue(id)
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-remote", rid, "opensubsonic:home", "server-track-1",
                "bitchord://canonical?recording=1", null, null, null, null,
            ))
            val grant = mutableSetOf("local-mediastore")
            val repo = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate(search = false) },
                personalProviderKeys = { grant.toSet() },
            )
            assertTrue(repo.search("Magnetic").recordings.isEmpty())
            grant += "opensubsonic:home"
            assertEquals(listOf(id), repo.search("Magnetic").recordings.map { it.musicBrainzId })
            grant -= "opensubsonic:home"
            assertTrue(repo.search("Magnetic").recordings.isEmpty())
        } finally { db.close() }
    }

    @Test fun personalMbidsCannotBeStarvedByEditorialResultsBeforeSqlLimit(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val editorialHits = (1..30).map { number ->
                MusicBrainzRecordingHit(
                    "%08x-aaaa-4aaa-8aaa-%012x".format(number, number),
                    "Magnetic Editorial %02d".format(number), "ILLIT", 161000L,
                )
            }
            val owned = MusicBrainzRecordingHit(
                "e614e432-ca77-41c1-82c1-4e3a24ebbb0c",
                "Magnetic Local Last", "ILLIT", 160000L,
            )
            val ids = MusicBrainzCatalogIndex(db,
                cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
                .persistRecordings(editorialHits + owned)
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "local-only-last", ids.getValue(owned.musicBrainzId),
                "local-mediastore", "local-track",
                "content://media/external/audio/media/123", null, null, null, null,
            ))
            val repo = MusicBrainzCachedSearchRepository(
                db, cacheRead = ProviderCachedReadAccess { gate(search = false) },
            )
            val snapshot = repo.search("Magnetic", MusicBrainzCachedSearchLimits(
                artists = 0, groups = 0, releases = 0, recordings = 1,
            ))
            assertEquals(listOf(owned.musicBrainzId),
                snapshot.recordings.map { it.musicBrainzId })
            assertEquals(setOf(owned.musicBrainzId), snapshot.recordingMbidsWithAssets)
        } finally { db.close() }
    }

}
