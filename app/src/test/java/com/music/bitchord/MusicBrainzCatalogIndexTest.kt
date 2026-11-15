package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class MusicBrainzCatalogIndexTest {
    @Before
    fun setUp() {
        ProviderCachedReadAccessTestHelper.installPermissiveGate()
    }

    @After
    fun tearDown() {
        ProviderCachedReadAccessTestHelper.resetGate()
    }

    @Test
    fun musicBrainzArtistNameIsSearchableWithoutOverwritingLocalArtistAlias() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val mbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val ids = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests()).persistArtists(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit(
                    mbid, "ILLIT", "South Korean girl group", "KR",
                ),
            ))
            val artistId = ids[mbid]!!
            db.catalogDao().upsertArtist(com.music.bitchord.data.catalog.ArtistEntity(
                artistId, "My local alias",
            ))
            assertEquals("My local alias", db.catalogDao().artistById(artistId)!!.name)
            val remote = db.catalogDao().searchKnownMusicBrainzArtists("ILLIT", 10).single()
            assertEquals("ILLIT", remote.name)
            assertEquals(mbid, remote.musicBrainzId)
            assertEquals("South Korean girl group", remote.disambiguation)
        } finally { db.close() }
    }

    @Test
    fun invalidMbidCannotBeClaimedByPublicIndex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess.permissiveForUnitTests(), idFactory = { "unexpected" })
            assertTrue(index.persistRecordings(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit("not-an-mbid", "Magnetic", "ILLIT", null),
            )).isEmpty())
            assertNull(db.catalogDao().recordingById("unexpected"))
        } finally { db.close() }
    }
}
