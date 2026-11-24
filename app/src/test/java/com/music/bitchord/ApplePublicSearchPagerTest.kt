package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApplePublicSearchPagerTest {
    private fun album(id: Long, country: String = "br", title: String = "Release $id") =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM,
            id = id, storefront = country, title = title,
            artistName = "Artist", artistId = 77,
            collectionId = id, storeUrl = null, releaseDate = null,
            genre = null, durationMs = null, trackNumber = null, discNumber = null,
        )

    private class Fake(
        var initial: ItunesCatalogResult,
        val extras: MutableMap<ItunesCatalogSearchAttribute, ItunesCatalogResult> =
            mutableMapOf(),
    ) : ApplePublicSearchSource {
        val calls = mutableListOf<ItunesCatalogSearchAttribute?>()
        var expectedQuery = "Artist"
        override suspend fun search(
            query: String, entity: ItunesCatalogEntity, country: String,
            attribute: ItunesCatalogSearchAttribute?,
        ): ItunesCatalogResult {
            calls.add(attribute)
            assertEquals("br", country)
            assertEquals(expectedQuery, query)
            return if (attribute == null) initial
            else extras[attribute] ?: ItunesCatalogResult.Success(emptyList())
        }
    }

    @Test fun firstTwoHundredAreLocalPagesWithoutFictitiousOffsets() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(
            (1L..200L).map { album(it) }, possiblyTruncated = true))
        val pager = ApplePublicSearchPager(
            query = "Artist", entity = ItunesCatalogEntity.ALBUM,
            country = "br", pageSize = 25, source = fake,
        )
        assertEquals(25, (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.size)
        repeat(7) { pager.loadMore() }
        assertEquals(200, pager.snapshot().visible.size)
        assertEquals(listOf(null), fake.calls)
        assertTrue(pager.snapshot().hasMore)
    }

    @Test fun fallbackAlbumTermCanFindTheTwoHundredFirstAppleProduct() = runBlocking {
        val fake = Fake(
            ItunesCatalogResult.Success((1L..200L).map { album(it) }, true),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(
                    listOf(album(200), album(201), album(202, "us")), false),
            ),
        )
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 50, fake)
        pager.start()
        repeat(4) { pager.loadMore() }
        assertEquals(201, pager.snapshot().visible.size)
        assertTrue(pager.snapshot().coverageUncertain)
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ALBUM_TERM), fake.calls)
    }

    @Test fun cappedEmptyLandingAndDuplicateOnlyProbeReachNextOfficialAttribute() = runBlocking {
        val fake = Fake(
            ItunesCatalogResult.Success(listOf(album(99).copy(artistName = "Other")), true),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(
                    listOf(album(98).copy(artistName = "Other"))),
                ItunesCatalogSearchAttribute.ARTIST_TERM to ItunesCatalogResult.Success(
                    listOf(album(1), album(2))),
            ),
        )
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 1, fake)
        assertEquals(listOf(1L), (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ALBUM_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM), fake.calls)
        assertEquals(listOf(1L, 2L), (pager.loadMore() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
        assertEquals(3, fake.calls.size)
        val continuation = Fake(ItunesCatalogResult.Success(listOf(album(1)), true),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(listOf(album(1))),
                ItunesCatalogSearchAttribute.ARTIST_TERM to ItunesCatalogResult.Success(listOf(album(2))),
            ),
        )
        val continuing = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 1, continuation)
        continuing.start()
        assertEquals(listOf(1L, 2L), (continuing.loadMore() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
    }

    @Test fun failureDoesNotDiscardResultsAndNextClickRetriesSameAttribute() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(listOf(album(1)), true),
            mutableMapOf(ItunesCatalogSearchAttribute.ALBUM_TERM to
                ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)))
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 1, fake)
        pager.start()
        assertTrue(pager.loadMore() is ApplePublicSearchPageLoad.Failure)
        assertEquals(1, pager.snapshot().visible.size)
        fake.extras[ItunesCatalogSearchAttribute.ALBUM_TERM] =
            ItunesCatalogResult.Success(listOf(album(2)))
        pager.loadMore()
        assertEquals(listOf(1L,2L), pager.snapshot().visible.map { it.id })
        assertEquals(2, fake.calls.count { it == ItunesCatalogSearchAttribute.ALBUM_TERM })
    }


    @Test fun artistQueryRejectsSubstringNamesakesButKeepsExactAndLegitimatePrefixes() =
        runBlocking {
            val rows = listOf(
                ItunesCatalogItem(ItunesCatalogItemType.ARTIST, 10, "br", "MOKA ILLIT",
                    null, null, null, null, null, null, null, null, null),
                ItunesCatalogItem(ItunesCatalogItemType.ARTIST, 11, "br", "Ill-It",
                    null, null, null, null, null, null, null, null, null),
                ItunesCatalogItem(ItunesCatalogItemType.ARTIST, 12, "br", "ILLITE",
                    null, null, null, null, null, null, null, null, null),
                ItunesCatalogItem(ItunesCatalogItemType.ARTIST, 13, "br", "ILLIT Official",
                    null, null, null, null, null, null, null, null, null),
                ItunesCatalogItem(ItunesCatalogItemType.ARTIST, 14, "br", "ILLIT",
                    null, null, null, null, null, null, null, null, null),
            )
            val fake = Fake(ItunesCatalogResult.Success(rows)).apply {
                expectedQuery = "illit"
            }
            val pager = ApplePublicSearchPager(
                "illit", ItunesCatalogEntity.ARTIST, "br", 24, fake,
            )
            val result = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
            assertEquals(listOf(14L, 13L), result.visible.map { it.id })
            assertEquals(2, result.discoveredCount)
            assertFalse(result.hasMore)
        }

    @Test fun songsAndAlbumsMatchExactArtistOrActualWorkTitleNotNamesakes() =
        runBlocking {
            fun song(id: Long, artist: String, title: String) =
                ItunesCatalogItem(
                    type = ItunesCatalogItemType.TRACK, id = id,
                    storefront = "br", title = title, artistName = artist,
                    artistId = id, collectionId = 1L, storeUrl = null,
                    releaseDate = null, genre = null, durationMs = null,
                    trackNumber = 1, discNumber = 1,
                )
            val rows = listOf(
                song(1L, "MOKA ILLIT", "Not Related"),
                song(2L, "ILL-IT", "Other"),
                song(3L, "ILLIT", "Magnetic"),
                song(4L, "Other Artist", "ILLIT (a tribute)"),
                song(5L, "ILLIT", "Cherish"),
                song(6L, "MOKA ILLIT", "Unrelated"),
            )
            val fake = Fake(ItunesCatalogResult.Success(rows)).apply {
                expectedQuery = "illit"
            }
            val pager = ApplePublicSearchPager(
                "illit", ItunesCatalogEntity.SONG, "br", 24, fake,
            )
            assertEquals(listOf(3L, 5L, 4L),
                (pager.start() as ApplePublicSearchPageLoad.Success)
                    .snapshot.visible.map { it.id })
            assertEquals(null, applePublicSearchMatchRank(
                "illit", album(10L).copy(artistName = "MOKA ILLIT",
                    title = "Unrelated EP"),
            ))
            assertEquals(0, applePublicSearchMatchRank(
                "illit", album(11L).copy(artistName = "ILLIT",
                    title = "BREAK EVEN"),
            ))
            assertEquals(2, applePublicSearchMatchRank(
                "illit", album(12L).copy(artistName = "Other",
                    title = "ILLIT Live"),
            ))
        }

    @Test fun compoundArtistTitleQueriesUseBothFieldsWithoutAdmittingNamesakes() = runBlocking {
        val exact = album(1, title = "Hunky Dory").copy(artistName = "David Bowie")
        val other = exact.copy(id = 2, artistName = "David Bowies")
        val unrelated = exact.copy(id = 3, artistName = "Other")
        val alternate = exact.copy(id = 4, title = "Hunky Dory (2015 Remaster)")
        assertEquals(1, applePublicSearchMatchRank("David Bowie Hunky Dory", exact))
        assertEquals(1, applePublicSearchMatchRank("Hunky Dory - David Bowie", exact))
        assertEquals(2, applePublicSearchMatchRank("David Bowie Hunky Dory", alternate))
        assertNull(applePublicSearchMatchRank("David Bowie Hunky Dory", other))
        assertNull(applePublicSearchMatchRank("David Bowie Hunky Dory", unrelated))
        val fake = Fake(ItunesCatalogResult.Success(listOf(other, unrelated, alternate, exact)))
            .apply { expectedQuery = "David Bowie Hunky Dory" }
        val pager = ApplePublicSearchPager(fake.expectedQuery, ItunesCatalogEntity.ALBUM,
            "br", source = fake)
        assertEquals(listOf(1L, 4L), (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
        val song = exact.copy(type = ItunesCatalogItemType.TRACK, title = "Magnetic",
            artistName = "ILLIT")
        assertEquals(1, applePublicSearchMatchRank("Magnetic ILLIT", song))
        assertNull(applePublicSearchMatchRank("Magnetic ILLIT",
            song.copy(artistName = "Illiterate")))
    }

    @Test fun titleFirstEditionQueriesKeepExactArtistAndWorkBoundary() = runBlocking {
        val single = album(1, title = "Blue Monday - Single").copy(artistName = "New Order")
        val remaster = album(2, title = "Violator (2006 Remaster)").copy(artistName = "Depeche Mode")
        assertEquals(2, applePublicSearchMatchRank("Blue Monday New Order", single))
        assertEquals(2, applePublicSearchMatchRank("Blue Monday - New Order", single))
        assertEquals(2, applePublicSearchMatchRank("Violator Depeche Mode", remaster))
        assertNull(applePublicSearchMatchRank("Blue Monday New Order",
            single.copy(artistName = "New Orders")))
        assertNull(applePublicSearchMatchRank("Blue Monday New Order",
            single.copy(artistName = "Other")))
        assertNull(applePublicSearchMatchRank("Blue Monday New Order",
            single.copy(title = "Blue Mondays - Single")))
        assertNull(applePublicSearchMatchRank("Blue Monday New Order",
            single.copy(title = "Blue Monday Tribute")))
        val fake = Fake(ItunesCatalogResult.Success(listOf(
            single.copy(id = 3, artistName = "New Orders"), single,
        ))).apply { expectedQuery = "Blue Monday New Order" }
        val pager = ApplePublicSearchPager(fake.expectedQuery, ItunesCatalogEntity.ALBUM,
            "br", source = fake)
        assertEquals(listOf(1L), (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
    }

    @Test fun accentsAndArticlesAreNormalizedWithoutRemovingSourcePunctuation() {
        val artist = ItunesCatalogItem(
            ItunesCatalogItemType.ARTIST, 17L, "br", "Beyoncé",
            null, null, null, null, null, null, null, null, null,
        )
        assertEquals(0, applePublicSearchMatchRank("Beyonce", artist))
        assertEquals(3, applePublicSearchMatchRank("beatles",
            artist.copy(title = "The Beatles")))
        assertNull(applePublicSearchMatchRank("illit", artist.copy(title = "Ill-It")))
        assertNull(applePublicSearchMatchRank("illit", artist.copy(title = "ILLITERATE")))
    }


    @Test fun invalidUserSearchQueryReturnsFailureInsteadOfCrashingConstructor() =
        runBlocking {
            val fake = Fake(ItunesCatalogResult.Success(emptyList()))
            val pager = ApplePublicSearchPager(
                query = "Invalid\nName", entity = ItunesCatalogEntity.ALBUM,
                country = "br", source = fake,
            )
            assertTrue(pager.start() is ApplePublicSearchPageLoad.Failure)
            assertTrue(fake.calls.isEmpty())
        }

    @Test fun contradictorySnapshotsForSameAppleIdAreExcludedNotPickedByFirstRow() =
        runBlocking {
            val fake = Fake(
                ItunesCatalogResult.Success(listOf(album(1)), true),
                mutableMapOf(ItunesCatalogSearchAttribute.ALBUM_TERM to
                    ItunesCatalogResult.Success(listOf(album(1, title = "Different")))),
            )
            val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
                "br", 1, fake)
            pager.start()
            pager.loadMore()
            assertTrue(pager.snapshot().visible.isEmpty())
            assertTrue(pager.snapshot().coverageUncertain)
        }
}
