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

    @Test fun songCollectionRecoveryHydratesExactAlbumAndNeverPublishesSongCreditAsAlbum() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val capabilities = mutableListOf<com.music.bitchord.data.providers.ProviderCapability>()
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String =
                error("Capability-aware call required")
            override suspend fun get(path: String, parameters: Map<String, String>,
                capability: com.music.bitchord.data.providers.ProviderCapability): String {
                calls += path to parameters
                capabilities += capability
                return when {
                    path == "search" -> """{"resultCount":4,"results":[
                        {"wrapperType":"track","kind":"song","trackId":1,"collectionId":10,
                         "collectionName":"Example Work","trackName":"Song","artistId":99,"artistName":"Example Artist"},
                        {"wrapperType":"track","kind":"song","trackId":2,"collectionId":10,
                         "collectionName":"Example Work","trackName":"Another","artistId":99,"artistName":"Example Artist"},
                        {"wrapperType":"track","kind":"song","trackId":3,"collectionId":20,
                         "collectionName":"Example Work","trackName":"Song","artistId":99,"artistName":"Example Artist"},
                        {"wrapperType":"track","kind":"song","trackId":4,"collectionId":30,
                         "collectionName":"Example Work","trackName":"Song","artistId":99,"artistName":"Example Artist"}
                    ]}"""
                    parameters["id"] == "10" -> """{"resultCount":1,"results":[
                        {"wrapperType":"collection","collectionType":"Album","collectionId":10,
                         "collectionName":"Example Work","artistId":77,"artistName":"Other Artist"}
                    ]}"""
                    parameters["id"] == "20" -> """{"resultCount":2,"results":[
                        {"wrapperType":"collection","collectionType":"Album","collectionId":20,
                         "collectionName":"Example Work (Remastered)","artistId":99,"artistName":"Example Artist"},
                        {"wrapperType":"collection","collectionType":"Album","collectionId":21,
                         "collectionName":"Example Work","artistId":99,"artistName":"Example Artist"}
                    ]}"""
                    else -> error("Unbounded lookup")
                }
            }
        }
        val source = ItunesPublicSearchSource(ItunesCatalogClient(transport))
        val result = source.discoverSongCollections(
            "Example Artist Example Work", "Example Work", "br", emptySet(),
        ) as ItunesCatalogResult.Success
        assertEquals(listOf(20L), result.items.map { it.id })
        assertEquals("Example Work (Remastered)", result.items.single().title)
        assertEquals(listOf("search", "lookup", "lookup"), calls.map { it.first })
        assertEquals(listOf("10", "20"), calls.drop(1).map { it.second["id"] })
        assertEquals("songTerm", calls.first().second["attribute"])
        assertTrue(calls.all { it.second["country"] == "br" })
        assertTrue(capabilities.all { it == com.music.bitchord.data.providers.ProviderCapability.CATALOG_SEARCH })
        assertFalse(result.items.single().isPlayable)
    }

    @Test fun albumCollectionFallbackRunsOnceAfterEmptyFieldsAndRetainsExactIds() = runBlocking {
        var recoveryCalls = 0
        val source = object : ApplePublicSearchSource {
            override val supportsSongCollectionDiscovery = true
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ) = ItunesCatalogResult.Success(emptyList())

            override suspend fun discoverSongCollections(
                originalQuery: String, workTerm: String, country: String, knownIds: Set<Long>,
            ): ItunesCatalogResult {
                recoveryCalls++
                assertTrue(knownIds.isEmpty())
                return ItunesCatalogResult.Success(listOf(
                    album(100, title = "Example Work"),
                    album(200, country = "us", title = "Example Work"),
                    album(300, title = "Unrelated"),
                ))
            }
        }
        val pager = ApplePublicSearchPager("Example Work", ItunesCatalogEntity.ALBUM, "br", source = source)
        assertEquals(listOf(100L), (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
        assertFalse(pager.snapshot().hasMore)
        pager.loadMore()
        assertEquals(1, recoveryCalls)
    }

    @Test fun exactArtistCollectionRecoveryNeverUsesAmbiguousCreditsOrReordersPublishedRows() = runBlocking {
        val original = "Example Artist Example Work"
        val first = album(1, title = "Example Work Extended").copy(artistName = "Example Artist")
        val recovered = album(2, title = "Example Work (2022 Digital Master) - Single")
            .copy(artistName = "Example Artist")
        var ambiguous = false
        var lookups = 0
        val source = object : ApplePublicSearchSource {
            override val supportsArtistCollectionDiscovery = true
            override suspend fun search(query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult =
                ItunesCatalogResult.Success(if (attribute == null) {
                    if (ambiguous) listOf(first, first.copy(id = 3, artistId = 88)) else listOf(first)
                } else emptyList())
            override suspend fun discoverArtistCollections(originalQuery: String, artistId: Long,
                credit: String, country: String, knownIds: Set<Long>): ItunesCatalogResult {
                lookups++
                assertEquals(original, originalQuery)
                assertEquals(77L, artistId)
                assertEquals("example artist", credit)
                assertEquals("br", country)
                assertEquals(setOf(1L), knownIds)
                return ItunesCatalogResult.Success(listOf(recovered))
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", source = source)
        assertEquals(listOf(1L), (pager.start() as ApplePublicSearchPageLoad.Success).snapshot.visible.map { it.id })
        assertEquals(0, lookups)
        val continuation = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L, 2L), continuation.visible.map { it.id })
        assertEquals(1, lookups)
        assertFalse(continuation.hasMore)
        ambiguous = true
        val second = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", source = source)
        second.start()
        repeat(3) { second.loadMore() }
        assertEquals(1, lookups)
    }

    @Test fun fullWeakAlbumPageDoesNotStrandExactWorkRecovery() = runBlocking {
        val original = "Example Artist Example Work"
        val weak = listOf(
            album(1, title = "Example Work Extended"),
            album(2, title = "Example Work Tribute"),
        ).map { it.copy(artistName = "Example Artist") }
        val recovered = album(3, title = "Example Work").copy(artistName = "Example Artist")
        var lookups = 0
        val source = object : ApplePublicSearchSource {
            override val supportsArtistCollectionDiscovery = true
            override suspend fun search(query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult =
                ItunesCatalogResult.Success(if (attribute == null) weak else emptyList())
            override suspend fun discoverArtistCollections(originalQuery: String, artistId: Long,
                credit: String, country: String, knownIds: Set<Long>): ItunesCatalogResult {
                lookups++
                assertEquals(original, originalQuery)
                assertEquals(setOf(1L, 2L), knownIds)
                return ItunesCatalogResult.Success(listOf(recovered))
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", 2, source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L, 2L), initial.visible.map { it.id })
        assertEquals(0, lookups)
        assertTrue(initial.hasMore)
        val continued = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L, 2L, 3L), continued.visible.map { it.id })
        assertEquals(1, lookups)
        assertFalse(continued.hasMore)
    }

    @Test fun exactArtistDiscoveryUsesSearchConsentAndRejectsForeignCreditsAndKnownCollections() = runBlocking {
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String =
                error("Capability-aware call required")
            override suspend fun get(path: String, parameters: Map<String, String>,
                capability: com.music.bitchord.data.providers.ProviderCapability): String {
                assertEquals("lookup", path)
                assertEquals("77", parameters["id"])
                assertEquals("album", parameters["entity"])
                assertEquals("200", parameters["limit"])
                assertEquals("br", parameters["country"])
                assertEquals(com.music.bitchord.data.providers.ProviderCapability.CATALOG_SEARCH, capability)
                return """{"resultCount":6,"results":[
                    {"wrapperType":"artist","artistId":77,"artistName":"Example Artist"},
                    {"wrapperType":"collection","collectionType":"Album","collectionId":1,"collectionName":"Example Work","artistId":77,"artistName":"Example Artist"},
                    {"wrapperType":"collection","collectionType":"Album","collectionId":2,"collectionName":"Example Work (2020 Digital Master)","artistId":77,"artistName":"Example Artist"},
                    {"wrapperType":"collection","collectionType":"Album","collectionId":3,"collectionName":"Example Work","artistId":88,"artistName":"Example Artist"},
                    {"wrapperType":"collection","collectionType":"Album","collectionId":4,"collectionName":"Example Work","artistId":77,"artistName":"Other Artist"},
                    {"wrapperType":"collection","collectionType":"Album","collectionId":5,"collectionName":"Unrelated","artistId":77,"artistName":"Example Artist"}
                ]}"""
            }
        }
        val source = ItunesPublicSearchSource(ItunesCatalogClient(transport))
        val result = source.discoverArtistCollections("Example Artist Example Work", 77, "example artist",
            "br", setOf(1L)) as ItunesCatalogResult.Success
        assertEquals(listOf(2L), result.items.map { it.id })
    }

    @Test fun commercialEditionWorkKeysPreserveDistinctWorksAndPreferVerifiedTextualMatches() = runBlocking {
        assertEquals("example work", applePublicAlbumWorkKey("Example Work (2022 Digital Master) - Single"))
        assertEquals("example work", applePublicAlbumWorkKey("Example Work [Remastered 2012]"))
        assertEquals("example work", applePublicAlbumWorkKey("Example Work (30th Anniversary Edition)"))
        assertEquals("example work 2011", applePublicAlbumWorkKey("Example Work 2011 - EP"))
        assertEquals("example work (live)", applePublicAlbumWorkKey("Example Work (Live)"))
        assertEquals("example work (tribute)", applePublicAlbumWorkKey("Example Work (Tribute)"))
        assertEquals("example work 2", applePublicAlbumWorkKey("Example Work 2"))
        val qualified = album(2, title = "Example Work (2022 Digital Master) - Single")
            .copy(artistName = "Example Artist")
        val prefix = qualified.copy(id = 1, title = "Example Work Extended")
        val exact = qualified.copy(id = 3, title = "Example Work")
        assertTrue(applePublicQualifiedAlbumMatch("Example Artist Example Work", qualified))
        assertTrue(applePublicQualifiedAlbumMatch("Example Work Example Artist", qualified))
        assertFalse(applePublicQualifiedAlbumMatch("Other Artist Example Work", qualified))
        assertFalse(applePublicQualifiedAlbumMatch("Example Work (2020 Digital Master)", qualified))
        val fake = Fake(ItunesCatalogResult.Success(listOf(prefix, qualified, exact)))
            .apply { expectedQuery = "Example Artist Example Work" }
        val pager = ApplePublicSearchPager(fake.expectedQuery, ItunesCatalogEntity.ALBUM, "br", source = fake)
        assertEquals(listOf(3L, 2L, 1L),
            (pager.start() as ApplePublicSearchPageLoad.Success).snapshot.visible.map { it.id })
    }

    @Test fun yearSpecificSingleIsDiscoverableInBothQueryOrdersButNotAnExactEdition() = runBlocking {
        val year = album(1, title = "Example Work 2011 - EP").copy(artistName = "Example Artist")
        val exact = year.copy(id = 2, title = "Example Work")
        val remaster = year.copy(id = 3, title = "Example Work (2022 Digital Master) - Single")
        assertEquals("example work 2011", applePublicAlbumWorkKey(year.title))
        assertTrue(applePublicRelatedYearEditionMatch("Example Work Example Artist", year))
        assertTrue(applePublicRelatedYearEditionMatch("Example Artist Example Work", year))
        assertEquals(2, applePublicSearchMatchRank("Example Work Example Artist", year))
        assertFalse(applePublicQualifiedAlbumMatch("Example Work Example Artist", year))
        assertFalse(applePublicRelatedYearEditionMatch("Example Work Other Artist", year))
        assertFalse(applePublicRelatedYearEditionMatch("Example Work Example Artist",
            year.copy(title = "Example Work 2011")))
        assertFalse(applePublicRelatedYearEditionMatch("Example Work Example Artist",
            year.copy(title = "Example Work 2011 (Live) - EP")))
        assertFalse(applePublicRelatedYearEditionMatch("Example Work Example Artist",
            year.copy(title = "Example Work 2 - EP")))
        val fake = Fake(ItunesCatalogResult.Success(listOf(year, remaster, exact)))
            .apply { expectedQuery = "Example Work Example Artist" }
        val pager = ApplePublicSearchPager(fake.expectedQuery, ItunesCatalogEntity.ALBUM, "br", source = fake)
        assertEquals(listOf(2L, 3L, 1L),
            (pager.start() as ApplePublicSearchPageLoad.Success).snapshot.visible.map { it.id })
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

    @Test fun uncappedIrrelevantLandingUsesBoundedAttributesButProductiveLandingDoesNot() = runBlocking {
        val fake = Fake(
            ItunesCatalogResult.Success(listOf(album(99).copy(artistName = "Other")), false),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(emptyList()),
                ItunesCatalogSearchAttribute.ARTIST_TERM to ItunesCatalogResult.Success(listOf(album(1))),
            ),
        )
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM, "br", source = fake)
        val snapshot = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L), snapshot.visible.map { it.id })
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ALBUM_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM), fake.calls)
        assertFalse(snapshot.hasMore)

        val productive = Fake(ItunesCatalogResult.Success(listOf(album(1)), false))
        val direct = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM, "br",
            source = productive)
        direct.start()
        direct.loadMore()
        assertEquals(listOf(null), productive.calls)
        assertFalse(direct.snapshot().hasMore)
    }

    @Test fun emptyUncappedArtistSearchDoesNotInventMoreThanOneProbe() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(emptyList(), false))
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ARTIST, "br", source = fake)
        val snapshot = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertTrue(snapshot.visible.isEmpty())
        assertFalse(snapshot.hasMore)
        pager.loadMore()
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ARTIST_TERM), fake.calls)
    }

    @Test fun numericArtistSuffixFallbackOnlyOffersExactNativeNamesAfterLiteralMiss() = runBlocking {
        val native = album(10, title = "Example Artist").copy(
            type = ItunesCatalogItemType.ARTIST, collectionId = null)
        val second = native.copy(id = 11)
        var literalExists = false
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val source = object : ApplePublicSearchSource {
            override suspend fun search(query: String, entity: ItunesCatalogEntity,
                country: String, attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(when {
                    query == "Example Artist (2)" && literalExists ->
                        listOf(native.copy(id = 20, title = query))
                    query == "Example Artist" -> listOf(native, second,
                        native.copy(id = 12, title = "Example Artist Tribute"),
                        native.copy(id = 13, storefront = "us"),
                        native.copy(id = 14, type = ItunesCatalogItemType.ALBUM))
                    else -> emptyList()
                })
            }
        }
        val pager = ApplePublicSearchPager("Example Artist (2)", ItunesCatalogEntity.ARTIST, "br", source = source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(10L, 11L), initial.visible.map { it.id })
        assertTrue(initial.visible.all { it.title == "Example Artist" })
        assertEquals(2, pager.firstDiscoveredRank { it.id == 11L })
        assertEquals(0, pager.firstDiscoveredRank { it.id == 12L })
        assertEquals(3, calls.size)
        pager.loadMore()
        assertEquals(3, calls.size)
        literalExists = true
        calls.clear()
        val literal = ApplePublicSearchPager("Example Artist (2)", ItunesCatalogEntity.ARTIST, "br", source = source)
        assertEquals(20L, (literal.start() as ApplePublicSearchPageLoad.Success).snapshot.visible.single().id)
        assertEquals(1, calls.size)
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


    @Test fun artistQueryPrefersExactNativeNamesButRetainsPrefixDiscoveryWhenNoExactName() =
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
            assertEquals(listOf(14L), result.visible.map { it.id })
            assertEquals(1, result.discoveredCount)
            assertFalse(result.hasMore)
            val prefixes = Fake(ItunesCatalogResult.Success(rows.filter { it.id != 14L }))
                .apply { expectedQuery = "illit" }
            val prefixOnly = ApplePublicSearchPager("illit", ItunesCatalogEntity.ARTIST,
                "br", 24, prefixes)
            assertEquals(listOf(13L),
                (prefixOnly.start() as ApplePublicSearchPageLoad.Success).snapshot.visible.map { it.id })
        }

    @Test fun ambiguousArtistLandingUsesDistinctNativeWorksWithoutMergingArtists() = runBlocking {
        val first = album(1, title = "Example").copy(type = ItunesCatalogItemType.ARTIST,
            artistName = null, artistId = null, collectionId = null)
        val supported = first.copy(id = 2)
        val alternative = first.copy(id = 3)
        val works = listOf(
            album(10, title = "Same work").copy(artistName = "Example", artistId = 1),
            album(11, title = "Same work (Remastered)").copy(artistName = "Example", artistId = 1),
            album(12, title = "Same work (Deluxe)").copy(artistName = "Example", artistId = 1),
            album(20, title = "First work").copy(artistName = "Example", artistId = 2),
            album(21, title = "Second work").copy(artistName = "Example", artistId = 2),
            album(30, country = "us", title = "Foreign").copy(artistName = "Example", artistId = 1),
            album(31, title = "Wrong credit").copy(artistName = "Other", artistId = 1),
        )
        var probes = 0
        val source = object : ApplePublicSearchSource {
            override suspend fun search(query: String, entity: ItunesCatalogEntity,
                country: String, attribute: ItunesCatalogSearchAttribute?) =
                ItunesCatalogResult.Success(listOf(first, supported, alternative))
            override suspend fun artistLandingEvidence(query: String, country: String): ItunesCatalogResult {
                probes++
                assertEquals("Example", query)
                assertEquals("br", country)
                return ItunesCatalogResult.Success(works)
            }
        }
        val pager = ApplePublicSearchPager("Example", ItunesCatalogEntity.ARTIST, "br", 2, source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(2L, 1L), initial.visible.map { it.id })
        assertEquals(3, initial.discoveredCount)
        val continued = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(2L, 1L, 3L), continued.visible.map { it.id })
        assertEquals(1, probes)
    }

    @Test fun songLandingDiversifiesSameCreditAndTitleWithoutMergingTracks() = runBlocking {
        val originals = (1L..7L).map { id ->
            album(id, title = "Song").copy(type = ItunesCatalogItemType.TRACK)
        }
        val live = originals.first().copy(id = 8, title = "Song (Live)")
        val namesake = originals.first().copy(id = 9, artistId = 99)
        val unknown = originals.first().copy(id = 10, artistId = null)
        val all = originals + listOf(live, namesake, unknown)
        val fake = Fake(ItunesCatalogResult.Success(all)).apply { expectedQuery = "Song" }
        val pager = ApplePublicSearchPager("Song", ItunesCatalogEntity.SONG, "br", 5, fake)
        val first = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L, 2L, 9L, 10L, 8L), first.visible.map { it.id })
        assertEquals(all.size, first.discoveredCount)
        val continued = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(first.visible, continued.visible.take(5))
        assertEquals(all.map { it.id }.toSet(), continued.visible.map { it.id }.toSet())
        assertEquals(1, fake.calls.size)
    }

    @Test fun compoundSongLandingKeepsVerifiedCreditAheadOfUnrelatedTitlePrefixes() = runBlocking {
        val originals = (1L..7L).map { id ->
            album(id, title = "Song").copy(type = ItunesCatalogItemType.TRACK, artistName = "Band")
        }
        val live = originals.first().copy(id = 8, title = "Song (Live)")
        val misleading = originals.first().copy(id = 9, title = "Song Band (Tribute)",
            artistId = 99, artistName = "Other")
        val all = originals + listOf(misleading, live)
        val fake = Fake(ItunesCatalogResult.Success(all)).apply { expectedQuery = "Song Band" }
        val pager = ApplePublicSearchPager("Song Band", ItunesCatalogEntity.SONG, "br", 9, fake)
        val snapshot = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(9L, snapshot.visible.last().id)
        assertEquals(all.map { it.id }.toSet(), snapshot.visible.map { it.id }.toSet())
        assertEquals(1, fake.calls.size)
    }

    @Test fun compoundPriorityDoesNotAssumeArtistWhenLiteralTitleAlsoExists() {
        val rows = listOf("Other" to "Song Band", "Band" to "Song", "Band" to "Song (Live)")
        assertEquals(rows, com.music.bitchord.data.catalog.metadata.prioritizeNativeCompoundCredit(
            rows, "song band", { it.second.lowercase() }, { it.first.lowercase() }))
        val ambiguous = listOf("Other" to "A B C D extra", "A" to "B C D", "A B" to "C D")
        assertEquals(ambiguous, com.music.bitchord.data.catalog.metadata.prioritizeNativeCompoundCredit(
            ambiguous, "a b c d", { it.second.lowercase() }, { it.first.lowercase() }))
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

    @Test fun emptyCompoundQueryUsesBoundedTitleProbesButNeverAcceptsWrongCredit() = runBlocking {
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val correct = album(1, title = "Blue Monday - Single").copy(artistName = "New Order")
        val wrong = correct.copy(id = 2, artistName = "Another Artist")
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query to attribute
                assertEquals(ItunesCatalogEntity.ALBUM, entity)
                assertEquals("br", country)
                return ItunesCatalogResult.Success(
                    if (query == "Blue Monday") listOf(wrong, correct) else emptyList()
                )
            }
        }
        val pager = ApplePublicSearchPager("Blue Monday New Order", ItunesCatalogEntity.ALBUM,
            "br", source = source)
        val snapshot = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L), snapshot.visible.map { it.id })
        assertEquals(listOf(
            "Blue Monday New Order" to null,
            "Blue Monday New Order" to ItunesCatalogSearchAttribute.ALBUM_TERM,
            "Blue Monday New Order" to ItunesCatalogSearchAttribute.ARTIST_TERM,
            "Blue Monday" to ItunesCatalogSearchAttribute.ALBUM_TERM,
        ), calls)
        assertFalse(snapshot.hasMore)
        pager.loadMore()
        assertEquals(4, calls.size)
    }

    @Test fun unevenCompoundRecoveryUsesSourceCreditRatherThanBlindMidpoint() = runBlocking {
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val original = "Aphex Twin Come to Daddy"
        val right = album(1, title = "Come to Daddy - EP").copy(artistName = "Aphex Twin")
        val hint = album(2, title = "Unrelated Album").copy(artistName = "Aphex Twin")
        val wrong = right.copy(id = 3, artistName = "Aphex Twins")
        val foreignHint = hint.copy(id = 4, storefront = "us", artistName = "Aphex Twin Come")
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(when {
                    query == original && attribute == null -> listOf(foreignHint, hint)
                    query == "come to daddy" -> listOf(wrong, right)
                    else -> emptyList()
                })
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM,
            "br", source = source)
        val snapshot = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L), snapshot.visible.map { it.id })
        assertEquals(listOf(
            original to null,
            original to ItunesCatalogSearchAttribute.ALBUM_TERM,
            original to ItunesCatalogSearchAttribute.ARTIST_TERM,
            "come to daddy" to ItunesCatalogSearchAttribute.ALBUM_TERM,
        ), calls)
        assertFalse(snapshot.hasMore)
    }

    @Test fun weakWorkLandingPromotesExactTitleBeforePublishingAndKeepsContinuationStable() = runBlocking {
        val query = "Example Artist Example Work"
        val fuzzy = (1L..6L).map { id ->
            album(id, title = "Example Work Variant $id").copy(artistName = "Example Artist")
        }
        val exact = album(20, title = "Example Work").copy(artistName = "Example Artist")
        val wrong = exact.copy(id = 21, artistName = "Example Artists")
        val foreign = exact.copy(id = 22, storefront = "us")
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val source = object : ApplePublicSearchSource {
            override suspend fun search(query: String, entity: ItunesCatalogEntity,
                country: String, attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(when (query to attribute) {
                    "Example Artist Example Work" to null -> fuzzy
                    "example work" to ItunesCatalogSearchAttribute.ALBUM_TERM ->
                        listOf(wrong, foreign, exact)
                    else -> emptyList()
                })
            }
        }
        val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM, "br", source = source)
        val landing = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(20L, landing.visible.first().id)
        assertEquals(listOf(20L) + fuzzy.map { it.id }, landing.visible.map { it.id })
        assertEquals(2, calls.size)
        while (pager.snapshot().hasMore) pager.loadMore()
        assertEquals(landing.visible, pager.snapshot().visible)
        assertEquals(1, calls.count { it == ("example work" to ItunesCatalogSearchAttribute.ALBUM_TERM) })
    }

    @Test fun albumLandingRanksExactWorkBeforeArtistNamesakeAndUsesNativeEditionSupport() = runBlocking {
        val query = "Example Work"
        val artistOnly = (1L..8L).map {
            album(it, title = "Unrelated Work $it").copy(artistName = query, artistId = 88)
        }
        val namesake = album(20, title = query).copy(artistId = 99)
        val exact = album(30, title = query)
        val remaster = album(31, title = "$query (Remastered)")
        val unrelated = album(32, title = "$query (Tribute)")
        val foreign = remaster.copy(id = 33, storefront = "us")
        val otherIdentity = remaster.copy(id = 34, artistId = 100)
        val fake = Fake(ItunesCatalogResult.Success(artistOnly +
            listOf(namesake, exact, remaster, unrelated, foreign, otherIdentity)))
            .apply { expectedQuery = query }
        val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM, "br", source = fake)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(30L, 20L, 31L, 34L), initial.visible.take(4).map { it.id })
        assertEquals(1, fake.calls.size)
        assertEquals(0, pager.firstDiscoveredRank { it.id == 33L })
        assertTrue(initial.visible.any { it.id == 32L })
        assertTrue(initial.visible.any { it.id == 1L })
        pager.loadMore()
        assertEquals(initial.visible, pager.snapshot().visible)
    }

    @Test fun editionEvidenceCannotEraseStrongNativeOrderAcrossManyNamesakes() = runBlocking {
        val query = "Example Work"
        val originals = (1L..8L).map {
            album(it, title = query).copy(artistId = 100 + it, artistName = "Artist $it")
        }
        // Later families with many commercial variants may outrank a nearby
        // candidate, but should not displace the whole native leading page.
        val extras = (4L..8L).flatMap { id ->
            (1L..5L).map { edition ->
                originals[(id - 1).toInt()].copy(
                    id = 1000 + id * 10 + edition, title = "$query (Remastered)",
                )
            }
        }
        val fake = Fake(ItunesCatalogResult.Success(originals + extras))
            .apply { expectedQuery = query }
        val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM, "br", source = fake)
        val first = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertTrue(first.visible.take(5).any { it.id == 1L })
        assertTrue(first.visible.take(5).any { it.id == 2L })
        assertEquals(1, fake.calls.size)
        assertEquals(originals.size + extras.size, first.discoveredCount)
    }

    @Test fun titleOnlyAlbumLandingDiversifiesExactNamesakesWithoutMergingEditions() = runBlocking {
        val query = "Example Work"
        val dominant = (1L..7L).map {
            album(it, title = query).copy(artistName = "First Artist", artistId = 88)
        }
        val other = album(20, title = query).copy(artistName = "Second Artist", artistId = 99)
        val variant = other.copy(id = 21, title = "$query (Remastered)")
        val unknown = other.copy(id = 22, artistId = null)
        val fake = Fake(ItunesCatalogResult.Success(dominant + listOf(other, variant, unknown)))
            .apply { expectedQuery = query }
        val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM, "br", 5, fake)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(1L, 20L, 22L, 2L, 3L), initial.visible.map { it.id })
        while (pager.snapshot().hasMore) pager.loadMore()
        assertEquals(initial.visible, pager.snapshot().visible.take(5))
        assertEquals((dominant + listOf(other, variant, unknown)).map { it.id }.toSet(),
            pager.snapshot().visible.map { it.id }.toSet())
        assertEquals(1, fake.calls.size)
    }

    @Test fun cappedMixedAlbumLandingConsumesTitleFieldBeforePublishingButKeepsArtistListingFast() = runBlocking {
        val weak = (1L..25L).map { album(it, title = "Example Work Variant $it") }
        val exact = album(100, title = "Example Work")
        val calls = mutableListOf<ItunesCatalogSearchAttribute?>()
        val source = object : ApplePublicSearchSource {
            override suspend fun search(query: String, entity: ItunesCatalogEntity,
                country: String, attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult {
                calls += attribute
                return when (attribute) {
                    null -> ItunesCatalogResult.Success(weak, true)
                    ItunesCatalogSearchAttribute.ALBUM_TERM -> ItunesCatalogResult.Success(listOf(exact))
                    else -> ItunesCatalogResult.Success(emptyList())
                }
            }
        }
        val pager = ApplePublicSearchPager("Example Work", ItunesCatalogEntity.ALBUM, "br", source = source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(100L, initial.visible.first().id)
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ALBUM_TERM), calls)
        while (pager.snapshot().hasMore) pager.loadMore()
        assertEquals(1, calls.count { it == ItunesCatalogSearchAttribute.ALBUM_TERM })
        assertEquals(initial.visible, pager.snapshot().visible.take(initial.visible.size))
        calls.clear()
        ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM, "br", source = source).start()
        assertEquals(listOf(null), calls)
    }

    @Test fun weakAlbumLandingRecoversVerifiedCollectionBeforePublishingWithoutRepeatReads() = runBlocking {
        val query = "Example Work"
        val weak = (1L..10L).map { album(it, title = "Example Work Variant $it") }
        val exact = album(100, title = "Example Work")
        var discoveries = 0
        val source = object : ApplePublicSearchSource {
            override val supportsSongCollectionDiscovery = true
            override suspend fun search(query: String, entity: ItunesCatalogEntity,
                country: String, attribute: ItunesCatalogSearchAttribute?): ItunesCatalogResult =
                ItunesCatalogResult.Success(if (attribute == null) weak else emptyList())
            override suspend fun discoverSongCollections(originalQuery: String, workTerm: String,
                country: String, knownIds: Set<Long>): ItunesCatalogResult {
                discoveries++
                assertEquals(query, originalQuery)
                assertEquals(query, workTerm)
                assertEquals(weak.map { it.id }.toSet(), knownIds)
                return ItunesCatalogResult.Success(listOf(exact,
                    exact.copy(id = 101, storefront = "us"),
                    exact.copy(id = 102, title = "Unrelated")))
            }
        }
        val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM, "br", source = source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(100L) + weak.map { it.id }, initial.visible.map { it.id })
        assertEquals(1, discoveries)
        repeat(3) { pager.loadMore() }
        assertEquals(initial.visible, pager.snapshot().visible)
        assertEquals(1, discoveries)
        // A strong artist-only landing should not invoke the song index.
        ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM, "br", source = source).start()
        assertEquals(1, discoveries)
    }

    @Test fun productiveCompoundLandingOffersBoundedFieldContinuationWithoutReordering() = runBlocking {
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val original = "New Order Blue Monday"
        val first = album(10, title = "Blue Monday 1988").copy(artistName = "New Order")
        val recovered = album(11, title = "Blue Monday - Single").copy(artistName = "New Order")
        val wrong = recovered.copy(id = 12, artistName = "New Orders")
        val foreign = recovered.copy(id = 13, storefront = "us")
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(when (query) {
                    original -> listOf(first)
                    "blue monday" -> listOf(wrong, foreign, recovered, first)
                    else -> emptyList()
                })
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", source = source)
        val initial = (pager.start() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(10L), initial.visible.map { it.id })
        assertTrue(initial.hasMore)
        assertEquals(listOf(original to null), calls)
        val continued = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertEquals(listOf(10L, 11L), continued.visible.map { it.id })
        assertEquals("blue monday" to ItunesCatalogSearchAttribute.ALBUM_TERM, calls.last())
        while (pager.snapshot().hasMore) pager.loadMore()
        assertTrue(calls.size <= 3)
        assertEquals(listOf(10L, 11L), pager.snapshot().visible.map { it.id })
    }

    @Test fun artistFieldFallbackUsesActualCreditAfterTitleProbeMisses() = runBlocking {
        val original = "Aphex Twin Come to Daddy"
        val first = album(1, title = "Come to Daddy Tribute").copy(artistName = "Aphex Twin")
        val correct = album(2, title = "Come to Daddy - EP").copy(artistName = "Aphex Twin")
        val wrong = correct.copy(id = 3, artistName = "Aphex Twins")
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(when (query to attribute) {
                    original to null -> listOf(first)
                    "aphex twin" to ItunesCatalogSearchAttribute.ARTIST_TERM -> listOf(wrong, correct)
                    else -> emptyList()
                })
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", source = source)
        pager.start()
        assertTrue(pager.snapshot().hasMore)
        pager.loadMore()
        assertEquals(listOf(1L, 2L), pager.snapshot().visible.map { it.id })
        assertEquals(listOf(original to null,
            "come to daddy" to ItunesCatalogSearchAttribute.ALBUM_TERM,
            "aphex twin" to ItunesCatalogSearchAttribute.ARTIST_TERM), calls)
        assertFalse(pager.snapshot().hasMore)
    }

    @Test fun longCompoundEmptySearchCanProbeWholeWorkRatherThanBisectTitle() = runBlocking {
        val original = "The Smashing Pumpkins Mellon Collie and the Infinite Sadness"
        val work = "Mellon Collie and the Infinite Sadness"
        val correct = album(1, title = work).copy(artistName = "The Smashing Pumpkins")
        val calls = mutableListOf<Pair<String, ItunesCatalogSearchAttribute?>>()
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query to attribute
                return ItunesCatalogResult.Success(if (query == work) listOf(correct) else emptyList())
            }
        }
        val pager = ApplePublicSearchPager(original, ItunesCatalogEntity.ALBUM, "br", source = source)
        assertEquals(listOf(1L), (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.map { it.id })
        assertEquals(work to ItunesCatalogSearchAttribute.ALBUM_TERM, calls.last())
        assertTrue(calls.size <= 5)
    }

    @Test fun sourceCreditHintsSupportUnevenReverseAndAccentedQueriesWithoutSubstringSplits() {
        val credit = album(1).copy(artistName = "João Gilberto")
        assertEquals(listOf("chega de saudade"), applePublicSearchWorkHints(
            "Chega de Saudade Joao Gilberto", listOf(credit)))
        assertEquals(listOf("chega de saudade"), applePublicSearchWorkHints(
            "Joao Gilberto - Chega de Saudade", listOf(credit)))
        assertTrue(applePublicSearchWorkHints("Joao Gilbertos", listOf(credit)).isEmpty())
        assertTrue(applePublicSearchWorkHints("Joao Gilberto", listOf(credit)).isEmpty())
    }

    @Test fun explicitCompoundRecoveryStopsAfterTwoExtraProbesAndRetriesFailedTerm() = runBlocking {
        val calls = mutableListOf<String>()
        var fail = true
        val source = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                calls += query
                if (query == "New Order" && fail) {
                    fail = false
                    return ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)
                }
                return ItunesCatalogResult.Success(emptyList())
            }
        }
        val pager = ApplePublicSearchPager("New Order - Blue Monday", ItunesCatalogEntity.ALBUM,
            "br", source = source)
        assertTrue(pager.start() is ApplePublicSearchPageLoad.Failure)
        assertTrue(pager.snapshot().hasMore)
        val recovered = (pager.loadMore() as ApplePublicSearchPageLoad.Success).snapshot
        assertFalse(recovered.hasMore)
        assertTrue(recovered.visible.isEmpty())
        assertEquals(listOf("New Order - Blue Monday", "New Order - Blue Monday",
            "New Order - Blue Monday", "New Order", "New Order", "Blue Monday"), calls)
        pager.loadMore()
        assertEquals(6, calls.size)
    }

    @Test fun accentsAndArticlesAreNormalizedWithoutRemovingSourcePunctuation() {
        val artist = ItunesCatalogItem(
            ItunesCatalogItemType.ARTIST, 17L, "br", "Beyoncé",
            null, null, null, null, null, null, null, null, null,
        )
        assertEquals(0, applePublicSearchMatchRank("Beyonce", artist))
        assertEquals(1, applePublicSearchMatchRank("beatles",
            artist.copy(title = "The Beatles")))
        assertEquals(2, applePublicSearchMatchRank("beatles",
            artist.copy(title = "Beatles Revival")))
        assertEquals(3, applePublicSearchMatchRank("beatles",
            artist.copy(title = "The Beatles Revival")))
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

    @Test fun conflictingArtistCreditsAcrossApplePagesInvalidateSameCollectionId() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(listOf(album(1)), true),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(
                    listOf(album(1).copy(artistName = "Artist feat. Other"))),
            ))
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM, "br", 1, fake)
        pager.start()
        pager.loadMore()
        assertTrue(pager.snapshot().visible.isEmpty())
        assertTrue(pager.snapshot().coverageUncertain)
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
