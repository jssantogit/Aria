package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebChartsTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val charts = ProviderCapability.CHART_MOST_PLAYED
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val search = ProviderCapability.CATALOG_SEARCH
    private fun gate(vararg caps: ProviderCapability) = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key, setOf(charts, editorial, search), ProviderReadiness.READY)),
        caps.associateWith { listOf(ProviderChoice(key, true, 0)) },
    ))

    @Test fun chartAndGenreOperationsRemainIndependentlyAuthorized() = runBlocking {
        var calls = 0
        val transport = AppleMusicWebTransport { capability, path, args ->
            calls++
            when (path) {
                "/v1/catalog/br/genres" -> {
                    assertEquals(editorial, capability)
                    """{"data":[{"id":"21","type":"genres","attributes":{"name":"Rock"}}]}"""
                }
                "/v1/catalog/br/charts" -> {
                    assertEquals(charts, capability)
                    assertEquals("most-played", args["chart"])
                    assertEquals("21", args["genre"])
                    """{"results":{"songs":[{"chart":"most-played","name":"Top Songs","data":[
                        {"id":"5","type":"songs","attributes":{"name":"First","artistName":"Band"}},
                        {"id":"2","type":"songs","attributes":{"name":"Second","artistName":"Other"}}
                    ]}]}}"""
                }
                else -> error("Unexpected path")
            }
        }
        val reader = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(transport), gate(editorial))
        assertEquals(ProviderCallResult.Denied, reader.mostPlayedChart(AppleMusicWebKind.SONG, "br", "21"))
        assertEquals(0, calls)
        val genres = (reader.genres("br") as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Complete
        assertEquals("21", genres.value.single().id)
        val chartReader = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(transport), gate(charts))
        assertEquals(ProviderCallResult.Denied, chartReader.genres("br"))
        val page = (chartReader.mostPlayedChart(AppleMusicWebKind.SONG, "br", "21")
            as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Complete
        assertEquals(listOf("5","2"), page.value.items.map { it.id })
        assertEquals(2, calls)
        assertFalse(AppleMusicWebOperationRegistry.allows(editorial, "/v1/catalog/br/charts"))
        assertFalse(AppleMusicWebOperationRegistry.allows(charts, "/v1/catalog/br/genres"))
    }

    @Test fun chartCursorCannotChangeMarketGenreOrChartType() = runBlocking {
        for (next in listOf("/v1/catalog/us/charts?offset=25",
            "/v1/catalog/br/charts?offset=25&genre=22",
            "/v1/catalog/br/charts?offset=25&chart=top-selling",
            "/v1/catalog/br/charts?offset=25&types=albums")) {
            val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"results":{"songs":[{"chart":"most-played","data":[],"next":"$next"}]}}"""
            })
            assertTrue(runCatching { catalog.mostPlayedChart(AppleMusicWebKind.SONG, "br", "21") }.isFailure)
        }
    }

    @Test fun malformedOrWrongTypeChartCannotBecomeEmptySuccess() = runBlocking {
        for (body in listOf("{}", """{"results":{"songs":[{"data":[{"id":"3","type":"albums","attributes":{"name":"Wrong"}}]}]}}""",
            """{"results":{"songs":[{"data":[]},{"data":[]}]}}""")) {
            val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(
                AppleMusicWebTransport { _, _, _ -> body }), gate(charts))
            assertTrue(gateway.mostPlayedChart(AppleMusicWebKind.SONG,"br") is ProviderCallResult.Failed)
        }
    }

    @Test fun canonicalGenreMatchingNeverSubstitutesParentOrAmbiguousNames() {
        val native = listOf(
            AppleMusicWebGenre("1","br","Rock"),
            AppleMusicWebGenre("2","br","Electronic"),
            AppleMusicWebGenre("3","br","R&B/Soul"),
            AppleMusicWebGenre("4","br","Hard Rock"),
            AppleMusicWebGenre("5","us","House"),
        )
        assertEquals("4", resolveAppleMusicWebCanonicalGenre(native,"br","hard rock")?.id)
        assertEquals("3", resolveAppleMusicWebCanonicalGenre(native,"br","rnb")?.id)
        assertNull(resolveAppleMusicWebCanonicalGenre(native,"br","house"))
        assertNull(resolveAppleMusicWebCanonicalGenre(native + native[0].copy(id="9"),"br","rock"))
    }

    @Test fun albumGenreRelationshipCannotBorrowSearchPermissionOrInventName() = runBlocking {
        var calls = 0
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { cap, path, args ->
            calls++
            assertEquals(editorial, cap)
            assertEquals("/v1/catalog/br/albums/123", path)
            assertEquals("genres", args["include"])
            """{"data":[{"id":"123","type":"albums","relationships":{"genres":{"data":[
              {"id":"77","type":"genres","attributes":{"name":"House"}},
              {"id":"88","type":"genres"}
            ]}}}]}"""
        })
        val denied = AppleMusicWebEditorialGateway(catalog, gate(search))
        assertEquals(ProviderCallResult.Denied, denied.albumGenres("123","br"))
        assertEquals(0, calls)
        val reader = AppleMusicWebEditorialGateway(catalog, gate(editorial))
        val genres = (reader.albumGenres("123","br") as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Complete
        assertEquals(listOf(AppleMusicWebGenre("77","br","House")), genres.value)
        assertNull(resolveAppleMusicWebCanonicalGenre(genres.value, "br", "techno"))
    }

    private fun diagnostic(result: ProviderCallResult<*>): String = when (result) {
        is ProviderCallResult.Failed -> when (val cause = result.cause) {
            is AppleMusicWebHttpException -> "http_" + cause.status
            is AppleMusicWebProtocolException -> when (cause.message) {
                "Missing or ambiguous grouping" -> "grouping_identity"
                "Missing grouping tabs" -> "grouping_tabs"
                "Missing grouping tab" -> "grouping_tab"
                "Missing grouping children" -> "grouping_children"
                "Invalid room identity" -> "room_id"
                "Wrong room linkage type" -> "room_type"
                else -> "protocol"
            }
            is java.io.IOException -> "io"
            is IllegalArgumentException -> "invalid_argument"
            else -> "other"
        }
        ProviderCallResult.Denied -> "denied"
        ProviderCallResult.Stale -> "stale"
        is ProviderCallResult.Success -> "success"
    }

    @Test fun liveCanonicalGenreCoverage() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AMS_GENRE_MATRIX_LIVE") == "1")
        val gate = gate(charts, editorial, search)
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        )), gate)
        val result = gateway.genres("br")
        val native = ((result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
        assertNotNull(native)
        val corpus = com.music.bitchord.ui.idmParityMainGenres + com.music.bitchord.ui.idmParitySubgenres
        assertEquals(15, corpus.size)
        var failed = 0
        for (tag in corpus) {
            var genre = resolveAppleMusicWebCanonicalGenre(native.orEmpty(), "br", tag)
            var discoveryReads = 0
            var candidateRows = 0
            var matchingLabels = 0
            var discoveryFailures = 0
            var provenance = if (genre != null) "native_index" else "unmapped"
            if (genre == null) {
                val linked = mutableListOf<AppleMusicWebGenre>()
                // Search only nominates albums. A genre name and its native ID
                // must be materially present in the exact album relationship.
                val response = gateway.search(AppleMusicWebKind.ALBUM, tag, "br", limit = 25)
                val page = ((response as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
                if (page == null) discoveryFailures++
                candidateRows = page?.items?.size ?: 0
                val matches = page?.items.orEmpty().filter { item ->
                    item.genreNames.any { appleMusicWebGenreNameMatches(it, tag) }
                }
                matchingLabels = matches.size
                for (album in matches.take(5)) {
                    discoveryReads++
                    val detail = gateway.albumGenres(album.id, "br")
                    val values = ((detail as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
                    if (values == null) discoveryFailures++
                    linked += values.orEmpty()
                }
                genre = resolveAppleMusicWebCanonicalGenre(linked, "br", tag)
                if (genre != null) provenance = "album_relationship"
            }
            if (genre == null) {
                println("AMS_CANONICAL_GENRE|tag=$tag|status=unmapped|candidates=$candidateRows|matching_labels=$matchingLabels" +
                    "|discovery_reads=$discoveryReads|discovery_failures=$discoveryFailures")
                continue
            }
            val chart = gateway.mostPlayedChart(AppleMusicWebKind.ALBUM,"br",genre.id,limit=25)
            val page = ((chart as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
            val grouping = gateway.browseRooms("br",genre.id)
            val rooms = ((grouping as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
            if (page == null || rooms == null) failed++
            println("AMS_CANONICAL_GENRE|tag=$tag|status=" +
                (if (page != null && rooms != null) "observed" else "interrupted") +
                "|albums=" + (page?.items?.size ?: 0) + "|rooms=" + (rooms?.size ?: 0) +
                "|provenance=$provenance|discovery_reads=$discoveryReads|discovery_failures=$discoveryFailures" +
                "|chart_reason=" + diagnostic(chart) + "|grouping_reason=" + diagnostic(grouping))
        }
        assertEquals(0, failed)
    }

    @Test fun liveChartsAndGenres() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AMS_CHARTS_LIVE") == "1")
        val gate = gate(charts, editorial)
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        )), gate)
        // The first sample is native most-played, not Daily Top 100 playlists.
        val markets = listOf("br","us","gb","fr","de","jp","kr","in","mx","ca")
        var failures = 0
        for (market in markets) {
            val result = gateway.mostPlayedChart(AppleMusicWebKind.SONG, market, limit = 25)
            val page = ((result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
            val good = page != null && page.items.isNotEmpty()
            if (!good) failures++
            println("AMS_CHART|market=$market|rows=" + (page?.items?.size ?: 0) + "|complete=$good")
        }
        val response = gateway.genres("br")
        val genres = ((response as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
        if (genres.isNullOrEmpty()) failures++
        println("AMS_GENRES|market=br|rows=" + (genres?.size ?: 0))
        // Use source-returned IDs, not assumed mappings of local genres.
        for (genre in genres.orEmpty().take(5)) {
            for (kind in listOf(AppleMusicWebKind.SONG, AppleMusicWebKind.ALBUM)) {
                val result = gateway.mostPlayedChart(kind, "br", genre.id, limit = 25)
                val page = ((result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
                if (page == null) failures++
                println("AMS_GENRE_CHART|kind=" + kind.name + "|rows=" + (page?.items?.size ?: 0) +
                    "|complete=" + (page != null))
            }
        }
        assertEquals(0, failures)
    }
}
