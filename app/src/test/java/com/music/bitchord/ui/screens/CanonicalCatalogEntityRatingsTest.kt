package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCatalogEntityRatingsTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test fun onlineArtistAndEditionUseFirstPartyIdentityWithoutAPlayableAsset() {
        val artist = canonicalCatalogEntityRatingTarget("catalog:ARTIST:$id")
        val release = canonicalCatalogEntityRatingTarget("catalog:RELEASE:$id")
        val group = canonicalCatalogEntityRatingTarget("catalog:RELEASE_GROUP:$id")
        assertEquals(CanonicalEntityRatingTarget("ARTIST", id), artist)
        assertEquals(CanonicalEntityRatingTarget("ALBUM", id), release)
        assertEquals(CanonicalEntityRatingTarget("RELEASE_GROUP", id), group)
        assertTrue(canPersistCanonicalCatalogRating(artist!!))
        assertTrue(canPersistCanonicalCatalogRating(release!!))
        assertTrue(canPersistCanonicalCatalogRating(group!!))
    }

    @Test fun onlinePlaylistCannotMasqueradeAsLocalPlaylistOrAnAlbum() {
        assertEquals(CanonicalEntityRatingTarget("PLAYLIST", "public:$id"),
            canonicalPublicPlaylistRatingTarget(id))
        assertEquals(CanonicalEntityRatingTarget("PLAYLIST", "normal:123"),
            canonicalOwnedPlaylistRatingTarget("normal:123"))
        assertNull(canonicalOwnedPlaylistRatingTarget("catalog:RELEASE:$id"))
        assertNull(canonicalPublicPlaylistRatingTarget("not-a-mbid"))
    }

    @Test fun amsArtistAlbumPlaylistUseScopedLocalRatingsAndNativeMenuWithoutPlayback() {
        for ((kind,type) in listOf("artist" to "ARTIST","album" to "ALBUM","playlist" to "PLAYLIST")) {
            val id = if (kind == "playlist") "pl.abc123" else "123"
            val route = "apple_music_web:br:$kind:$id"
            val target = canonicalCatalogEntityRatingTarget(route)!!
            assertEquals(CanonicalEntityRatingTarget(type,route),target)
            assertTrue(canPersistCanonicalCatalogRating(target))
            assertFalse(canPersistCanonicalCatalogRating(target.copy(entityType="RELEASE_GROUP")))
            assertTrue(validCatalogMetadataPin(CatalogMetadataPin(route,"Test")))
            val spec = canonicalCatalogEntityMenuSpec(route,"https://music.apple.com/br/$kind/test/$id")!!
            assertEquals(target,spec.ratingTarget)
            assertEquals("https://music.apple.com/br/$kind/test/$id",spec.sourceUrl)
            assertEquals("Abrir no Apple Music",spec.sourceLabel)
            assertNull(spec.playlistKind) // verified source-member import is a separate storage contract
            assertNull(canonicalCatalogEntityMenuSpec(route,"https://music.apple.com/us/$kind/test/$id")!!.sourceUrl)
        }
        assertNull(canonicalCatalogEntityRatingTarget("apple_music_web:br:artist:0"))
        assertNull(canonicalCatalogEntityRatingTarget("apple_music_web:br:song:123"))
    }

    @Test fun rejectExternalUrlsNamesAndMalformedIdentities() {
        assertNull(canonicalCatalogEntityRatingTarget("catalog:ARTIST:STAYC"))
        assertNull(canonicalCatalogEntityRatingTarget("catalog:ARTIST: $id"))
        assertNull(canonicalCatalogEntityRatingTarget("catalog:RELEASE_GROUP:$id/extra"))
        assertNull(canonicalCatalogEntityRatingTarget("local:ALBUM:$id"))
        assertFalse(canPersistCanonicalCatalogRating(CanonicalEntityRatingTarget("RECORDING", id)))
        assertFalse(canPersistCanonicalCatalogRating(CanonicalEntityRatingTarget("PLAYLIST", "public:fake")))
    }
    @Test fun nativeProviderRatingsKeepExactMarketTypeAndId() {
        val routes = mapOf(
            "itunes:br:artist:123" to "ARTIST",
            "itunes:us:artist:123" to "ARTIST",
            "itunes:br:album:123" to "ALBUM",
            "discogs:artist:123" to "ARTIST",
            "discogs:release:123" to "ALBUM",
        )
        val targets = routes.map { (route, type) ->
            val target = canonicalCatalogEntityRatingTarget(route)!!
            assertEquals(CanonicalEntityRatingTarget(type, route), target)
            assertTrue(canPersistCanonicalCatalogRating(target))
            assertFalse(canPersistCanonicalCatalogRating(target.copy(entityType = "RELEASE_GROUP")))
            target
        }
        assertEquals(routes.size, targets.toSet().size)
        for (route in listOf("itunes:br:track:123", "itunes:br:album:0",
            "itunes:br:artist:123/extra", "discogs:artist:0", "discogs:release:001",
            "discogs:master:123", "discogs:release:123/extra")) {
            assertNull(route, canonicalCatalogEntityRatingTarget(route))
        }
    }

    @Test fun nativeProviderMenusUseTheirOwnVerifiedSourceAndNeverImportMbRecordings() {
        val discogs = canonicalCatalogEntityMenuSpec("discogs:release:123")!!
        assertEquals("https://www.discogs.com/release/123", discogs.sourceUrl)
        assertEquals("Abrir no Discogs", discogs.sourceLabel)
        assertNull(discogs.playlistKind)
        val itunes = canonicalCatalogEntityMenuSpec("itunes:br:album:123")!!
        assertNull(itunes.sourceUrl)
        assertNull(itunes.playlistKind)
        assertEquals("Abrir no Apple Music", itunes.sourceLabel)
        assertNull(canonicalCatalogEntityMenuSpec("itunes:br:album:123",
            "https://example.com/album/123")!!.sourceUrl)
        val mb = canonicalCatalogEntityMenuSpec("catalog:RELEASE:$id")!!
        assertEquals("https://musicbrainz.org/release/$id", mb.sourceUrl)
        assertEquals(CanonicalSearchKind.RELEASE, mb.playlistKind)
    }

    @Test fun identicalNativeNumbersNeverShareRatingIdentityAcrossSources() {
        val routes = listOf("itunes:br:album:123","itunes:us:album:123",
            "apple_music_web:br:album:123","apple_music_web:us:album:123","discogs:release:123")
        val targets = routes.map { requireNotNull(canonicalCatalogEntityRatingTarget(it)) }
        assertEquals(routes.size,targets.toSet().size)
        assertTrue(targets.all(::canPersistCanonicalCatalogRating))
    }
}
