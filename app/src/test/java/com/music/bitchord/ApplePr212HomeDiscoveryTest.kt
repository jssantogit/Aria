package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class ApplePr212HomeDiscoveryTest {
    private fun chart(
        id: Long, name: String, market: String,
        kind: ApplePublicChartKind = ApplePublicChartKind.SONGS,
        genre: String = "Pop", artist: String = "Artist $id",
        artistId: Long? = id + 500,
        rank: Int = 1,
    ) = ApplePublicChartEntry(
        storefront = market, id = id, name = name, rank = rank,
        kind = kind, artistName = artist, artistId = artistId,
        primaryGenre = genre, releaseDate = "2026-11-01",
        artwork = "https://is1-ssl.mzstatic.com/album/100x100bb.jpg",
    )

    @Test fun worldwideHomeStartsWithTheSameHeroCardAndNewTitle() {
        val charts = ApplePublicChartsSnapshot(
            songs = listOf(
                chart(1, "One", "br"),
                chart(2, "Two", "us"),
                chart(3, "Three", "kr"),
                chart(4, "Four", "jp"),
            ),
        )
        val projected = applePr212HomeProjection(
            charts, emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        assertEquals("Mais relevantes pra você", projected.shelves.first().title)
        assertEquals(4, projected.shelves.first().items.size)
        assertTrue(projected.shelves.first().items.all {
            it.browseId?.startsWith("itunes:") == true && it.videoId == null
        })
        assertTrue(projected.rows.filter {
            it.musicBrainzId.startsWith("itunes:") && it.kind ==
                com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING
        }.all { !it.hasCatalogedAsset })
    }

    @Test fun recentPlaysNeedActualLocalEvidenceNeverRandomAppleChartIds() {
        val played = ShelfItem("Local song", "Local artist", null, null, "local:pick:hash")
        val projected = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = listOf(chart(1, "Pop", "br"))),
            listOf(played, played), emptyMap(), emptyMap(), "2026-11-15",
        )
        val recents = projected.shelves.single { it.title == "Reproduções recentes" }
        assertEquals(listOf("local:pick:hash"), recents.items.map { it.browseId })
        assertEquals("Mais relevantes pra você", projected.shelves.first().title)
        val noHistory = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = listOf(chart(1, "Pop", "br"))),
            emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        assertFalse(noHistory.shelves.any { it.title == "Reproduções recentes" })
    }

    @Test fun trendingEpRequiresExplicitAppleFormatNotArbitraryTrackCount() {
        val projected = applePr212HomeProjection(
            ApplePublicChartsSnapshot(
                songs = listOf(chart(1, "Pop", "br")),
                albums = listOf(
                    chart(20, "Get Up - EP", "br", ApplePublicChartKind.ALBUMS),
                    chart(21, "Supernatural - Single", "us", ApplePublicChartKind.ALBUMS),
                    chart(22, "Other album", "jp", ApplePublicChartKind.ALBUMS),
                ),
            ), emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        val shelf = projected.shelves.single { it.title == "Singles e EPs em alta" }
        assertEquals(2, shelf.items.size)
        assertFalse(shelf.items.any { it.title == "Other album" })
    }

    @Test fun twoGenreSlotsStayDistinctForTheDayWithoutDuplicatingAppleProducts() {
        val genres = applePr212HomeGenres("2026-11-15")
        assertEquals(2, genres.size)
        assertNotEquals(genres[0].tag, genres[1].tag)
        assertEquals(genres, applePr212HomeGenres("2026-11-15"))
        val songs = (1L..80L).map { n ->
            chart(n, "Song $n", "br", genre = if (n % 2L == 0L)
                "Rock" else "Metal", rank = n.toInt())
        }
        val projected = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = songs),
            emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        val all = projected.shelves.flatMap { it.items }.mapNotNull { it.browseId }
        assertEquals(all.distinct().size, all.size)
        assertTrue(projected.shelves.none { it.items.isEmpty() })
    }

    @Test fun artistCardRequiresExactAppleArtistIdNeverBorrowsAlbumArtwork() {
        val projected = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = listOf(
                chart(1, "First", "br", artist = "Artist", artistId = null),
                chart(2, "Second", "br", artist = "Artist", artistId = 900),
                chart(3, "Third", "br", artist = "Another", artistId = 901),
            )), emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        val artists = projected.shelves.single { it.title == "Artistas em alta" }
        assertEquals(2, artists.items.size)
        assertTrue(artists.items.all { it.thumbnailUrl == null && it.videoId == null })
    }

    @Test fun AppleArtistPortraitComesOnlyFromMatchingStorefrontAndExactArtistIdentity() {
        val valid = "https://is1-ssl.mzstatic.com/image/thumb/Music123/photo/1200x1200bb.jpg"
        val map = mapOf("itunes:br:artist:900" to valid)
        assertEquals(valid, applePr212VerifiedPortrait("br", 900L, map))
        assertNull(applePr212VerifiedPortrait("us", 900L, map))
        assertNull(applePr212VerifiedPortrait("br", 901L, map))
        assertNull(applePr212VerifiedPortrait("br", 0L, map))
        assertNull(applePr212VerifiedPortrait("BR", 900L, map))
        assertNull(applePr212VerifiedPortrait("br", 900L,
            mapOf("itunes:br:artist:900" to "https://example.com/album.jpg")))
        val projected = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = listOf(
                chart(1L, "Top", "br", artist = "Actual Artist", artistId = 900L),
            )), emptyList(), emptyMap(), emptyMap(), "2026-11-15", map,
        )
        val artist = projected.rows.single {
            it.kind == com.music.bitchord.ui.screens.CanonicalSearchKind.ARTIST
        }
        assertEquals("itunes:br:artist:900", artist.musicBrainzId)
        assertEquals(valid, artist.artworkUri)
        assertEquals(valid, projected.shelves.single {
            it.title == "Artistas em alta"
        }.items.single().thumbnailUrl)
        // The corresponding song retains its own cover, not a portrait.
        assertNotEquals(valid, projected.shelves.first().items.single().thumbnailUrl)
    }

    @Test fun verifiedArtworkPatchNeedsSameAppleIdAndOriginalUrl() {
        val item = chart(12, "Test", "br")
        val next = "https://is1-ssl.mzstatic.com/album/600x600bb.jpg"
        val source = ApplePublicChartsSnapshot(songs = listOf(item))
        val rejected = applePr212ChartArtworkPatch(source,
            "itunes:us:track:12", item.artwork!!, next)
        assertEquals(source, rejected)
        val accepted = applePr212ChartArtworkPatch(source, item.scopedId,
            item.artwork!!, next)
        assertEquals(next, accepted.songs.single().artwork)
    }

    @Test fun existingPr212HomeBindingRetainsHeroAndRenamesOnlyShelfTitles() {
        val localHistory = ShelfItem(
            "Heard locally", "Local artist", null, null, "local:pick:verified",
        )
        val chart = ApplePublicChartsSnapshot(
            songs = listOf(
                chart(101, "Chart Song", "br", artistId = 900),
                chart(102, "Another", "us", artistId = 901),
            ),
            albums = listOf(chart(55, "Single - Single", "br",
                kind = ApplePublicChartKind.ALBUMS)),
        )
        val projection = applePr212HomeProjection(
            chart, emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        val tagged = applePr212TaggedHomeRows(projection, "2026-11-15")
        val shelves = applePr212NativeHomeFromRows(
            tagged,
            listOf(com.music.bitchord.data.model.HomeShelf(
                "Em alta", listOf(localHistory),
            )),
            trendingHeading = "Em alta",
            date = "2026-11-15",
        )!!
        assertEquals("Mais relevantes pra você", shelves.first().title)
        assertEquals("Reproduções recentes", shelves[1].title)
        assertEquals(listOf("local:pick:verified"),
            shelves[1].items.map { it.browseId })
        assertTrue(shelves.any { it.title == "Singles e EPs em alta" })
        assertTrue(shelves.any { it.title == "Artistas em alta" })
        assertTrue(tagged.all { it.musicBrainzId.startsWith("itunes:") })
        assertNull(applePr212NativeHomeFromRows(
            projection.rows, emptyList(), "Em alta", "2026-11-15",
        ))
    }

    @Test fun albumArtworkAndArtistIdsStaySourceScopedAcrossTagAdapter() {
        val br = chart(10, "Music", "br", artistId = 101)
        val us = chart(10, "Music", "us", artistId = 102)
        val projection = applePr212HomeProjection(
            ApplePublicChartsSnapshot(songs = listOf(br, us)),
            emptyList(), emptyMap(), emptyMap(), "2026-11-15",
        )
        val tagged = applePr212TaggedHomeRows(projection, "2026-11-15")
        assertTrue(tagged.any { it.musicBrainzId == "itunes:br:track:10" })
        // The same title is only collapsed on this display shelf; the real
        // chart identities stay distinct in their original source data.
        assertEquals(2, listOf(br, us).distinctBy { it.scopedId }.size)
    }

    @Test fun homeClickRoutesAppleTrackAndArtistByTypedIdWithoutInventingAnAlbum() {
        val song = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            musicBrainzId = "itunes:br:track:800", kind =
                com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            title = "Ditto", subtitle = "NewJeans",
        )
        val artist = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            musicBrainzId = "itunes:br:artist:900", kind =
                com.music.bitchord.ui.screens.CanonicalSearchKind.ARTIST,
            title = "NewJeans", subtitle = "Artista",
        )
        assertEquals(song, canonicalEditorialRowForCard(
            listOf(song, artist), "itunes:br:track:800",
        ))
        assertEquals(artist, canonicalEditorialRowForCard(
            listOf(song, artist), "itunes:br:artist:900",
        ))
        assertNull(canonicalEditorialRowForCard(
            listOf(song.copy(kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE)),
            "itunes:br:track:800",
        ))
        assertNull(canonicalEditorialRowForCard(
            listOf(artist), "itunes:us:artist:900",
        ))
    }

    @Test fun chartOutageKeepsVerifiedNewAppleSectionInsteadOfOldHomeCategories() {
        val date = "2026-11-15"
        val projection = applePr212HomeProjection(
            ApplePublicChartsSnapshot(
                songs = emptyList(),
                albums = listOf(chart(56L, "Fresh - Single", "br",
                    kind = ApplePublicChartKind.ALBUMS)),
            ),
            recent = emptyList(), genreTracks = emptyMap(),
            genreAlbums = emptyMap(), date = date,
        )
        val rows = applePr212TaggedHomeRows(projection, date)
        val displayed = applePr212NativeHomeFromRows(
            rows, emptyList(), "Em alta", date,
        )!!
        assertEquals("Singles e EPs em alta", displayed.first().title)
        assertTrue(displayed.none {
            it.title in setOf("Lançamentos", "Escolha a dedo", "Mais")
        })
        assertFalse(displayed.any { it.title == "Mais relevantes pra você" })
    }

    @Test fun roundRobinPromotesSeveralMarketsWithoutInventingWorldwideRank() {
        val entries = listOf(
            chart(11, "BR 1", "br", rank = 1),
            chart(12, "BR 2", "br", rank = 2),
            chart(21, "US 1", "us", rank = 1),
            chart(22, "US 2", "us", rank = 2),
        )
        assertEquals(listOf("BR 1", "US 1", "BR 2", "US 2"),
            applePr212MarketMix(entries).map { it.name })
    }
}
