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

    @Test fun disabledSearchReturnsNoMusicBrainzRowsWithoutDeletingSourceEvidence(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val artistMbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val recordingMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests())
            index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "ILLIT", null, "KR",
            )))
            val ids = index.persistRecordings(listOf(
                MusicBrainzRecordingHit(recordingMbid, "Magnetic", "ILLIT", 160000L),
            ))
            db.catalogDao().upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset-local", ids.getValue(recordingMbid),
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
            assertEquals(listOf(recordingMbid),
                song.recordings.map { it.musicBrainzId })
            assertEquals(setOf(recordingMbid), song.recordingMbidsWithAssets)

            gate.replace(policy(search = false))
            val disabled = repository.search("Magnetic")
            assertTrue(disabled.isEmpty)
            assertTrue(disabled.recordingMbidsWithAssets.isEmpty())
            assertEquals(ids.getValue(recordingMbid),
                db.catalogDao().entityIdForExternalIdentity(
                    "musicbrainz", "recording", recordingMbid,
                ))
            val localBrowse = com.music.bitchord.data.catalog.CanonicalMultiSourceBrowseRepository(
                com.music.bitchord.data.catalog.RoomCanonicalMultiSourceBrowseStore(db.catalogDao()),
            )
            val localTracks = localBrowse.observe(setOf("local-mediastore")).first().tracks
            assertEquals(listOf("asset-local"),
                localTracks.flatMap { it.availableAssets }.map { it.mediaAssetId })

            gate.replace(policy(search = true))
            assertEquals(listOf(recordingMbid),
                repository.search("Magnetic").recordings.map { it.musicBrainzId })
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
}
