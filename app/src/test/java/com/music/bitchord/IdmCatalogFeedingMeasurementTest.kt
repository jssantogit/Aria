package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * CI catalogue feeding measurements. These tests call the same source adapters,
 * source validation, provider dispatch and Home/genre projectors used by the app.
 * HTTP transports are deterministic fixtures: this measures *processing losses*
 * and provider isolation, NOT live API uptime, real popularity or elapsed speed.
 *
 * No user names, real release titles, search terms, API tokens or source IDs
 * are written to artifacts. The fixture identity is checked only in memory.
 */
class IdmCatalogFeedingMeasurementTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val discogs = BuiltInProviderKeys.DISCOGS
    private val today = "2026-11-22"

    private fun measure(
        scenario: String,
        raw: Int,
        parsed: Int,
        eligible: Int,
        displayed: Int,
        requests: Int,
        withArtwork: Int,
        cursorRemaining: Boolean,
        rejected: Int = (raw - eligible).coerceAtLeast(0),
        extras: Map<String, Int> = emptyMap(),
    ) {
        require(Regex("[a-z_]+").matches(scenario))
        require(listOf(raw, parsed, eligible, displayed, requests,
            withArtwork, rejected).all { it >= 0 })
        val fields = linkedMapOf(
            "schema" to 1, "raw_candidates" to raw,
            "parsed_distinct" to parsed, "editorial_eligible" to eligible,
            "displayed_unique" to displayed, "http_or_transport_reads" to requests,
            "displayed_with_artwork" to withArtwork,
            "rejected_or_not_selected" to rejected,
        )
        extras.forEach { (key, value) ->
            require(Regex("[a-z_]+").matches(key) && value >= 0)
            fields[key] = value
        }
        val directory = File(System.getenv("IDM_CATALOG_METRICS_DIR")
            ?: "build/idm-catalog-metrics")
        check(directory.exists() || directory.mkdirs())
        val json = buildString {
            append("{\n  \"mode\": \"fixture-production-pipeline\",\n")
            append("  \"scenario\": \"").append(scenario).append("\",\n")
            fields.entries.forEachIndexed { index, entry ->
                append("  \"").append(entry.key).append("\": ").append(entry.value)
                append(",\n")
            }
            append("  \"cursor_remaining\": ").append(cursorRemaining)
            append("\n}\n")
        }
        File(directory, scenario + ".json").writeText(json)
        println("IDM_CATALOG_MEASURE|" + scenario +
            "|raw=" + raw + "|parsed=" + parsed + "|eligible=" + eligible +
            "|visible=" + displayed + "|requests=" + requests +
            "|artwork=" + withArtwork + "|cursorRemaining=" + cursorRemaining)
    }

    private fun mbId(index: Int): String =
        "%08d-1111-4111-8111-111111111111".format(index)

    private fun mbRows(): List<String> = (1..30).map { index ->
        val id = when {
            index % 11 == 0 -> "invalid-mbid"
            index % 13 == 0 -> mbId(index - 1)
            else -> mbId(index)
        }
        val kind = if (index % 5 == 0) "Single" else "Album"
        """{"id":"${id}","title":"Fixture ${index}","primary-type":"${kind}","first-release-date":"2026-11-01","artist-credit":[{"name":"Fixture Artist"}]}"""
    }

    @Test fun appMusicBrainzHomeScannerConsumesSourcePositionsEvenAfterDateRejections() = runBlocking {
        val seen = mutableListOf<Int>()
        val rows = (1..9).map { index ->
            val date = if (index == 6 || index == 9) "2026-11-18" else "2020-01-01"
            """{"id":"${mbId(index)}","title":"Fixture Album","primary-type":"Album","first-release-date":"$date","artist-credit":[{"name":"Fixture Artist"}]}"""
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    assertEquals("release-group", path)
                    val offset = parameters["offset"]!!.toInt()
                    seen += offset
                    return """{"count":9,"offset":$offset,"release-groups":[""" +
                        rows.drop(offset).take(3).joinToString(",") + "]}"
                }
            },
        ))
        val scanned = scanMusicBrainzRecentEditorial(
            search, "2026-10-01", "2026-11-23", maxPages = 3, pageSize = 3,
        )
        assertEquals(listOf(0, 3, 6), seen)
        assertEquals(3, scanned.attempts)
        assertEquals(9, scanned.sourcePositions)
        assertEquals(9, scanned.parsedRows)
        assertEquals(listOf(mbId(6), mbId(9)),
            scanned.rows.map { it.musicBrainzId })
        assertFalse(scanned.sourceFailure)
        assertFalse(scanned.cursorRemaining)
    }

    @Test fun musicbrainzFullSourcePageKeepsRareAlbumWithoutExtraRequests() = runBlocking {
        var requests = 0
        val releases = (1..100).map { index ->
            val kind = if (index == 100) "Album" else "Single"
            val date = if (index == 100) "2026-11-01" else "2026-11-18"
            """{"id":"${mbId(index)}","title":"Verified source fixture ${index}","primary-type":"${kind}","first-release-date":"${date}","artist-credit":[{"name":"Fixture artist"}]}"""
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>,
                    userAgent: String,
                ): String {
                    requests++
                    assertEquals("release-group", path)
                    assertEquals("0", parameters["offset"])
                    assertEquals("100", parameters["limit"])
                    return """{"count":100,"offset":0,"release-groups":[""" +
                        releases.joinToString(",") + "]}"
                }
            },
        ))
        val result = scanMusicBrainzRecentEditorial(
            search, "2026-10-01", "2026-11-23",
            maxPages = 1, pageSize = 100,
        )
        assertEquals(1, requests)
        assertEquals(100, result.sourcePositions)
        assertEquals(100, result.parsedRows)
        assertFalse(result.cursorRemaining)
        assertFalse(result.sourceFailure)
        assertEquals(40, result.rows.size)
        assertTrue(result.rows.any {
            it.musicBrainzId == mbId(100) && it.releaseKind == "Album"
        })
        assertFalse(canonicalPublicNeedsMoreEditorialRows(result.rows))
        measure("musicbrainz_mixed_source_page",
            raw = 100, parsed = result.parsedRows,
            eligible = result.rows.size, displayed = result.rows.size,
            requests = requests, withArtwork = 0,
            cursorRemaining = result.cursorRemaining)
    }

    @Test fun musicbrainz_isolated_source_pages_flow_through_home_and_genre() = runBlocking {
        val entries = mbRows()
        val requests = mutableListOf<Pair<String, Int>>()
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                assertEquals("release-group", path)
                val offset = parameters["offset"]!!.toInt()
                val limit = parameters["limit"]!!.toInt()
                requests += parameters["query"].orEmpty() to offset
                val page = entries.drop(offset).take(limit)
                return """{"count":30,"offset":""" + offset +
                    ""","release-groups":[""" + page.joinToString(",") + "]}"
            }
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(transport = transport))
        val recent = search.searchRecentReleaseGroups("2026-10-01", today)
        assertTrue(recent is MusicBrainzCatalogSearchResult.Success)
        val full = (recent as MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>).page
        assertEquals(30, full.consumedCount)
        val typed = canonicalPublicReleaseRows(full.items, "2026-10-01", today)
        assertTrue(typed.size >= 20)

        var offset: Int? = 0
        var pages = 0
        val pagedIds = linkedSetOf<String>()
        while (offset != null) {
            val pageResult = search.searchReleaseGroups("Fixture", limit = 10, offset = offset)
            assertTrue(pageResult is MusicBrainzCatalogSearchResult.Success)
            val page = (pageResult as MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>).page
            assertEquals(offset, page.offset)
            page.items.forEach { pagedIds += it.musicBrainzId }
            pages++
            offset = page.nextOffset
            assertTrue("Unbounded MusicBrainz cursor", pages <= 4)
        }
        assertEquals(3, pages)
        assertEquals(full.items.map { it.musicBrainzId }.toSet(), pagedIds)

        val localHome = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = typed,
            localPicks = emptyList(), trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = today,
        )
        val chosen = projectCanonicalHomeEditorial(
            canonicalHomeShelves = localHome, appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(mb),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = today, musicBrainzRows = typed,
        )
        assertEquals(mb, chosen.provider)
        val shelves = (chosen.state as UiState.Success).data
        val visible = shelves.flatMap { it.items }.mapNotNull { it.browseId }.distinct()
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.startsWith("catalog:RELEASE_GROUP:") })
        assertTrue(shelves.any { it.title == "Singles e EPs" })

        val genre = search.searchReleaseGroupsForTag("k-pop")
        assertTrue(genre is MusicBrainzCatalogSearchResult.Success)
        assertTrue(requests.last().first.contains("tag:\"k-pop\""))
        measure("musicbrainz_isolated", entries.size, full.items.size, typed.size,
            visible.size, requests.size, visible.count { id ->
                shelves.any { shelf -> shelf.items.any {
                    it.browseId == id && !it.thumbnailUrl.isNullOrBlank()
                } }
            }, cursorRemaining = offset != null)
    }

    private fun appleRelease(id: Long, tag: String, storefront: String = "br",
        date: String = "2026-11-17"): ItunesEditorialRelease =
        ItunesEditorialRelease(
            appleScopedId = "itunes:" + storefront + ":album:" + id,
            appleCollectionId = id,
            storefront = storefront,
            title = "Fixture release " + id,
            artistName = "Fixture artist " + id,
            artistId = id + 20_000,
            releaseDate = date,
            genre = when (tag) {
                "hip-hop" -> "Hip-Hop/Rap"
                "rnb" -> "R&B/Soul"
                "k-pop" -> "K-Pop"
                else -> tag.replaceFirstChar { it.uppercase() }
            },
            format = if (id % 3L == 0L) ItunesEditorialReleaseFormat.EP
                else ItunesEditorialReleaseFormat.ALBUM,
            artworkUrl = "https://is1-ssl.mzstatic.com/fixture.jpg",
            trackCount = 8,
            tracks = emptyList(),
            musicBrainzReleaseGroupId = null,
            musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun itunes_isolated_discovery_admission_and_native_home_projection() = runBlocking {
        val queries = mutableListOf<String>()
        val updates = mutableListOf<Int>()
        val source = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, storefront ->
                queries += tag
                val n = queries.size
                val raw = (1..16).map { appleRelease(n * 1000L + it, tag) } +
                    appleRelease(9000 + n.toLong(), tag, storefront = "us") +
                    appleRelease(9900 + n.toLong(), tag, date = "2026-12-20")
                assertEquals("br", storefront)
                AppleEditorialLoad.Success(raw, AppleEditorialReadState.COMPLETE)
            },
        )
        val result = source.discover("br", today, onProgress = { updates += it.size })
        assertTrue(result is AppleEditorialLoad.Success)
        val verified = (result as AppleEditorialLoad.Success).releases
        assertEquals(3, queries.size)
        assertEquals(listOf(16, 32, 36), updates)
        assertEquals(48, result.probeReport?.matchingProducts)
        assertEquals(36, verified.size)
        assertTrue(verified.all { it.musicBrainzReleaseGroupId == null })

        val chosen = projectCanonicalHomeEditorial(
            canonicalHomeShelves = emptyList(),
            appleHomeReleases = UiState.Success(verified),
            catalogEditorialOrder = listOf(apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = today,
        )
        assertEquals(apple, chosen.provider)
        val shelves = (chosen.state as UiState.Success).data
        val items = shelves.flatMap { it.items }
        val unique = items.mapNotNull { it.browseId }.distinct()
        assertTrue(unique.size >= 20)
        assertTrue(items.all { item ->
            AppleCatalogBrowseRoute.parse(item.browseId)?.storefront == "br" &&
                item.videoId == null
        })
        measure("itunes_isolated", raw = 54, parsed = 48, eligible = 36,
            displayed = unique.size, requests = queries.size,
            withArtwork = unique.count { key -> items.any {
                it.browseId == key && !it.thumbnailUrl.isNullOrBlank()
            } }, cursorRemaining = true)
    }

    private fun discogsAdmission(): RateLimitedProviderTransport {
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(discogs, caps, ProviderReadiness.READY)),
            choices = caps.associateWith { listOf(ProviderChoice(discogs, true, 0)) },
        ))
        return RateLimitedProviderTransport { gate }
    }

    @Test fun discogs_isolated_nine_home_slices_hydrate_exact_master_release_ids() = runBlocking {
        val index = mutableMapOf<Long, Pair<DiscogsEditorialQuery, Int>>()
        var indexCalls = 0
        var masterCalls = 0
        var releaseCalls = 0
        var observedCandidates = 0
        val genre = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = discogsAdmission(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexCalls++
                    val tagIndex = discogsHomeDiscoveryTags.indexOfFirst {
                        discogsEditorialQueriesForTag(it).firstOrNull() == query
                    }
                    assertTrue(tagIndex >= 0)
                    val ids = (1..5).map { position ->
                        val id = 20_000L + tagIndex * 100L + page * 10L + position
                        index[id] = query to page
                        id
                    }
                    observedCandidates += ids.size
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":""" + page +
                        ""","pages":2,"per_page":12},"results":[""" +
                        ids.joinToString(",") { id ->
                            """{"id":""" + id + ""","type":"master"}"""
                        } + "]}")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterCalls++
                    val (query, page) = index[id] ?: error("Unknown fixture Master")
                    val matching = page == 2
                    return DiscogsLookupResult.FoundMaster(DiscogsMasterRecord(
                        link = DiscogsLink(DiscogsKind.MASTER, id),
                        title = "Fixture master", artistNames = listOf("Fixture artist"),
                        year = 2025, mainReleaseId = id + 50_000,
                        genres = listOf(if (!matching) "Unrelated" else
                            query.requiredGenre ?: if (query.isStyle) "Rock" else query.sourceName),
                        styles = listOf(if (matching && query.isStyle)
                            query.sourceName else "Unrelated"),
                    ))
                }

                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseCalls++
                    val masterId = id - 50_000
                    return DiscogsLookupResult.Found(DiscogsRecord(
                        link = DiscogsLink(DiscogsKind.RELEASE, id),
                        title = "Fixture release", artistNames = listOf("Fixture artist"),
                        country = "Brazil", year = 2025,
                        labels = emptyList(), barcodes = emptyList(),
                        formats = listOf("CD", "Album"),
                        tracklist = emptyList(), unofficial = false,
                        masterId = masterId, artistIds = listOf(id + 90_000),
                    ))
                }
            },
        )
        val verified = mutableListOf<DiscogsEditorialCandidate>()
        for ((position, tag) in discogsHomeDiscoveryTags.withIndex()) {
            val budget = discogsMultiStylePageBudget(
                verifiedMasters = verified.distinctBy { it.masterId }.size,
                remainingQueries = discogsHomeDiscoveryTags.size - position - 1,
                totalSlots = 10,
            ).coerceAtMost(2)
            val query = discogsEditorialQueriesForTag(tag).first()
            val result = genre.discover(query, maxReleases = budget)
            assertTrue("Discogs tag failed: " + tag, result is DiscogsEditorialResult.Verified)
            verified += (result as DiscogsEditorialResult.Verified).releases
        }
        assertEquals(10, verified.distinctBy { it.masterId }.size)
        assertEquals(18, indexCalls)
        assertTrue(masterCalls >= 50)
        val native = discogsEditorialShelves(verified, home = true)
        assertTrue(native.any { it.title == "Artistas para descobrir" })
        val chosen = projectCanonicalHomeEditorial(
            canonicalHomeShelves = emptyList(),
            appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(discogs),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = today,
            discogsEditorial = UiState.Success(native),
        )
        assertEquals(discogs, chosen.provider)
        val shown = (chosen.state as UiState.Success).data
        val releaseItems = shown.flatMap { it.items }
            .filter { it.browseId.orEmpty().startsWith("discogs:release:") }
        val unique = releaseItems.mapNotNull { it.browseId }.distinct()
        assertEquals(10, unique.size)
        assertTrue(releaseItems.all { it.thumbnailUrl == null && it.videoId == null })
        measure("discogs_isolated", observedCandidates, masterCalls,
            verified.distinctBy { it.masterId }.size, unique.size,
            indexCalls + masterCalls + releaseCalls, 0, cursorRemaining = true)
    }

    @Test fun collective_all_six_priorities_and_fallback_keep_native_identity() {
        val album = appleRelease(421L, "pop")
        val mbSource = HomeShelf("Lançamentos", listOf(
            ShelfItem("Fixture MB", "Fixture Artist", null, null,
                "catalog:RELEASE_GROUP:" + mbId(1)),
        ))
        val discogsSource = HomeShelf("Álbuns", listOf(
            ShelfItem("Fixture Discogs", "Fixture Artist", null, null,
                "discogs:release:500"),
        ))
        val providerOrders = listOf(
            listOf(mb, apple, discogs), listOf(mb, discogs, apple),
            listOf(apple, mb, discogs), listOf(apple, discogs, mb),
            listOf(discogs, mb, apple), listOf(discogs, apple, mb),
        )
        var visibleOnFirstOrder = 0
        for (order in providerOrders) {
            val selected = projectCanonicalHomeEditorial(
                canonicalHomeShelves = listOf(mbSource),
                appleHomeReleases = UiState.Success(listOf(album)),
                catalogEditorialOrder = order,
                canonicalWorldwideLoading = false,
                releasesHeading = "Lançamentos",
                discoveryHeading = "Descobrir",
                todayIsoDate = today,
                discogsEditorial = UiState.Success(listOf(discogsSource)),
            )
            assertEquals(order.first(), selected.provider)
            val cards = (selected.state as UiState.Success).data.flatMap { it.items }
            assertTrue(cards.isNotEmpty())
            val prefix = when (order.first()) {
                mb -> "catalog:RELEASE_GROUP:"
                apple -> "itunes:br:album:"
                else -> "discogs:release:"
            }
            assertTrue(cards.all { it.browseId.orEmpty().startsWith(prefix) })
            if (order == providerOrders.first()) {
                visibleOnFirstOrder = cards.mapNotNull { it.browseId }.distinct().size
            }
        }

        val noMb = emptyList<HomeShelf>()
        val noApple = UiState.Success(emptyList<ItunesEditorialRelease>())
        val pending = projectCanonicalHomeEditorial(
            noMb, noApple, listOf(mb, discogs, apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = today, discogsEditorial = UiState.Loading,
        )
        assertEquals(discogs, pending.provider)
        assertEquals(UiState.Loading, pending.state)
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, discogs, apple), false, noMb,
            "Lançamentos", UiState.Loading,
        ))
        val resolved = projectCanonicalHomeEditorial(
            noMb, noApple, listOf(mb, discogs, apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = today,
            discogsEditorial = UiState.Success(listOf(discogsSource)),
        )
        assertEquals(discogs, resolved.provider)

        val genre = projectCanonicalGenreState(
            tag = "k-pop", genrePlaylists = emptyList(), playlistHeading = "Playlists",
            appleByTag = mapOf("k-pop" to noApple),
            musicBrainzRows = UiState.Success(emptyList()),
            ordered = listOf(mb, apple, discogs),
            releasesHeading = "Lançamentos", musicBrainzResultTag = "k-pop",
            todayIsoDate = today,
            discogsByTag = mapOf("k-pop" to UiState.Success(listOf(discogsSource))),
        )
        assertTrue((genre as UiState.Success).data.flatMap { it.items }.all {
            it.browseId.orEmpty().startsWith("discogs:release:")
        })
        // All three independently valid source candidates are available,
        // but the current Home selector deliberately publishes ONE provider.
        // Six different priority scenarios are not six cards on one Home.
        assertEquals(1, visibleOnFirstOrder)
        measure("collective_all_priorities", raw = 3, parsed = 3,
            eligible = 3, displayed = visibleOnFirstOrder,
            requests = 0, withArtwork = 0, cursorRemaining = false,
            rejected = 3 - visibleOnFirstOrder,
            extras = mapOf(
                "priority_permutations" to providerOrders.size,
                "unpublished_valid_candidates" to 3 - visibleOnFirstOrder,
            ))
    }
}
