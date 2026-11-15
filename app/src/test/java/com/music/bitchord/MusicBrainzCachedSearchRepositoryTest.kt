package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * The headless local catalog search is deliberately separate from the REA UI.
 * It must not resurrect disabled MusicBrainz-only evidence or touch media files.
 */
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
            val index = MusicBrainzCatalogIndex(db)
            index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "ILLIT", null, "KR",
            )))
            val ids = index.persistRecordings(listOf(
                MusicBrainzRecordingHit(recordingMbid, "Magnetic", "ILLIT", 160000L),
            ))
            db.catalogDao().upsertMediaAsset(MediaAssetEntity(
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
            assertEquals("asset-local",
                db.catalogDao().recordingById(ids.getValue(recordingMbid))
                    ?.let { "asset-local" }) // canonical identity retained

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
            val index = MusicBrainzCatalogIndex(db)
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
            val index = MusicBrainzCatalogIndex(db)
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

    @Test(expected = IllegalArgumentException::class)
    fun unreasonableQueryLimitsAreRejectedInsteadOfScanningTheDatabase() {
        MusicBrainzCachedSearchLimits(artists = 10000)
    }
}
