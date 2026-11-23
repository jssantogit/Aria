package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Controlled 30 / 100 / 300-candidate capacity curves.
 * Uses each actual source cursor/hydration adapter. These measurements
 * distinguish what the provider pagination can traverse from the smaller
 * Home presentation limits; they do NOT assert that a remote provider has
 * 300 relevant results or that any candidate is popular.
 */
class IdmCatalogCapacityCurveTest {
    private fun report(
        provider: String, target: Int, discovered: Int,
        verified: Int, reads: Int, uncertain: Boolean,
    ) {
        val scenario = provider + "_" + target.toString().padStart(3, '0')
        val dir = File(System.getenv("IDM_CATALOG_METRICS_DIR")
            ?: "build/idm-catalog-metrics")
        check(dir.exists() || dir.mkdirs())
        File(dir, "capacity_" + scenario + ".json").writeText(
            """{"schema":1,"mode":"capacity-fixture","provider":"""" +
                provider + """","target":""" + target +
                ""","source_candidates":""" + discovered +
                ""","verified_unique":""" + verified +
                ""","transport_reads":""" + reads +
                ""","coverage_uncertain":""" + uncertain + "}"
        )
        println("IDM_CAPACITY|" + scenario + "|discovered=" + discovered +
            "|verified=" + verified + "|reads=" + reads +
            "|uncertain=" + uncertain)
    }

    private fun mbId(index: Int): String =
        "%08d-2222-4222-8222-222222222222".format(index)

    @Test fun musicbrainzOffsetConsumesAllSourcePagesAt30_100_300() = runBlocking {
        for (target in listOf(30, 100, 300)) {
            var requests = 0
            val source = MusicBrainzCatalogSearch(MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>,
                        userAgent: String,
                    ): String {
                        assertEquals("release-group", path)
                        requests++
                        val offset = parameters["offset"]!!.toInt()
                        val limit = parameters["limit"]!!.toInt()
                        val rows = (offset until minOf(offset + limit, target)).map { n ->
                            """{"id":"${mbId(n + 1)}","title":"Fixture album","primary-type":"Album","first-release-date":"2026-11-01","artist-credit":[{"name":"Fixture artist"}]}"""
                        }
                        return """{"count":""" + target + ""","offset":""" +
                            offset + ""","release-groups":[""" +
                            rows.joinToString(",") + "]}"
                    }
                },
            ))
            val verified = linkedSetOf<String>()
            var offset: Int? = 0
            while (offset != null) {
                val response = source.searchReleaseGroups("Fixture", limit = 100, offset = offset)
                assertTrue(response is MusicBrainzCatalogSearchResult.Success)
                val page = (response as MusicBrainzCatalogSearchResult.Success<
                    MusicBrainzReleaseGroupHit>).page
                assertEquals(offset, page.offset)
                verified.addAll(page.items.map { it.musicBrainzId })
                offset = page.nextOffset
                assertTrue(requests <= 4)
            }
            assertEquals(target, verified.size)
            assertEquals((target + 99) / 100, requests)
            report("musicbrainz", target, target, verified.size,
                requests, uncertain = false)
        }
    }

    private fun appleAlbum(id: Long): ItunesCatalogItem = ItunesCatalogItem(
        type = ItunesCatalogItemType.ALBUM,
        id = id, storefront = "br", title = "Fixture Album",
        artistName = "Fixture Artist", artistId = 999L,
        collectionId = id, storeUrl = null, releaseDate = null,
        genre = "Pop", durationMs = null, trackNumber = null,
        discNumber = null,
    )

    @Test fun itunesLocalPagingAndOfficialFacetsMeasureThe200ResultCeiling() = runBlocking {
        for (target in listOf(30, 100, 300)) {
            var requests = 0
            val source = object : ApplePublicSearchSource {
                override suspend fun search(
                    query: String, entity: ItunesCatalogEntity,
                    country: String, attribute: ItunesCatalogSearchAttribute?,
                ): ItunesCatalogResult {
                    assertEquals("Fixture Artist", query)
                    assertEquals(ItunesCatalogEntity.ALBUM, entity)
                    assertEquals("br", country)
                    requests++
                    return when (attribute) {
                        null -> ItunesCatalogResult.Success(
                            (1..minOf(200, target)).map { appleAlbum(it.toLong()) },
                            possiblyTruncated = target > 200,
                        )
                        ItunesCatalogSearchAttribute.ALBUM_TERM ->
                            ItunesCatalogResult.Success(
                                (201..target).map { appleAlbum(it.toLong()) },
                            )
                        else -> ItunesCatalogResult.Success(emptyList())
                    }
                }
            }
            val pager = ApplePublicSearchPager(
                "Fixture Artist", ItunesCatalogEntity.ALBUM,
                "br", pageSize = 25, source = source,
            )
            assertTrue(pager.start() is ApplePublicSearchPageLoad.Success)
            var scrolls = 0
            while (pager.snapshot().hasMore) {
                assertTrue(pager.loadMore() is ApplePublicSearchPageLoad.Success)
                scrolls++
                assertTrue("Nonterminating local/facet cursor", scrolls <= 20)
            }
            val result = pager.snapshot()
            assertEquals(target, result.discoveredCount)
            assertEquals(target, result.visible.size)
            assertEquals(target > 200, result.coverageUncertain)
            assertEquals(if (target > 200) 3 else 1, requests)
            report("itunes", target, result.discoveredCount,
                result.visible.size, requests, result.coverageUncertain)
        }
    }

    private fun gate(): RateLimitedProviderTransport {
        val provider = BuiltInProviderKeys.DISCOGS
        val caps = setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val policy = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                provider, caps, ProviderReadiness.READY,
            )),
            choices = caps.associateWith {
                listOf(ProviderChoice(provider, true, 0))
            },
        ))
        return RateLimitedProviderTransport { policy }
    }

    @Test fun discogsArtistHydratesMultipleSourceIndexPagesUpTo300Entries() = runBlocking {
        val artistId = 99L
        for (target in listOf(30, 100, 300)) {
            var sourcePages = 0
            var masterReads = 0
            var releaseReads = 0
            val source = DiscogsNativeArtistCatalog(
                enabled = true, admission = gate(),
                limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsPublicArtistTransport {
                    override suspend fun list(
                        artistId: Long, page: Int,
                        userAgent: String,
                    ): DiscogsHttpResponse {
                        assertEquals(99L, artistId)
                        sourcePages++
                        val first = (page - 1) * 6 + 1
                        val last = minOf(first + 5, target)
                        val rows = (first..last).joinToString(",") { id ->
                            """{"id":""" + id + ""","role":"Main","type":"master","title":"Fixture"}"""
                        }
                        return DiscogsHttpResponse(200,
                            """{"pagination":{"page":""" + page +
                            ""","pages":""" + ((target + 5) / 6) +
                            """},"releases":[""" + rows + "]}")
                    }
                },
                editions = object : DiscogsNativeEditionReader {
                    override suspend fun master(id: Long): DiscogsLookupResult {
                        masterReads++
                        return DiscogsLookupResult.FoundMaster(
                            DiscogsMasterRecord(
                                link = DiscogsLink(DiscogsKind.MASTER, id),
                                title = "Fixture", artistNames = listOf("Artist"),
                                year = 2026, mainReleaseId = id + 50_000,
                                genres = listOf("Pop"), styles = emptyList(),
                            ),
                        )
                    }

                    override suspend fun release(id: Long): DiscogsLookupResult {
                        releaseReads++
                        return DiscogsLookupResult.Found(
                            DiscogsRecord(
                                link = DiscogsLink(DiscogsKind.RELEASE, id),
                                title = "Fixture Album", artistNames = listOf("Artist"),
                                country = "Brazil", year = 2026,
                                labels = emptyList(), barcodes = emptyList(),
                                formats = listOf("CD", "Album"),
                                tracklist = emptyList(), unofficial = false,
                                masterId = id - 50_000, artistIds = listOf(artistId),
                            ),
                        )
                    }
                },
            )
            val recovered = mutableListOf<DiscogsNativeArtistRelease>()
            var page = 1
            var hasMore = true
            while (hasMore) {
                val result = source.load(artistId, page)
                assertTrue(result is DiscogsNativeArtistLoad.Success)
                val snapshot = result as DiscogsNativeArtistLoad.Success
                assertFalse(snapshot.retryCurrentPage)
                assertFalse(snapshot.incomplete)
                recovered += snapshot.releases
                hasMore = snapshot.hasMore
                page++
                assertTrue(page <= 101)
            }
            val distinct = recovered.map { it.browseId }.distinct()
            assertEquals(target, distinct.size)
            assertEquals(target, masterReads)
            assertEquals(target, releaseReads)
            assertEquals((target + 5) / 6, sourcePages)
            report("discogs", target, target, distinct.size,
                sourcePages + masterReads + releaseReads,
                uncertain = false)
        }
    }
}
