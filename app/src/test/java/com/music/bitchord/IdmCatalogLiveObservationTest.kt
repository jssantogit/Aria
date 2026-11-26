package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * OPTIONAL non-gating network observation for the CI measurement workflow.
 *
 * Requests pass through the actual MusicBrainz, Apple and Discogs source
 * adapters and the same canonical presentation. Live responses are allowed
 * to be empty, rate-limited or unavailable: this test reports their status
 * rather than pretending a fixture proves network availability. A Discogs
 * token is taken from CI secrets, never a source file or test fixture.
 *
 * Aggregate observations retain no raw JSON bodies, queries, names, IDs or tokens.
 * The opt-in fixed-public-corpus relevance review is separate: only bounded
 * displayed title/credit, ordinal, kind, and existing target evidence; never
 * native IDs, raw responses, arbitrary user queries, credentials or URLs.
 */
class IdmCatalogLiveObservationTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val discogs = BuiltInProviderKeys.DISCOGS

    private fun gateFor(
        provider: ProviderKey, capabilities: Set<ProviderCapability>,
    ): ProviderPolicyGate = ProviderPolicyGate(ProviderCapabilityPolicy(
        descriptors = listOf(
            ProviderDescriptor(provider, capabilities, ProviderReadiness.READY),
        ),
        choices = capabilities.associateWith { listOf(ProviderChoice(provider, true, 0)) },
    ))

    private data class Sample(
        val status: String,
        val raw: Int? = null,
        val parsed: Int? = null,
        val eligible: Int = 0,
        val visible: Int = 0,
        val requests: Int? = null,
        val artwork: Int = 0,
        val shelves: List<HomeShelf> = emptyList(),
        val counts: Map<String, Int> = emptyMap(),
    )

    private fun appendAggregateCounts(
        lines: MutableMap<String, String>, counts: Map<String, Int>,
    ) {
        counts.forEach { (key, value) ->
            // Numeric HTTP classifications (413, 5xx) are valid metric
            // names. Keep the JSON key grammar restricted and counts unsigned.
            require(Regex("[a-z_][a-z0-9_]*").matches(key)) {
                "Invalid aggregate metric name"
            }
            require(value >= 0) { "Negative aggregate metric count" }
            require(key !in lines) { "Duplicate aggregate metric name" }
            lines[key] = value.toString()
        }
    }

    @Test fun aggregateReportAcceptsHttpStatusCountersWithoutLiveNetwork() {
        val lines = linkedMapOf("schema" to "1")
        appendAggregateCounts(lines, mapOf(
            "detail_upstream_http_413" to 0,
            "detail_http_5xx" to 2,
            "detail_io_failures" to 1,
        ))
        assertEquals(mapOf("schema" to "1", "detail_upstream_http_413" to "0",
            "detail_http_5xx" to "2", "detail_io_failures" to "1"), lines)
        for (invalid in listOf(
            mapOf("bad-key" to 1), mapOf("bad\"key" to 1),
            mapOf("9invalid" to 1), mapOf("detail_http_5xx" to 1),
            mapOf("negative_count" to -1),
        )) {
            assertTrue(runCatching { appendAggregateCounts(lines, invalid) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(4, lines.size)
    }

    private fun report(scenario: String, sample: Sample) {
        val dir = File(System.getenv("IDM_CATALOG_METRICS_DIR")
            ?: "build/idm-catalog-metrics")
        check(dir.exists() || dir.mkdirs())
        val lines = linkedMapOf(
            "schema" to "1",
            "raw_candidates" to (sample.raw?.toString() ?: "null"),
            "parsed_distinct" to (sample.parsed?.toString() ?: "null"),
            "editorial_eligible" to sample.eligible.toString(),
            "displayed_unique" to sample.visible.toString(),
            "http_or_transport_reads" to (sample.requests?.toString() ?: "null"),
            "displayed_with_artwork" to sample.artwork.toString(),
        )
        appendAggregateCounts(lines, sample.counts)
        val json = buildString {
            append("{\n  \"mode\": \"live-network-observation\",\n")
            append("  \"scenario\": \"").append(scenario).append("\",\n")
            append("  \"status\": \"").append(sample.status).append("\",\n")
            for ((name, value) in lines) {
                append("  \"").append(name).append("\": ").append(value).append(",\n")
            }
            append("  \"source_payloads_retained\": false\n}\n")
        }
        File(dir, "live_" + scenario + ".json").writeText(json)
        println("IDM_CATALOG_LIVE|" + scenario + "|status=" + sample.status +
            "|eligible=" + sample.eligible + "|visible=" + sample.visible +
            "|reads=" + (sample.requests?.toString() ?: "unknown"))
    }

    private fun cards(shelves: List<HomeShelf>): List<com.music.bitchord.data.model.ShelfItem> =
        shelves.flatMap { it.items }.distinctBy { it.browseId }

    /**
     * Mirror canonicalPublicReleaseRows' ordered admission gates, reporting
     * only aggregate first-rejection reasons. No source titles or IDs leave
     * the test process. A short source sample cannot prove global coverage.
     */
    private fun musicBrainzRejectionCounts(
        hits: List<MusicBrainzReleaseGroupHit>,
        from: String,
        through: String,
        published: Int,
    ): Map<String, Int> {
        val counts = linkedMapOf(
            "rejected_date" to 0,
            "rejected_artist_credit" to 0,
            "rejected_unsafe_title" to 0,
            "rejected_release_type" to 0,
            "rejected_duplicate_id" to 0,
            "rejected_display_cap" to 0,
        )
        val dateReasons = linkedMapOf(
            "date_missing" to 0,
            "date_year_only" to 0,
            "date_month_only" to 0,
            "date_invalid" to 0,
            "date_outside_window" to 0,
        )
        val identities = mutableSetOf<String>()
        var admitted = 0
        for (hit in hits) {
            val date = hit.firstReleaseDate
            val dateReason = when {
                date.isNullOrBlank() -> "date_missing"
                Regex("^\\d{4}$").matches(date) -> "date_year_only"
                Regex("^\\d{4}-\\d{2}$").matches(date) -> "date_month_only"
                date.length != 10 || runCatching { LocalDate.parse(date) }.isFailure ->
                    "date_invalid"
                date < from || date > through -> "date_outside_window"
                else -> null
            }
            val reason = when {
                dateReason != null -> "rejected_date"
                hit.artistDisplay.isNullOrBlank() -> "rejected_artist_credit"
                !canonicalStreamingTitleIsSafe(hit.title) -> "rejected_unsafe_title"
                !CanonicalEditorialDiscography.classify(
                    hit.primaryType, hit.secondaryTypes,
                ).isPublishable -> "rejected_release_type"
                !identities.add(hit.musicBrainzId.lowercase(java.util.Locale.ROOT)) ->
                    "rejected_duplicate_id"
                else -> null
            }
            if (dateReason != null) {
                dateReasons[dateReason] = dateReasons.getValue(dateReason) + 1
            }
            if (reason == null) admitted++
            else counts[reason] = counts.getValue(reason) + 1
        }
        counts["rejected_display_cap"] = (admitted - published).coerceAtLeast(0)
        check(counts.values.sum() == hits.size - published) {
            "MusicBrainz source and Home rejection counts diverged"
        }
        check(dateReasons.values.sum() == counts.getValue("rejected_date")) {
            "MusicBrainz date precision breakdown diverged"
        }
        return counts + dateReasons
    }

    @Test fun live_sources_and_collective_fallback_use_canonical_pipeline() = runBlocking {
        assumeTrue("Only the observational CI job may access remote services",
            System.getenv("IDM_CATALOG_LIVE") == "1")
        val now = LocalDate.now(ZoneOffset.UTC)
        val date = now.toString()
        val startDate = now.minusDays(50).toString()

        val mbGate = gateFor(mb, setOf(ProviderCapability.CATALOG_SEARCH))
        val musicBrainz = MusicBrainzCatalogSearch(
            MusicBrainzClient(admission = { mbGate }),
        )
        val mbResult = withTimeoutOrNull(80_000L) {
            try { musicBrainz.searchRecentReleaseGroups(
                startDate, date, limit = 60,
            ) } catch (_: Exception) { null }
        }
        val mbTyped = (mbResult as? MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>)
            ?.page?.items?.let { hits ->
                canonicalPublicReleaseRows(hits, startDate, date)
            }.orEmpty()
        val mbHome = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = mbTyped,
            localPicks = emptyList(), trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = date,
        )
        val mbProjected = projectCanonicalHomeEditorial(
            canonicalHomeShelves = mbHome,
            appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(mb),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = date, musicBrainzRows = mbTyped,
        )
        val mbShelves = (mbProjected.state as? UiState.Success)?.data.orEmpty()
        val mbItems = cards(mbShelves)
        val mbPage = (mbResult as? MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>)?.page
        val rejectionCounts = mbPage?.items?.let {
            musicBrainzRejectionCounts(it, startDate, date, mbTyped.size)
        }.orEmpty()
        report("musicbrainz", Sample(
            status = when {
                mbResult is MusicBrainzCatalogSearchResult.Failure -> "source_error"
                mbResult == null -> "timeout_or_unavailable"
                mbItems.isEmpty() -> "empty_after_filters"
                else -> "verified"
            },
            raw = mbPage?.consumedCount, parsed = mbPage?.items?.size,
            eligible = mbTyped.size, visible = mbItems.size, requests = 1,
            artwork = mbItems.count { !it.thumbnailUrl.isNullOrBlank() },
            shelves = mbShelves, counts = rejectionCounts,
        ))

        // Repeat the *actual* app's narrow / wide / historical progression
        // through the same bounded raw-offset scanner that MainViewModel uses.
        // The separate 50-day single-page report above isolates date defects;
        // this report measures the complete Home feeding decision.
        val mbLandingTags = mutableMapOf<String, Set<String>>()
        fun observeMbTags(rows: List<MusicBrainzReleaseGroupHit>) {
            rows.forEach { row ->
                mbLandingTags[row.musicBrainzId] =
                    mbLandingTags[row.musicBrainzId].orEmpty() + row.editorialGenreTags
            }
        }
        val narrow = scanMusicBrainzRecentEditorial(
            musicBrainz, now.minusDays(30).toString(), date, maxPages = 3,
            onSourcePage = ::observeMbTags,
        )
        val wide = if (canonicalPublicNeedsMoreEditorialRows(narrow.rows))
            scanMusicBrainzRecentEditorial(
                musicBrainz, now.minusDays(90).toString(), date, maxPages = 2,
                onSourcePage = ::observeMbTags,
            ) else null
        val currentRows = (narrow.rows + wide?.rows.orEmpty())
            .distinctBy { it.musicBrainzId }
        val historical = if (canonicalPublicNeedsMoreEditorialRows(currentRows))
            scanMusicBrainzRecentEditorial(
                musicBrainz, now.minusDays(365).toString(),
                now.minusDays(91).toString(), maxPages = 1,
                onSourcePage = ::observeMbTags,
            ) else null
        val initialMbRows = (currentRows + historical?.rows.orEmpty())
            .distinctBy { it.musicBrainzId }
        val scans = listOfNotNull(narrow, wide, historical)
        val supplement = if (scans.any { it.sourceFailure })
            MusicBrainzHomeGenreSupplement(initialMbRows, 0, 0, 0, false)
        else supplementMusicBrainzHomeGenres(
            musicBrainz, initialMbRows, date, onSourcePage = ::observeMbTags,
        )
        val appMbRows = supplement.rows
        val appMbHome = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = appMbRows,
            localPicks = emptyList(), trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = date,
        )
        val appMbSelected = projectCanonicalHomeEditorial(
            canonicalHomeShelves = appMbHome,
            appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(mb),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = date, musicBrainzRows = appMbRows,
        )
        val appMbShelves = (appMbSelected.state as? UiState.Success)
            ?.data.orEmpty()
        val appMbItems = cards(appMbShelves)
        report("musicbrainz_app_flow", Sample(
            status = when {
                scans.any { it.sourceFailure } || supplement.sourceFailure -> "partial_source_failure"
                appMbItems.isEmpty() -> "empty_after_filters"
                else -> "verified"
            },
            raw = scans.sumOf { it.sourcePositions } + supplement.sourcePositions,
            parsed = scans.sumOf { it.parsedRows } + supplement.parsedRows,
            eligible = appMbRows.size, visible = appMbItems.size,
            requests = scans.sumOf { it.attempts } + supplement.attempts,
            artwork = appMbItems.count { !it.thumbnailUrl.isNullOrBlank() },
            counts = mapOf(
                "narrow_window_reads" to narrow.attempts,
                "wide_window_reads" to (wide?.attempts ?: 0),
                "historical_window_reads" to (historical?.attempts ?: 0),
                "genre_supplement_reads" to supplement.attempts,
                "raw_cursor_windows_open" to scans.count { it.cursorRemaining },
                "verified_landing_genres" to musicBrainzHomeVerifiedGenreCount(
                    mbLandingTags, appMbItems.mapNotNull { it.browseId }.toSet(),
                ),
            ),
            shelves = appMbShelves,
        ))

        // One real Home scroll window, using the exact cursor chosen by
        // initialCanonicalHomePageSnapshot. This is a bounded observation,
        // never an assertion that a source has any specific number of albums.
        val mbInitialPaging = initialCanonicalHomePageSnapshot(
            date, narrow, wide, historical,
        ).copy(rows = appMbRows)
        val homeWindow = mbInitialPaging.nextWindow
        val homeOffset = homeWindow?.let(mbInitialPaging.offsets::get)
        val homeWindowDates = homeWindow?.let {
            canonicalHomeWindowDates(date, it)
        }
        val mbHomeMore = if (appMbItems.isNotEmpty() &&
            homeWindow != null && homeOffset != null && homeWindowDates != null
        ) withTimeoutOrNull(35_000L) {
            try {
                scanMusicBrainzRecentEditorial(
                    musicBrainz, homeWindowDates.first, homeWindowDates.second,
                    maxPages = 1, startOffset = homeOffset,
                    includeCoarseDatesForUnrankedHome = true,
                )
            } catch (_: Exception) { null }
        } else null
        val mbHomeNext = if (mbHomeMore != null &&
            homeWindow != null && homeOffset != null
        ) advanceCanonicalHomePage(
            mbInitialPaging, homeWindow, homeOffset, mbHomeMore,
        ) else null
        val mbHomeExtra = mbHomeNext?.let {
            canonicalHomeContinuationShelf(mb, appMbShelves, it)
        }
        val mbInitiallyVisible = cards(appMbShelves + listOfNotNull(
            canonicalHomeContinuationShelf(mb, appMbShelves, mbInitialPaging),
        )).mapNotNull { it.browseId }.toSet()
        val mbNewVisible = mbHomeExtra?.items.orEmpty().filter {
            it.browseId !in mbInitiallyVisible
        }
        val mbCumulativeReleases = (mbInitiallyVisible + mbNewVisible.mapNotNull { it.browseId })
            .count { it.startsWith("catalog:RELEASE_GROUP:") || it.startsWith("catalog:RELEASE:") }
        report("musicbrainz_home_capacity_30", Sample(
            status = if (mbCumulativeReleases >= 30) "target_reached" else "below_target",
            eligible = mbCumulativeReleases, visible = mbCumulativeReleases,
            counts = mapOf(
                "verified_target" to 30,
                "source_gestures" to if (mbHomeNext != null) 1 else 0,
                "verified_landing_genres" to musicBrainzHomeVerifiedGenreCount(
                    mbLandingTags, mbInitiallyVisible,
                ),
            ),
        ))
        report("musicbrainz_home_granular", Sample(
            status = when {
                appMbItems.isEmpty() -> "initial_no_editorial"
                !mbInitialPaging.hasMore -> "no_source_cursor"
                mbHomeMore == null -> "timeout_or_unavailable"
                mbHomeMore.sourceFailure -> "retryable_interruption"
                mbNewVisible.isEmpty() -> "no_new_verified_records"
                else -> "verified"
            },
            raw = mbHomeMore?.sourcePositions,
            parsed = mbHomeMore?.parsedRows,
            eligible = mbHomeMore?.rows?.size ?: 0,
            visible = mbNewVisible.size,
            requests = mbHomeMore?.attempts,
            artwork = mbNewVisible.count { !it.thumbnailUrl.isNullOrBlank() },
            counts = mapOf(
                "window_index" to (homeWindow ?: 3),
                "cursor_after" to (mbHomeNext?.offsets
                    ?.getOrNull(homeWindow ?: 3) ?: 0),
                "retry_needed" to if (mbHomeMore?.sourceFailure == true) 1 else 0,
            ),
        ))

        val appleCaps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val appleGate = gateFor(apple, appleCaps)
        val appleTransport = KtorItunesCatalogTransport(
            admission = RateLimitedProviderTransport { appleGate },
        )
        val appleRepository = AppleEditorialCatalogRepository(
            ItunesCatalogClient(transport = appleTransport),
        )
        val appleGenre = AppleExploreGenreDiscovery(
            nativeSource = AppleNativeGenreSearchSource { term, storefront ->
                appleRepository.searchAlbums(
                    query = term, storefront = storefront, limit = 200,
                    capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    attribute = ItunesCatalogSearchAttribute.GENRE_INDEX,
                )
            },
        )
        val appleHome = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, storefront ->
                appleGenre.discoverNative(tag, storefront)
            },
        )
        var verifiedAppleHomePool: List<ItunesEditorialRelease> = emptyList()
        val appleResult = withTimeoutOrNull(95_000L) {
            try { appleHome.discover(
                "br", date, onVerifiedPool = { verifiedAppleHomePool = it },
            ) } catch (_: Exception) { null }
        }
        val appleReleases =
            (appleResult as? AppleEditorialLoad.Success)?.releases.orEmpty()
        val appleShelves = appleFirstHomeShelves(
            appleAlbums = appleReleases, existingLocalShelves = emptyList(),
            releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
            todayIsoDate = date,
        )
        val appleItems = cards(appleShelves)
        val appleProbes = (appleResult as? AppleEditorialLoad.Success)?.probeReport
        val appleShownIds = appleItems.mapNotNull { it.browseId }.toSet()
        val appleShownGenres = AppleNativeHomeDiscovery.sampleTags(now).count { tag ->
            appleReleases.any { row ->
                row.appleScopedId in appleShownIds && AppleExploreGenrePolicy.matches(tag, row.genre)
            }
        }
        report("itunes", Sample(
            status = when {
                appleResult is AppleEditorialLoad.Failure -> "source_error"
                appleResult == null -> "timeout_or_unavailable"
                appleItems.isEmpty() -> "empty_after_filters"
                else -> "verified"
            },
            // Native genre search already filters the raw storefront response.
            // Its original HTTP row count is unavailable in this interface.
            raw = null, parsed = appleProbes?.matchingProducts,
            eligible = appleReleases.size, visible = appleItems.size,
            requests = appleProbes?.probesMade,
            artwork = appleItems.count { !it.thumbnailUrl.isNullOrBlank() },
            shelves = appleShelves,
            counts = mapOf("verified_landing_genres" to appleShownGenres),
        ))

        val appleHomeUnshown = appleHomeUnshownVerified(
            verifiedAppleHomePool, appleShelves,
        )
        val appleHomeMore = appleHomeContinuationShelf(
            selectedProviderIsApple = true,
            initialShelves = appleShelves,
            sourcePool = verifiedAppleHomePool,
            revealedCount = 24,
        )
        report("itunes_home_granular", Sample(
            status = when {
                appleResult !is AppleEditorialLoad.Success -> "initial_unverified"
                appleHomeUnshown.isEmpty() -> "no_unshown_verified_products"
                else -> "verified"
            },
            raw = null,
            parsed = verifiedAppleHomePool.size,
            eligible = appleHomeUnshown.size,
            visible = appleHomeMore?.items?.size ?: 0,
            requests = 0,
            artwork = appleHomeMore?.items?.count {
                !it.thumbnailUrl.isNullOrBlank()
            } ?: 0,
            counts = mapOf(
                "original_editorial_products" to appleReleases.size,
                "source_pool_distinct" to verifiedAppleHomePool.size,
                "first_reveal_budget" to 24,
            ),
        ))

        val appleCumulativeReleases = cards(appleShelves + listOfNotNull(appleHomeMore))
            .count { it.browseId.orEmpty().startsWith("itunes:br:album:") }
        report("itunes_home_capacity_30", Sample(
            status = if (appleCumulativeReleases >= 30) "target_reached" else "below_target",
            eligible = appleCumulativeReleases, visible = appleCumulativeReleases,
            counts = mapOf("verified_target" to 30, "source_gestures" to 1),
        ))

        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(String::isNotBlank)
        // Bounded live transport counters, without titles, IDs, search bodies
        // or token values. Distinguish index discovery from costly hydration.
        var discogsIndexReads = 0
        var discogsIndexCandidates = 0
        var discogsMasterReads = 0
        var discogsMasterFound = 0
        var discogsReleaseReads = 0
        var discogsReleaseFound = 0
        var discogsMasterRejected = 0
        var discogsReleaseRejected = 0
        // Source-only aggregate audit. Never export raw index rows, titles,
        // IDs, artist names or authentication material in CI artifacts.
        val discogsIndexHints = mutableMapOf<Long, DiscogsEditorialMasterIndexHint>()
        var indexHintsWithMainRelease = 0
        var indexHintsWithGenresAndStyles = 0
        var checkedMasterHints = 0
        var matchingMainReleaseHints = 0
        var matchingGenreHints = 0
        var matchingStyleHints = 0
        var discogsGranularScanner: DiscogsNativeGenreEditorial? = null
        var discogsArtistScanner: DiscogsNativeArtistCatalog? = null
        var artistIndexReads = 0
        var artistIndexCandidates = 0
        var artistIndexMasterCandidates = 0
        var artistIndexMainReleaseHints = 0
        var firstInitialVerifiedNs: Long? = null
        var initialLandingCursor: DiscogsEditorialCursor? = null
        val initialStartedNs = System.nanoTime()
        val discogsRead = if (token == null) null else {
            val caps = setOf(ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY)
            val discogsGate = gateFor(discogs, caps)
            val guard = RateLimitedProviderTransport { discogsGate }
            val liveIndex = KtorDiscogsGenreSearchTransport()
            // Count actual HTTP transport calls, not methods answered by the
            // new positive source evidence cache. A cached Master/Release
            // must never be misreported as another network request.
            fun countDetailTransport(path: String) {
                if (path.startsWith("masters/")) discogsMasterReads++
                if (path.startsWith("releases/")) discogsReleaseReads++
            }
            val liveDetailTransport = object : DiscogsTransport {
                private val upstream = KtorDiscogsTransport()
                override val supportsTokenAuthorization: Boolean get() = true
                override suspend fun get(path: String, userAgent: String):
                    DiscogsHttpResponse {
                    countDetailTransport(path)
                    return upstream.get(path, userAgent)
                }
                override suspend fun getAuthenticated(
                    path: String, userAgent: String, token: String,
                ): DiscogsHttpResponse {
                    countDetailTransport(path)
                    return upstream.getAuthenticated(path, userAgent, token)
                }
            }
            val editions = DiscogsNativeEditionClient(
                DiscogsClient(
                    enabled = true,
                    capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    admission = guard,
                    transport = liveDetailTransport,
                    // The live Home runs the same token-bearing Master and
                    // Release hydration as the actual Android session.
                    tokenProvider = { token },
                ),
            )
            val liveArtistIndex = KtorDiscogsPublicArtistTransport(perPage = 6)
            discogsArtistScanner = DiscogsNativeArtistCatalog(
                enabled = true, admission = guard, tokenProvider = { token },
                editions = editions,
                transport = object : DiscogsPublicArtistTransport {
                    override val supportsTokenAuthorization: Boolean get() = true
                    private fun counted(response: DiscogsHttpResponse, page: Int):
                        DiscogsHttpResponse {
                        if (response.status == 200) {
                            val entries = DiscogsPublicArtistParsing.page(response.body, page)
                                ?.entries.orEmpty()
                            artistIndexCandidates += entries.size
                            artistIndexMasterCandidates += entries.count { it.kind == DiscogsKind.MASTER }
                            artistIndexMainReleaseHints += entries.count {
                                it.kind == DiscogsKind.MASTER && it.mainReleaseId != null
                            }
                        }
                        return response
                    }
                    override suspend fun list(
                        artistId: Long, page: Int, userAgent: String,
                    ): DiscogsHttpResponse {
                        artistIndexReads++
                        return counted(liveArtistIndex.list(artistId, page, userAgent), page)
                    }
                    override suspend fun listAuthenticated(
                        artistId: Long, page: Int, userAgent: String, token: String,
                    ): DiscogsHttpResponse {
                        artistIndexReads++
                        return counted(
                            liveArtistIndex.listAuthenticated(artistId, page, userAgent, token),
                            page,
                        )
                    }
                },
            )
            val native = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { token },
                admission = guard,
                transport = object : DiscogsGenreSearchTransport {
                    override suspend fun search(
                        query: DiscogsEditorialQuery,
                        token: String, userAgent: String, page: Int,
                    ): DiscogsHttpResponse {
                        discogsIndexReads++
                        val response = liveIndex.search(query, token, userAgent, page)
                        if (response.status == 200) {
                            val index = DiscogsEditorialMasterParsing.page(response.body, page)
                            discogsIndexCandidates += index?.masterIds?.size ?: 0
                            index?.hints?.forEach { (masterId, hint) ->
                                discogsIndexHints[masterId] = hint
                                if (hint.mainReleaseId != null) indexHintsWithMainRelease++
                                if (hint.genres != null && hint.styles != null)
                                    indexHintsWithGenresAndStyles++
                            }
                        }
                        return response
                    }
                },
                editions = object : DiscogsNativeEditionReader {
                    override suspend fun master(id: Long): DiscogsLookupResult {
                        return editions.master(id).also {
                            if (it !is DiscogsLookupResult.FoundMaster)
                                discogsMasterRejected++
                            if (it is DiscogsLookupResult.FoundMaster) {
                                discogsMasterFound++
                                val hint = discogsIndexHints[id]
                                if (hint != null) {
                                    checkedMasterHints++
                                    val record = it.record
                                    if (hint.mainReleaseId != null &&
                                        hint.mainReleaseId == record.mainReleaseId
                                    ) matchingMainReleaseHints++
                                    if (hint.genres != null &&
                                        hint.genres.map(String::lowercase).toSet() ==
                                            record.genres.map(String::lowercase).toSet()
                                    ) matchingGenreHints++
                                    if (hint.styles != null &&
                                        hint.styles.map(String::lowercase).toSet() ==
                                            record.styles.map(String::lowercase).toSet()
                                    ) matchingStyleHints++
                                }
                            }
                        }
                    }

                    override suspend fun release(id: Long): DiscogsLookupResult {
                        return editions.release(id).also {
                            if (it is DiscogsLookupResult.Found) discogsReleaseFound++
                            else discogsReleaseRejected++
                        }
                    }
                },
            )
            discogsGranularScanner = native
            withTimeoutOrNull(95_000L) {
                try {
                    native.discoverDistinct(
                        DiscogsEditorialQuery("K-Pop", isStyle = true),
                        maxReleases = 3, knownMasterIds = emptySet(),
                        onCheckpoint = { initialLandingCursor = it },
                        onVerified = {
                            if (firstInitialVerifiedNs == null)
                                firstInitialVerifiedNs = System.nanoTime()
                        },
                    )
                } catch (_: Exception) { null }
            }
        }
        val initialElapsedMs = if (token == null) 0 else
            ((System.nanoTime() - initialStartedNs) / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val firstInitialVerifiedMs = firstInitialVerifiedNs?.let {
            ((it - initialStartedNs) / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } ?: 0
        check(firstInitialVerifiedMs in 0..initialElapsedMs)
        val verifiedDiscogs =
            (discogsRead as? DiscogsEditorialResult.Verified)?.releases.orEmpty()
        val discogsShelves = discogsEditorialShelves(verifiedDiscogs, home = true)
        val discogsItems = cards(discogsShelves)
        report("discogs", Sample(
            status = when {
                token == null -> "token_not_configured"
                discogsRead == null -> "timeout_or_unavailable"
                discogsRead is DiscogsEditorialResult.Unauthorized -> "unauthorized"
                discogsRead is DiscogsEditorialResult.Throttled -> "throttled"
                discogsRead is DiscogsEditorialResult.Disabled -> "disabled"
                discogsRead is DiscogsEditorialResult.Verified &&
                    verifiedDiscogs.isEmpty() -> "empty_after_filters"
                discogsRead is DiscogsEditorialResult.Verified -> "verified"
                else -> "source_error"
            },
            raw = if (token != null) discogsIndexCandidates else null,
            parsed = if (token != null) discogsMasterFound else null,
            eligible = verifiedDiscogs.size,
            visible = discogsItems.size,
            requests = if (token != null)
                discogsIndexReads + discogsMasterReads + discogsReleaseReads else null,
            artwork = discogsItems.count { !it.thumbnailUrl.isNullOrBlank() },
            shelves = discogsShelves,
            counts = mapOf(
                "discogs_index_reads" to discogsIndexReads,
                "discogs_master_reads" to discogsMasterReads,
                "discogs_master_found" to discogsMasterFound,
                "discogs_release_reads" to discogsReleaseReads,
                "discogs_release_found" to discogsReleaseFound,
                "index_hints_with_main_release" to indexHintsWithMainRelease,
                "index_hints_with_genre_and_style" to indexHintsWithGenresAndStyles,
                "master_hints_compared" to checkedMasterHints,
                "index_master_main_release_matches" to matchingMainReleaseHints,
                "index_master_genre_matches" to matchingGenreHints,
                "index_master_style_matches" to matchingStyleHints,
                "discogs_hydration_rejections" to
                    discogsMasterRejected + discogsReleaseRejected,
                "initial_elapsed_ms" to initialElapsedMs,
                "initial_first_verified_ms" to firstInitialVerifiedMs,
                "initial_first_verified_seen" to if (firstInitialVerifiedNs != null) 1 else 0,
            ),
        ))

        // One genuine scroll gesture after the same bounded landing read.
        // The counters below are DELTAS: never double-count the initial
        // Discogs index sample as newly seen raw source positions.
        val beforeIndexReads = discogsIndexReads
        val beforeIndexCandidates = discogsIndexCandidates
        val beforeMasterReads = discogsMasterReads
        val beforeMasterFound = discogsMasterFound
        val beforeReleaseReads = discogsReleaseReads
        val extraStart = initialLandingCursor ?: DiscogsEditorialCursor()
        val extraRead = if (discogsRead is DiscogsEditorialResult.Verified)
            withTimeoutOrNull(50_000L) {
                try {
                    discogsGranularScanner?.discoverGranular(
                        query = DiscogsEditorialQuery("K-Pop", isStyle = true),
                        cursor = extraStart,
                        knownMasterIds = verifiedDiscogs.map { it.masterId }.toSet(),
                        maxCandidates = 2,
                    )
                } catch (_: Exception) { null }
            }
        else null
        val extraVerified = when (extraRead) {
            is DiscogsGranularRead.Page -> extraRead.releases
            is DiscogsGranularRead.Interrupted -> extraRead.releases
            else -> emptyList()
        }
        val extraShelf = discogsGenreContinuationShelf(
            tag = "k-pop", selectedProviderIsDiscogs = true,
            original = discogsEditorialShelves(verifiedDiscogs),
            paging = DiscogsGenrePageSnapshot(
                tag = "k-pop", releases = extraVerified,
            ),
        )
        report("discogs_granular", Sample(
            status = when {
                token == null -> "token_not_configured"
                discogsRead !is DiscogsEditorialResult.Verified -> "initial_unverified"
                extraRead == null -> "timeout_or_unavailable"
                extraRead is DiscogsGranularRead.Interrupted -> "retryable_interruption"
                extraVerified.isEmpty() -> "no_new_verified_records"
                else -> "verified"
            },
            raw = if (token == null) null
                else discogsIndexCandidates - beforeIndexCandidates,
            parsed = if (token == null) null
                else discogsMasterFound - beforeMasterFound,
            eligible = extraVerified.size,
            visible = extraShelf?.items?.size ?: 0,
            requests = if (token == null) null else
                discogsIndexReads - beforeIndexReads +
                discogsMasterReads - beforeMasterReads +
                discogsReleaseReads - beforeReleaseReads,
            artwork = extraShelf?.items?.count { !it.thumbnailUrl.isNullOrBlank() } ?: 0,
            counts = mapOf(
                "source_index_reads" to discogsIndexReads - beforeIndexReads,
                "source_master_reads" to discogsMasterReads - beforeMasterReads,
                "source_release_reads" to discogsReleaseReads - beforeReleaseReads,
                "source_candidates_consumed" to when (extraRead) {
                    is DiscogsGranularRead.Page -> extraRead.consumedCandidates
                    is DiscogsGranularRead.Interrupted ->
                        (extraRead.retryCursor.candidatePosition -
                            extraStart.candidatePosition).coerceAtLeast(0)
                    else -> 0
                },
                "landing_checkpoint_used" to if (extraStart.landingCheckpoint) 1 else 0,
                "start_page" to extraStart.page,
                "start_position" to extraStart.candidatePosition,
                "retry_needed" to if (extraRead is DiscogsGranularRead.Interrupted) 1 else 0,
            ),
        ))

        // Reproduce one FULL app Home gesture, not merely the first two
        // index positions. Production checks up to three four-candidate
        // chunks, rotating to the next source Genre/Style predicate when
        // the current chunk does not yield a new verified Master.
        // Reproduce the actual Android Home landing, including each
        // source predicate's separate signed checkpoint. K-Pop alone is
        // a genre sample, not a valid stand-in for the Home load.
        val homeQueries = discogsHomeGranularQueries()
        val homeLandingVerified = mutableListOf<DiscogsEditorialCandidate>()
        val homeLandingCursors = mutableMapOf<DiscogsEditorialQuery, DiscogsEditorialCursor>()
        val homeLandingGenres = mutableMapOf<String, MutableSet<String>>()
        val homeLandingStarted = System.nanoTime()
        var homeLandingFirstNs: Long? = null
        val homeLandingIndexBefore = discogsIndexReads
        val homeLandingRawBefore = discogsIndexCandidates
        val homeLandingMasterBefore = discogsMasterReads
        val homeLandingReleaseBefore = discogsReleaseReads
        var homePredicatesAttempted = 0
        var homeLandingInterrupted = false
        val homeLandingCompleted = if (token != null) withTimeoutOrNull(180_000L) {
            for ((index, tag) in discogsHomeDiscoveryTags.withIndex()) {
                if (homeLandingVerified.size >= 10) break
                val predicate = discogsEditorialQueriesForTag(tag).firstOrNull() ?: continue
                val budget = discogsMultiStylePageBudget(
                    homeLandingVerified.size, discogsHomeDiscoveryTags.size - index - 1,
                    totalSlots = 10,
                ).coerceAtMost(2)
                homePredicatesAttempted++
                val read = discogsGranularScanner?.discoverDistinct(
                    predicate, budget, homeLandingVerified.map { it.masterId }.toSet(),
                    onCheckpoint = { homeLandingCursors[predicate] = it },
                    onVerified = {
                        if (homeLandingFirstNs == null) homeLandingFirstNs = System.nanoTime()
                        if (homeLandingVerified.none { prior -> prior.masterId == it.masterId }) {
                            homeLandingVerified += it
                            homeLandingGenres.getOrPut(tag) { mutableSetOf() }.add(it.browseId)
                        }
                    },
                )
                when (read) {
                    is DiscogsEditorialResult.Verified -> Unit
                    DiscogsEditorialResult.Unauthorized, DiscogsEditorialResult.Disabled,
                    is DiscogsEditorialResult.Throttled -> {
                        homeLandingInterrupted = true
                        break
                    }
                    else -> homeLandingInterrupted = true
                }
            }
            true
        } else null
        val discogsHomeShelves = discogsEditorialShelves(homeLandingVerified, home = true)
        val homeLandingVisibleIds = cards(discogsHomeShelves).mapNotNull { it.browseId }.toSet()
        val homeLandingGenreCount = homeLandingGenres.values.count { ids ->
            ids.any { it in homeLandingVisibleIds }
        }
        check(homeLandingVerified.size <= 10)
        check(homeLandingVerified.map { it.masterId }.distinct().size == homeLandingVerified.size)
        report("discogs_home_landing", Sample(
            status = when {
                token == null -> "token_not_configured"
                homeLandingCompleted == null -> "timeout_or_partial"
                homeLandingInterrupted -> "source_interruption"
                homeLandingVerified.isEmpty() -> "no_new_verified_records"
                else -> "verified"
            },
            raw = if (token == null) null else discogsIndexCandidates - homeLandingRawBefore,
            eligible = homeLandingVerified.size,
            visible = cards(discogsHomeShelves).size,
            requests = if (token == null) null else
                discogsIndexReads - homeLandingIndexBefore +
                    discogsMasterReads - homeLandingMasterBefore +
                    discogsReleaseReads - homeLandingReleaseBefore,
            counts = mapOf(
                "source_queries_attempted" to homePredicatesAttempted,
                "verified_landing_genres" to homeLandingGenreCount,
                "verified_target" to 10,
                "landing_checkpoints" to homeLandingCursors.size,
                "source_index_reads" to discogsIndexReads - homeLandingIndexBefore,
                "source_master_reads" to discogsMasterReads - homeLandingMasterBefore,
                "source_release_reads" to discogsReleaseReads - homeLandingReleaseBefore,
                "elapsed_ms" to ((System.nanoTime() - homeLandingStarted) / 1_000_000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                "first_verified_ms" to (homeLandingFirstNs?.let {
                    ((it - homeLandingStarted) / 1_000_000L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                } ?: 0),
            ),
        ))
        val discogsHomeInitial = initialDiscogsHomePageSnapshot(
            homeLandingVerified, homeQueries.size,
            homeQueries.mapIndexedNotNull { index, predicate ->
                homeLandingCursors[predicate]?.let { index to it }
            }.toMap(),
        )
        val beforeHomeIndexes = discogsIndexReads
        val beforeHomeCandidates = discogsIndexCandidates
        val beforeHomeMasters = discogsMasterReads
        val beforeHomeMasterFound = discogsMasterFound
        val beforeHomeReleases = discogsReleaseReads
        val beforeHomeReleaseFound = discogsReleaseFound
        val beforeHomeHintCount = indexHintsWithMainRelease
        val beforeHomeHintTags = indexHintsWithGenresAndStyles
        val beforeHomeCompared = checkedMasterHints
        val beforeHomeMainMatch = matchingMainReleaseHints
        val beforeHomeGenreMatch = matchingGenreHints
        val beforeHomeStyleMatch = matchingStyleHints
        var homeChunks = 0
        var homeConsumed = 0
        var homeTransportException = false
        var firstHomeVerifiedNs: Long? = null
        val homeStartNs = System.nanoTime()
        // The UI has no network deadline of its own. This observational
        // watchdog classifies excessive latency instead of falsely calling
        // a timed-out network sample an exhausted catalog.
        val homeCanStart = homeLandingCompleted == true &&
            discogsHomeShelves.isNotEmpty() && homeQueries.isNotEmpty()
        val homeGesture = if (homeCanStart) withTimeoutOrNull(110_000L) {
            discogsGranularScanner?.let { scanner ->
                scanDiscogsHomeGesture(
                    discogsHomeInitial, homeQueries, scanner,
                    onVerified = {
                        if (firstHomeVerifiedNs == null)
                            firstHomeVerifiedNs = System.nanoTime()
                    },
                )
            }
        } else null
        val discogsHomeNext = homeGesture?.state
        homeChunks = homeGesture?.chunksAttempted ?: 0
        homeConsumed = homeGesture?.consumedCandidates ?: 0
        homeTransportException = homeGesture?.transportException ?: false
        val homeElapsedMs = ((System.nanoTime() - homeStartNs) / 1_000_000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val firstHomeVerifiedMs = firstHomeVerifiedNs?.let { first ->
            ((first - homeStartNs) / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } ?: 0
        check(firstHomeVerifiedMs in 0..homeElapsedMs)
        check(homeChunks in 0..3)
        check(homeConsumed in 0..12)
        val discogsHomeExtra = discogsHomeNext?.let {
            discogsHomeContinuationShelf(discogs, discogsHomeShelves, it)
        }
        report("discogs_home_granular", Sample(
            status = when {
                token == null -> "token_not_configured"
                homeLandingCompleted != true -> "initial_unverified"
                !homeCanStart -> "initial_no_editorial"
                homeTransportException -> "source_error"
                discogsHomeNext == null -> "timeout_or_unavailable"
                discogsHomeNext.failed -> "retryable_interruption"
                discogsHomeExtra == null -> "no_new_verified_records"
                else -> "verified"
            },
            raw = if (token == null) null
                else discogsIndexCandidates - beforeHomeCandidates,
            parsed = if (token == null) null
                else discogsMasterFound - beforeHomeMasterFound,
            eligible = discogsHomeNext?.releases?.size ?: 0,
            visible = discogsHomeExtra?.items?.size ?: 0,
            requests = if (token == null) null else
                discogsIndexReads - beforeHomeIndexes +
                discogsMasterReads - beforeHomeMasters +
                discogsReleaseReads - beforeHomeReleases,
            artwork = discogsHomeExtra?.items?.count {
                !it.thumbnailUrl.isNullOrBlank()
            } ?: 0,
            counts = mapOf(
                "source_queries_available" to homeQueries.size,
                "next_query_index" to (discogsHomeNext?.nextQueryIndex ?: homeQueries.size),
                "next_window_has_more" to if (discogsHomeNext?.hasMore == true) 1 else 0,
                "source_candidates_consumed" to homeConsumed,
                "source_candidate_budget" to 12,
                "verified_target" to DISCOGS_SCROLL_VERIFIED_TARGET,
                "source_chunks_attempted" to homeChunks,
                "source_index_reads" to discogsIndexReads - beforeHomeIndexes,
                "source_master_reads" to discogsMasterReads - beforeHomeMasters,
                "source_master_found" to discogsMasterFound - beforeHomeMasterFound,
                "source_release_reads" to discogsReleaseReads - beforeHomeReleases,
                "source_release_found" to discogsReleaseFound - beforeHomeReleaseFound,
                "index_hints_with_main_release" to indexHintsWithMainRelease - beforeHomeHintCount,
                "index_hints_with_genre_and_style" to
                    indexHintsWithGenresAndStyles - beforeHomeHintTags,
                "master_hints_compared" to checkedMasterHints - beforeHomeCompared,
                "index_master_main_release_matches" to
                    matchingMainReleaseHints - beforeHomeMainMatch,
                "index_master_genre_matches" to matchingGenreHints - beforeHomeGenreMatch,
                "index_master_style_matches" to matchingStyleHints - beforeHomeStyleMatch,
                "elapsed_ms" to homeElapsedMs,
                "first_verified_ms" to firstHomeVerifiedMs,
            ),
        ))

        // Cumulative real Home capacity: the first gesture and landing
        // above are part of the same session, not separate fresh samples.
        // The optional larger curve uses exactly the same eight-release /
        // twelve-position gesture as Android, never an enlarged API quota.
        val capacityTarget = System.getenv("IDM_CATALOG_DISCOGS_CAPACITY_TARGET")
            ?.toIntOrNull()?.takeIf { it in listOf(100, 300) } ?: 100
        val milestones = listOf(30, 100, 300).filter { it <= capacityTarget }
        val capacityDeadline = when (capacityTarget) {
            300 -> 1_200_000L
            else -> 300_000L
        }
        var capacityState = discogsHomeNext ?: discogsHomeInitial
        var capacityGestures = if (homeGesture != null) 1 else 0
        var capacityPositions = homeConsumed
        var capacityChunks = homeChunks
        var capacityStalled = false
        var capacityFailed = capacityState.failed || homeTransportException
        val recordedMilestones = mutableSetOf<Int>()
        fun reportCapacity(target: Int, status: String) {
            val continuationMs = ((System.nanoTime() - homeStartNs) / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val appended = discogsHomeContinuationShelf(discogs, discogsHomeShelves, capacityState)
            val visibleReleases = cards(discogsHomeShelves + listOfNotNull(appended))
                .count { it.browseId.orEmpty().startsWith("discogs:release:") }
            val verifiedCount = capacityState.knownMasterIds.size
            check(visibleReleases == verifiedCount)
            report("discogs_home_capacity_" + target, Sample(
                status = status,
                raw = if (token == null) null else discogsIndexCandidates - homeLandingRawBefore,
                eligible = verifiedCount,
                visible = visibleReleases,
                requests = if (token == null) null else
                    discogsIndexReads - homeLandingIndexBefore +
                    discogsMasterReads - homeLandingMasterBefore +
                    discogsReleaseReads - homeLandingReleaseBefore,
                counts = mapOf(
                    "verified_target" to target,
                    "source_gestures" to capacityGestures,
                    "continuation_elapsed_ms" to continuationMs,
                    "source_candidates_consumed" to capacityPositions,
                    "source_chunks_attempted" to capacityChunks,
                    "source_index_reads" to discogsIndexReads - homeLandingIndexBefore,
                    "source_master_reads" to discogsMasterReads - homeLandingMasterBefore,
                    "source_release_reads" to discogsReleaseReads - homeLandingReleaseBefore,
                    "next_window_has_more" to if (capacityState.hasMore && homeCanStart) 1 else 0,
                    "elapsed_ms" to ((System.nanoTime() - homeLandingStarted) / 1_000_000L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                ),
            ))
            recordedMilestones += target
        }
        fun recordReachedMilestones() {
            milestones.filter { it !in recordedMilestones &&
                capacityState.knownMasterIds.size >= it
            }.forEach { target ->
                val continuationMs = (System.nanoTime() - homeStartNs) / 1_000_000L
                reportCapacity(target, when {
                    target == 30 && capacityGestures > 12 -> "target_reached_over_gesture_budget"
                    target == 100 && continuationMs > 300_000L -> "target_reached_over_time_budget"
                    else -> "target_reached"
                })
            }
        }
        recordReachedMilestones()
        val capacityScanner = discogsGranularScanner
        val capacityCompleted = if (homeCanStart && homeGesture != null &&
            capacityScanner != null && !capacityFailed
        ) withTimeoutOrNull(
            (capacityDeadline - (System.nanoTime() - homeStartNs) / 1_000_000L)
                .coerceAtLeast(1L),
        ) {
            // Bound both candidate throughput and wall clock, and stop on
            // interruption rather than automatically hammering a 429.
            val maxGestures = maxOf(12, capacityTarget / 2)
            while (capacityState.hasMore && capacityState.knownMasterIds.size < capacityTarget &&
                capacityGestures < maxGestures
            ) {
                val previous = capacityState
                val gesture = scanDiscogsHomeGesture(
                    capacityState, homeQueries, capacityScanner,
                ) ?: run { capacityFailed = true; null }
                if (gesture == null) break
                capacityGestures++
                capacityPositions += gesture.consumedCandidates
                capacityChunks += gesture.chunksAttempted
                capacityState = gesture.state
                check(gesture.consumedCandidates in 0..12)
                check(capacityState.knownMasterIds.containsAll(previous.knownMasterIds))
                check(capacityState.knownMasterIds.size - previous.knownMasterIds.size in 0..8)
                recordReachedMilestones()
                if (capacityGestures == 12 && 30 !in recordedMilestones)
                    reportCapacity(30, "gesture_budget_below_target")
                if (capacityState.failed || gesture.transportException) {
                    capacityFailed = true
                    break
                }
                if (capacityState.cursors == previous.cursors &&
                    capacityState.nextQueryIndex == previous.nextQueryIndex &&
                    capacityState.knownMasterIds == previous.knownMasterIds
                ) {
                    capacityStalled = true
                    break
                }
            }
            true
        } else null
        for (target in milestones.filter { it !in recordedMilestones }) {
            reportCapacity(target, when {
                token == null -> "token_not_configured"
                !homeCanStart -> "initial_unverified"
                capacityFailed -> "retryable_interruption"
                capacityCompleted == null -> "timeout_or_unavailable"
                capacityStalled -> "cursor_stalled"
                !capacityState.hasMore && capacityState.sourceCeilingHit -> "source_page_ceiling"
                !capacityState.hasMore -> "source_exhausted_below_target"
                else -> "gesture_budget_below_target"
            })
        }

        // Exercise one REAL canonical genre scroll using the new in-gesture
        // twelve-position budget. Compare actual network deltas separately:
        // returning to a K-Pop category reuses only a fresh source index;
        // a partial cursor from a later gesture would still re-read it.
        val genreIndexBefore = discogsIndexReads
        val genreCandidatesBefore = discogsIndexCandidates
        val genreMasterBefore = discogsMasterReads
        val genreMasterFoundBefore = discogsMasterFound
        val genreReleaseBefore = discogsReleaseReads
        val genreReleaseFoundBefore = discogsReleaseFound
        val genreStartedNs = System.nanoTime()
        var genreChunks = 0
        var genrePositions = 0
        var genreTransportException = false
        var firstGenreVerifiedNs: Long? = null
        val initialGenreState = DiscogsGenrePageSnapshot(
            tag = "k-pop", cursor = initialLandingCursor ?: DiscogsEditorialCursor(),
            knownMasterIds = verifiedDiscogs.map { it.masterId }.toSet(),
        )
        val genreCanStart = discogsRead is DiscogsEditorialResult.Verified &&
            discogsShelves.isNotEmpty()
        val genreGesture = if (genreCanStart) withTimeoutOrNull(95_000L) {
            discogsGranularScanner?.let { scanner ->
                scanDiscogsGenreGesture(
                    initialGenreState, "k-pop", discogsEditorialQueriesForTag("k-pop"), scanner,
                    onVerified = {
                        if (firstGenreVerifiedNs == null)
                            firstGenreVerifiedNs = System.nanoTime()
                    },
                )
            }
        } else null
        val scrolledGenre = genreGesture?.state
        genreChunks = genreGesture?.chunksAttempted ?: 0
        genrePositions = genreGesture?.consumedCandidates ?: 0
        genreTransportException = genreGesture?.transportException ?: false
        val genreElapsedMs = ((System.nanoTime() - genreStartedNs) / 1_000_000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        check(genrePositions in 0..12)
        check(genreChunks in 0..3)
        val genreExtra = scrolledGenre?.let {
            discogsGenreContinuationShelf(
                "k-pop", true,
                discogsEditorialShelves(verifiedDiscogs), it,
            )
        }
        report("discogs_genre_12_positions", Sample(
            status = when {
                token == null -> "token_not_configured"
                !genreCanStart -> "initial_unverified"
                genreTransportException -> "source_error"
                scrolledGenre == null -> "timeout_or_unavailable"
                scrolledGenre.failed -> "retryable_interruption"
                genreExtra == null -> "no_new_verified_records"
                else -> "verified"
            },
            raw = if (token == null) null
                else discogsIndexCandidates - genreCandidatesBefore,
            parsed = if (token == null) null
                else discogsMasterFound - genreMasterFoundBefore,
            eligible = scrolledGenre?.releases?.size ?: 0,
            visible = genreExtra?.items?.size ?: 0,
            requests = if (token == null) null else
                discogsIndexReads - genreIndexBefore +
                discogsMasterReads - genreMasterBefore +
                discogsReleaseReads - genreReleaseBefore,
            counts = mapOf(
                "source_candidates_consumed" to genrePositions,
                "source_candidate_budget" to 12,
                "verified_target" to DISCOGS_SCROLL_VERIFIED_TARGET,
                "source_chunks_attempted" to genreChunks,
                "source_index_reads" to discogsIndexReads - genreIndexBefore,
                "source_master_reads" to discogsMasterReads - genreMasterBefore,
                "source_release_reads" to discogsReleaseReads - genreReleaseBefore,
                "source_release_found" to discogsReleaseFound - genreReleaseFoundBefore,
                "next_window_has_more" to if (scrolledGenre?.hasMore == true) 1 else 0,
                "elapsed_ms" to genreElapsedMs,
                "first_verified_ms" to (firstGenreVerifiedNs?.let {
                    ((it - genreStartedNs) / 1_000_000L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                } ?: 0),
            ),
        ))

        // Revisit the exact initial live predicate with genuinely verified
        // identities already present in the current snapshot. This is a
        // deliberately overlapping sampler, not a claim about natural overlap
        // between two distinct genres. It exercises the same landing method
        // used by Home/genre when predicates overlap.
        val repeatKnown = (verifiedDiscogs + scrolledGenre?.releases.orEmpty())
            .map { it.masterId }.toSet()
        val repeatIndexBefore = discogsIndexReads
        val repeatRawBefore = discogsIndexCandidates
        val repeatMasterBefore = discogsMasterReads
        val repeatMasterFoundBefore = discogsMasterFound
        val repeatReleaseBefore = discogsReleaseReads
        val repeatStartedNs = System.nanoTime()
        val repeatEmitted = mutableListOf<Long>()
        val repeatedSample = if (genreCanStart) withTimeoutOrNull(45_000L) {
            try {
                discogsGranularScanner?.discoverDistinct(
                    DiscogsEditorialQuery("K-Pop", isStyle = true),
                    maxReleases = 3, knownMasterIds = repeatKnown,
                    onVerified = { repeatEmitted += it.masterId },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { DiscogsEditorialResult.Unavailable }
        } else null
        val repeatNew = (repeatedSample as? DiscogsEditorialResult.Verified)
            ?.releases.orEmpty()
        check(repeatEmitted.none { it in repeatKnown })
        check(repeatEmitted.distinct().size == repeatEmitted.size)
        check(repeatNew.none { it.masterId in repeatKnown })
        check(repeatNew.size <= 3)
        val repeatShelves = discogsEditorialShelves(repeatNew)
        report("discogs_overlapping_landing", Sample(
            status = when {
                token == null -> "token_not_configured"
                !genreCanStart -> "initial_unverified"
                repeatedSample == null -> "timeout_or_unavailable"
                repeatedSample is DiscogsEditorialResult.Throttled -> "throttled"
                repeatedSample is DiscogsEditorialResult.Unauthorized -> "unauthorized"
                repeatedSample is DiscogsEditorialResult.Disabled -> "disabled"
                repeatedSample !is DiscogsEditorialResult.Verified -> "source_error"
                repeatNew.isEmpty() -> "no_new_verified_records"
                else -> "verified"
            },
            raw = if (token == null) null else discogsIndexCandidates - repeatRawBefore,
            parsed = if (token == null) null else discogsMasterFound - repeatMasterFoundBefore,
            eligible = repeatNew.size,
            visible = cards(repeatShelves).size,
            requests = if (token == null) null else
                discogsIndexReads - repeatIndexBefore +
                discogsMasterReads - repeatMasterBefore +
                discogsReleaseReads - repeatReleaseBefore,
            counts = mapOf(
                "known_verified_masters" to repeatKnown.size,
                "new_verified_target" to 3,
                "new_verified_emitted" to repeatEmitted.size,
                "source_index_reads" to discogsIndexReads - repeatIndexBefore,
                "source_master_reads" to discogsMasterReads - repeatMasterBefore,
                "source_release_reads" to discogsReleaseReads - repeatReleaseBefore,
                "elapsed_ms" to ((System.nanoTime() - repeatStartedNs) / 1_000_000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            ),
        ))

        // Real native artist catalog: choose an exact artist ID credited by
        // a verified source Release, then traverse bounded actual pages.
        // A small artist discography or deadline is not a successful 30-row test.
        val artistCredit = (homeLandingVerified + verifiedDiscogs + repeatNew)
            .asSequence().flatMap { it.creditedArtists.asSequence() }
            .firstOrNull { it.first > 0 && it.second.isNotBlank() }
        val artistId = artistCredit?.first
        val artistVerified = mutableListOf<DiscogsNativeArtistRelease>()
        val artistMasterBefore = discogsMasterReads
        val artistReleaseBefore = discogsReleaseReads
        val artistStarted = System.nanoTime()
        var artistFirstNs: Long? = null
        var artistPagesCompleted = 0
        var artistInterrupted = false
        var artistIncomplete = false
        var artistHasMore = true
        val artistScanner = discogsArtistScanner
        val artistCompleted = if (artistId != null && artistScanner != null)
            withTimeoutOrNull(150_000L) {
                while (artistPagesCompleted < 10 && artistHasMore && artistVerified.size < 30) {
                    val window = artistScanner.loadWindow(
                        artistId, artistPagesCompleted + 1, artistVerified.toList(),
                        maxPages = minOf(3, 10 - artistPagesCompleted),
                    ) { rows ->
                        if (rows.isNotEmpty() && artistFirstNs == null)
                            artistFirstNs = System.nanoTime()
                    }
                    val response = window.result
                    if (response !is DiscogsNativeArtistLoad.Success) {
                        artistInterrupted = true
                        break
                    }
                    artistVerified += response.releases
                    artistPagesCompleted += window.completedPages
                    artistIncomplete = artistIncomplete || window.settledIncomplete
                    artistHasMore = response.hasMore || response.retryCurrentPage
                    if (response.retryCurrentPage) {
                        artistInterrupted = true
                        break
                    }
                    if (window.completedPages == 0) break
                }
                true
            } else null
        check(artistVerified.size == discogsMergeArtistPageReleases(artistVerified).size)
        check(artistVerified.all { it.artistId == artistId })
        val artistVisible = artistCredit?.let { credit ->
            cards(discogsArtistPage(
                com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow(credit.first, credit.second),
                artistVerified, artistHasMore, artistIncomplete || artistInterrupted,
            ).page.sections).size
        } ?: 0
        check(artistVisible == artistVerified.size)
        report("discogs_artist_30_target", Sample(
            status = when {
                token == null -> "token_not_configured"
                artistId == null -> "no_verified_artist_credit"
                artistCompleted == null -> "timeout_or_partial"
                artistInterrupted -> "retryable_interruption"
                artistVerified.size >= 30 -> "target_reached"
                !artistHasMore && !artistIncomplete -> "source_exhausted_below_target"
                !artistHasMore -> "incomplete_source_below_target"
                else -> "page_budget_below_target"
            },
            // The artist parser returns typed Main entries, not every raw role.
            parsed = if (token == null) null else artistIndexCandidates,
            eligible = artistVerified.size,
            visible = artistVisible,
            requests = if (token == null) null else artistIndexReads +
                discogsMasterReads - artistMasterBefore +
                discogsReleaseReads - artistReleaseBefore,
            counts = mapOf(
                "verified_target" to 30,
                "source_pages_completed" to artistPagesCompleted,
                "source_index_reads" to artistIndexReads,
                "source_index_master_candidates" to artistIndexMasterCandidates,
                "source_index_main_release_hints" to artistIndexMainReleaseHints,
                "source_master_reads" to discogsMasterReads - artistMasterBefore,
                "source_release_reads" to discogsReleaseReads - artistReleaseBefore,
                "next_window_has_more" to if (artistHasMore && artistId != null) 1 else 0,
                "incomplete_coverage" to if (artistIncomplete) 1 else 0,
                "elapsed_ms" to ((System.nanoTime() - artistStarted) / 1_000_000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                "first_verified_ms" to (artistFirstNs?.let {
                    ((it - artistStarted) / 1_000_000L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                } ?: 0),
            ),
        ))

        // Same Home priority and fallback selection as MainActivity, evaluated
        // for every possible explicit provider order. No cross-provider IDs.
        val orders = listOf(
            listOf(mb, apple, discogs), listOf(mb, discogs, apple),
            listOf(apple, mb, discogs), listOf(apple, discogs, mb),
            listOf(discogs, mb, apple), listOf(discogs, apple, mb),
        )
        var successful = 0
        var firstOrderVisible = 0
        val shownByAnyOrder = linkedMapOf<String,
            com.music.bitchord.data.model.ShelfItem>()
        for ((index, order) in orders.withIndex()) {
            val selection = projectCanonicalHomeEditorial(
                canonicalHomeShelves = appMbHome,
                appleHomeReleases = UiState.Success(appleReleases),
                catalogEditorialOrder = order,
                canonicalWorldwideLoading = false,
                releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
                todayIsoDate = date, musicBrainzRows = appMbRows,
                discogsEditorial = UiState.Success(discogsHomeShelves),
            )
            val owner = selection.provider
            val sourceItems = (selection.state as? UiState.Success)?.data
                ?.flatMap { it.items }.orEmpty()
            val displayedWithScroll = projectCanonicalHomeDisplay(
                canonicalHomeShelves = emptyList(),
                selection = selection,
                publicPlaylistShelf = null,
                musicBrainzPaging = mbHomeNext ?: mbInitialPaging,
                appleHomePool = verifiedAppleHomePool,
                appleRevealedCount = 24,
                discogsPaging = discogsHomeNext ?: discogsHomeInitial,
            )
            val continuation = displayedWithScroll.firstOrNull {
                it.title == "Mais do catálogo"
            }
            if (sourceItems.isNotEmpty()) {
                successful++
                val prefix = when (owner) {
                    mb -> "catalog:RELEASE"
                    apple -> "itunes:br:album:"
                    discogs -> "discogs:"
                    else -> error("Unknown catalogue owner")
                }
                assertTrue(sourceItems.all {
                    it.browseId.orEmpty().startsWith(prefix)
                })
                // The same Home widgets may append verified source rows, but
                // never a release owned by a lower-priority provider.
                assertTrue(continuation?.items.orEmpty().all { row ->
                    row.browseId.orEmpty().startsWith(prefix)
                })
                val distinct = sourceItems.distinctBy { it.browseId }
                if (index == 0) firstOrderVisible = distinct.size
                distinct.forEach { item ->
                    item.browseId?.let { shownByAnyOrder.putIfAbsent(it, item) }
                }
            }
        }
        val homeDiscogsItems = cards(discogsHomeShelves)
        val sourceVisibleCounts = listOf(
            appMbItems.size, appleItems.size, homeDiscogsItems.size,
        )
        // Each order represents an alternate Home, not another shelf on
        // the same screen. Never sum repeated cards across permutations.
        val sourceCandidates = appMbItems.size + appleItems.size + homeDiscogsItems.size
        report("collective", Sample(
            status = when {
                token == null -> "partial_discogs_token_not_configured"
                homeDiscogsItems.isEmpty() ->
                    "partial_discogs_unverified"
                successful == orders.size && sourceVisibleCounts.all { it > 0 } ->
                    "all_sources_observed"
                else -> "partial_priority_fallback"
            },
            eligible = sourceCandidates,
            visible = shownByAnyOrder.size,
            requests = 0,
            artwork = shownByAnyOrder.values.count {
                !it.thumbnailUrl.isNullOrBlank()
            },
            counts = mapOf(
                "priority_orders_tested" to orders.size,
                "priority_orders_with_content" to successful,
                "provider_sources_with_content" to sourceVisibleCounts.count { it > 0 },
                "provider_sources_not_verified" to
                    listOf(mbResult !is MusicBrainzCatalogSearchResult.Success,
                        appleResult !is AppleEditorialLoad.Success,
                        homeDiscogsItems.isEmpty()).count { it },
                "first_order_visible" to firstOrderVisible,
            ),
        ))
        assertEquals(6, orders.size)
    }

    /**
     * #227 fixed live corpus: all 15 categories per source, with cumulative
     * continuation on the three frozen capacity genres. This is observational;
     * every source error receives a report, not a fabricated passing fixture.
     */
    @Test fun live_genre_parity_matrix() = runBlocking {
        assumeTrue("Only the observational CI job may access remote services",
            System.getenv("IDM_CATALOG_LIVE") == "1")
        val today = LocalDate.now(ZoneOffset.UTC)
        val date = today.toString()
        val matrix = idmParityMainGenres + idmParitySubgenres
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val mbGate = gateFor(mb, caps)
        val mbSource = MusicBrainzCatalogSearch(
            MusicBrainzClient(admission = { mbGate }),
            capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
        val appleGate = gateFor(apple, caps)
        val appleRepo = AppleEditorialCatalogRepository(ItunesCatalogClient(
            transport = KtorItunesCatalogTransport(
                admission = RateLimitedProviderTransport { appleGate },
            ),
        ))
        val appleSource = AppleExploreGenreDiscovery(
            nativeSource = ItunesNativeGenreSearchSource(appleRepo),
        )
        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(String::isNotBlank)
        val discogsGate = gateFor(discogs, caps)
        val guard = RateLimitedProviderTransport { discogsGate }
        val discogsSource = DiscogsNativeGenreEditorial(
            enabled = token != null, tokenProvider = { token }, admission = guard,
            editions = DiscogsNativeEditionClient(DiscogsClient(
                enabled = token != null, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                admission = guard, tokenProvider = { token },
            )),
        )
        for (provider in listOf(mb, apple, discogs)) {
            val name = when (provider) {
                mb -> "musicbrainz"
                apple -> "itunes"
                else -> "discogs"
            }
            val prefix = when (provider) {
                mb -> "catalog:RELEASE_GROUP:"
                apple -> "itunes:br:album:"
                else -> "discogs:release:"
            }
            for (tag in matrix) {
                val landingTarget = if (tag in idmParityMainGenres) 6 else 4
                val capacity = tag in idmParityCapacityGenres
                var landingVisible = 0
                var visible = 0
                var gestures = 0
                var failed = false
                var mapped = true
                var sourceExhausted = false
                var indexCeiling = false
                var sourceQueries = 0
                var firstScrollVisible = 0
                val start = System.nanoTime()
                fun count(state: UiState<List<HomeShelf>>): Int {
                    val shelves = (state as? UiState.Success)?.data.orEmpty()
                    for (shelf in shelves) {
                        val ids = shelf.items.mapNotNull { it.browseId }
                        assertEquals("Duplicate identity in $name/$tag", ids.size, ids.distinct().size)
                        assertTrue("Foreign provider release in $name/$tag", ids.none {
                            (it.startsWith("catalog:RELEASE_GROUP:") ||
                                it.startsWith("itunes:br:album:") ||
                                it.startsWith("discogs:release:")) && !it.startsWith(prefix)
                        })
                    }
                    return cards(shelves).count { it.browseId.orEmpty().startsWith(prefix) }
                }
                val complete = try {
                    withTimeoutOrNull(if (capacity) 210_000L else 85_000L) {
                        when (provider) {
                            mb -> {
                                sourceQueries++
                                val historical = (mbSource.searchReleaseGroupsForTag(tag, 60)
                                    as? MusicBrainzCatalogSearchResult.Success)?.page
                                sourceQueries++
                                val recent = (mbSource.searchRecentReleaseGroupsForTag(
                                    tag, today.minusDays(180).toString(), date, 40,
                                ) as? MusicBrainzCatalogSearchResult.Success)?.page
                                if (historical == null && recent == null) failed = true
                                val hits = (historical?.items.orEmpty() + recent?.items.orEmpty())
                                    .distinctBy { it.musicBrainzId }
                                var rows = canonicalPublicGenreRows(hits, date)
                                if (!failed && rows.none { canonicalEditorialReleaseType(it) == "Album" }) {
                                    sourceQueries++
                                    val albums = (mbSource.searchAlbumReleaseGroupsForTag(tag, 24)
                                        as? MusicBrainzCatalogSearchResult.Success)?.page
                                    val enriched = canonicalPublicGenreRows(
                                        hits + albums?.items.orEmpty(), date,
                                    )
                                    if (enriched.any { canonicalEditorialReleaseType(it) == "Album" })
                                        rows = enriched
                                }
                                var paging = CanonicalGenrePageSnapshot(
                                    tag = tag, nextOffset = historical?.nextOffset,
                                    rows = historical?.items.orEmpty().let { initial ->
                                        if (initial.isEmpty()) emptyList()
                                        else canonicalPublicGenreRows(initial, date, maxItems = initial.size)
                                    },
                                )
                                fun project() = projectCanonicalGenreState(
                                    tag, emptyList(), "Playlists", emptyMap(),
                                    UiState.Success(rows), listOf(mb), "Lançamentos",
                                    todayIsoDate = date, musicBrainzPaging = paging,
                                )
                                landingVisible = count(project())
                                visible = landingVisible
                                while (capacity && visible < 30 && gestures < 12 && !failed) {
                                    val offset = paging.nextOffset ?: break
                                    sourceQueries++
                                    val page = (mbSource.searchReleaseGroupsForTag(tag, 60, offset)
                                        as? MusicBrainzCatalogSearchResult.Success)?.page
                                    gestures++
                                    val next = page?.let {
                                        appendCanonicalGenrePage(paging, tag, offset, 60, it, date)
                                    }
                                    if (next == null) { failed = true; break }
                                    paging = next
                                    val before = visible
                                    visible = count(project())
                                    assertTrue("Continuation lost visible releases in $name/$tag", visible >= before)
                                    if (gestures == 1) firstScrollVisible = visible - before
                                }
                                sourceExhausted = historical != null && paging.nextOffset == null
                            }
                            apple -> {
                                mapped = AppleExploreGenrePolicy.supports(tag)
                                if (mapped) {
                                    sourceQueries++
                                    val response = appleSource.discoverNative(tag, "br", expandSparseCapacity = true)
                                    val result = response as? AppleEditorialLoad.Success
                                    sourceQueries = result?.probeReport?.probesMade ?: 1
                                    if (result == null) failed = true
                                    val rows = result?.releases.orEmpty()
                                    var revealed = 24
                                    fun project() = projectCanonicalGenreState(
                                        tag, emptyList(), "Playlists", mapOf(tag to UiState.Success(rows)),
                                        UiState.Success(emptyList()), listOf(apple), "Lançamentos",
                                        todayIsoDate = date, appleVisibleCount = revealed,
                                    )
                                    landingVisible = count(project())
                                    visible = landingVisible
                                    while (capacity && visible < 30 && gestures < 12 &&
                                        !failed && revealed < rows.size
                                    ) {
                                        val before = visible
                                        revealed = minOf(revealed + 24, rows.size)
                                        gestures++
                                        visible = count(project())
                                        assertTrue("Continuation lost visible releases in $name/$tag", visible >= before)
                                        if (gestures == 1) firstScrollVisible = visible - before
                                    }
                                    indexCeiling = result?.state == AppleEditorialReadState.POSSIBLY_TRUNCATED
                                    sourceExhausted = !failed && !indexCeiling && revealed >= rows.size
                                }
                            }
                            else -> {
                                val predicates = discogsEditorialQueriesForTag(tag)
                                mapped = predicates.isNotEmpty()
                                if (token != null && mapped) {
                                    val landing = scanDiscogsGenreLanding(
                                        predicates, discogsSource,
                                        onProgress = { rows ->
                                            // Preserve the actually visible partial on a deadline.
                                            landingVisible = count(UiState.Success(discogsEditorialShelves(rows)))
                                            visible = landingVisible
                                        },
                                    )
                                    sourceQueries = landing?.queriesAttempted ?: 0
                                    if (landing == null || landing.failure != null) failed = true
                                    val shelves = discogsEditorialShelves(landing?.releases.orEmpty())
                                    var paging = landing?.paging(tag) ?: DiscogsGenrePageSnapshot(tag = tag)
                                    fun project() = projectCanonicalGenreState(
                                        tag, emptyList(), "Playlists", emptyMap(),
                                        UiState.Success(emptyList()), listOf(discogs), "Lançamentos",
                                        todayIsoDate = date,
                                        discogsByTag = mapOf(tag to UiState.Success(shelves)),
                                        discogsPaging = paging,
                                    )
                                    landingVisible = count(project())
                                    visible = landingVisible
                                    while (capacity && visible < 30 && gestures < 12 &&
                                        !failed && paging.hasMore && landingVisible > 0
                                    ) {
                                        val before = visible
                                        val prior = paging
                                        val next = scanDiscogsGenreGesture(paging, tag, predicates, discogsSource)
                                        gestures++
                                        if (next == null) { failed = true; break }
                                        sourceQueries += next.chunksAttempted
                                        paging = next.state
                                        visible = count(project())
                                        assertTrue("Invalid Discogs gesture delta in $tag", visible >= before && visible - before <= 8)
                                        if (gestures == 1) firstScrollVisible = visible - before
                                        if (paging.failed || next.transportException ||
                                            (paging.cursor == prior.cursor &&
                                                paging.queryIndex == prior.queryIndex && visible == before)
                                        ) failed = true
                                    }
                                    indexCeiling = paging.stoppedByPageLimit
                                    sourceExhausted = !paging.hasMore && !indexCeiling
                                }
                            }
                        }
                        true
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    false
                }
                val status = when {
                    provider == discogs && token == null -> "token_not_configured"
                    !mapped -> "no_source_mapping"
                    complete == null -> "timeout_or_partial"
                    failed -> "source_interruption"
                    landingVisible >= landingTarget && (!capacity || visible >= 30) -> "target_reached"
                    indexCeiling -> "source_ceiling_below_target"
                    sourceExhausted -> "source_exhausted_below_target"
                    else -> "sample_below_target"
                }
                report("genre_matrix_" + name + "_" + tag.replace(' ', '_').replace('-', '_'), Sample(
                    status = status, eligible = visible, visible = visible,
                    counts = mapOf(
                        "landing_visible_releases" to landingVisible,
                        "landing_target" to landingTarget,
                        "verified_target" to if (capacity) 30 else landingTarget,
                        "source_gestures" to gestures,
                        "first_scroll_new_releases" to firstScrollVisible,
                        // Source adapter calls, NOT necessarily HTTP GETs.
                        "source_queries_attempted" to sourceQueries,
                        "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                ))
            }
        }
    }

    private data class ArtistParityCase(
        val name: String, val anchor: String, val extensive: Boolean,
    )

    // Frozen before the campaign. The anchor is an independent discography
    // fact for human-labelled identity, not a cross-provider ID mapping.
    private val artistParityCases = listOf(
        ArtistParityCase("David Bowie", "Hunky Dory", true),
        ArtistParityCase("Bob Dylan", "Blood on the Tracks", true),
        ArtistParityCase("Madonna", "Like a Prayer", true),
        ArtistParityCase("Iron Maiden", "Powerslave", true),
        ArtistParityCase("Depeche Mode", "Violator", true),
        ArtistParityCase("Portishead", "Dummy", false),
        ArtistParityCase("Massive Attack", "Mezzanine", false),
        ArtistParityCase("BLACKPINK", "BORN PINK", false),
        ArtistParityCase("CHVRCHES", "Every Open Eye", false),
        ArtistParityCase("The xx", "Coexist", false),
    )

    private fun artistParityFold(value: String?): String =
        java.text.Normalizer.normalize(value.orEmpty().trim(), java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * #228: the exact source-owned artist must appear in artist search AND in
     * the independently returned credit of its frozen anchor album. A name
     * alone (including a Discogs numbered suffix) never selects the identity.
     * Exercise production discography adapters and canonical shelf compositors.
     * This is not an instrumented Activity/back-stack or scroll-restoration test.
     */
    @Test fun live_artist_parity_matrix() = runBlocking {
        assumeTrue("Only the observational CI job may access remote services",
            System.getenv("IDM_CATALOG_LIVE") == "1")
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val mbGate = gateFor(mb, caps)
        val mbClient = MusicBrainzClient(admission = { mbGate })
        val mbSearch = MusicBrainzCatalogSearch(mbClient)
        val mbGraph = MusicBrainzCatalogGraph(mbClient)
        val appleGate = gateFor(apple, caps)
        val appleRepo = AppleEditorialCatalogRepository(ItunesCatalogClient(
            transport = KtorItunesCatalogTransport(
                admission = RateLimitedProviderTransport { appleGate },
            ),
        ))
        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(String::isNotBlank)
        // Keep one stable gate and limiter for the whole matrix.
        val discogsGate = gateFor(discogs, caps)
        val discogsGuard = RateLimitedProviderTransport { discogsGate }
        val discogsArtists = DiscogsNativeArtistSearchClient(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
        )
        val discogsAlbums = DiscogsNativeReleaseSearchClient(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
        )
        // Count HTTP outcomes separately from logical edition lookups:
        // retries and positive cache hits otherwise obscure the cause of Unavailable.
        var detailHttpReads = 0
        var detailLocalBodyLimits = 0
        var detailHttp413 = 0
        var detailHttp5xx = 0
        var detailHttpOther = 0
        var detailIoFailures = 0
        var detailOtherFailures = 0
        val detailParseRejections = mutableMapOf<DiscogsReleaseParseRejection, Int>()
        val detailTransport = object : DiscogsTransport {
            private val upstream = KtorDiscogsTransport()
            override val supportsTokenAuthorization: Boolean get() = true
            private suspend fun observed(read: suspend () -> DiscogsHttpResponse): DiscogsHttpResponse {
                detailHttpReads++
                val response = try { read() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: java.io.IOException) {
                    detailIoFailures++
                    throw failure
                } catch (failure: Exception) {
                    detailOtherFailures++
                    throw failure
                }
                when {
                    response.localBodyLimitExceeded -> detailLocalBodyLimits++
                    response.status == 413 -> detailHttp413++
                    response.status in 500..599 -> detailHttp5xx++
                    response.status !in setOf(200, 401, 403, 404, 429) -> detailHttpOther++
                }
                return response
            }
            override suspend fun get(path: String, userAgent: String) =
                observed { upstream.get(path, userAgent) }
            override suspend fun getAuthenticated(path: String, userAgent: String, token: String) =
                observed { upstream.getAuthenticated(path, userAgent, token) }
        }
        val discogsDetails = DiscogsClient(
            enabled = token != null, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            admission = discogsGuard, tokenProvider = { token }, transport = detailTransport,
            onReleaseParseRejected = { reason ->
                detailParseRejections[reason] = (detailParseRejections[reason] ?: 0) + 1
            },
        )
        // Record only aggregate typed edition outcomes from the actual artist
        // loader. These are method calls (which may hit its positive cache),
        // not HTTP counts. Never serialize source IDs, names or bodies.
        var artistMasterCalls = 0
        var artistReleaseCalls = 0
        var artistMalformed = 0
        var artistUnavailable = 0
        var artistThrottled = 0
        var artistUnauthorized = 0
        var artistMissing = 0
        var artistDisabled = 0
        fun countArtistEdition(result: DiscogsLookupResult): DiscogsLookupResult {
            when (result) {
                DiscogsLookupResult.Malformed -> artistMalformed++
                DiscogsLookupResult.Unavailable -> artistUnavailable++
                is DiscogsLookupResult.Throttled -> artistThrottled++
                DiscogsLookupResult.Unauthorized -> artistUnauthorized++
                DiscogsLookupResult.Missing -> artistMissing++
                DiscogsLookupResult.Disabled -> artistDisabled++
                else -> Unit
            }
            return result
        }
        val discogsCatalog = DiscogsNativeArtistCatalog(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    artistMasterCalls++
                    return countArtistEdition(discogsDetails.fetchMaster(id))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    artistReleaseCalls++
                    return countArtistEdition(discogsDetails.fetchRelease(id))
                }
            },
        )
        for (provider in listOf(mb, apple, discogs)) {
            val sourceName = when (provider) {
                mb -> "musicbrainz"
                apple -> "itunes"
                else -> "discogs"
            }
            for ((index, case) in artistParityCases.withIndex()) {
                val target = if (case.extensive) 30 else 1
                val beforeMasterCalls = artistMasterCalls
                val beforeReleaseCalls = artistReleaseCalls
                val beforeMalformed = artistMalformed
                val beforeUnavailable = artistUnavailable
                val beforeThrottled = artistThrottled
                val beforeUnauthorized = artistUnauthorized
                val beforeMissing = artistMissing
                val beforeDisabled = artistDisabled
                val beforeHttpReads = detailHttpReads
                val beforeLocalLimits = detailLocalBodyLimits
                val beforeHttp413 = detailHttp413
                val beforeHttp5xx = detailHttp5xx
                val beforeHttpOther = detailHttpOther
                val beforeIoFailures = detailIoFailures
                val beforeOtherFailures = detailOtherFailures
                val beforeParseRejections = detailParseRejections.toMap()
                val started = System.nanoTime()
                var matched = false
                var profile = false
                var failed = false
                var incomplete = false
                var exhausted = false
                var visible = 0
                var landing = 0
                var gestures = 0
                var autoPages = 0
                var sections = 0
                var tracks = 0
                var profileStarted: Long? = null
                var firstProfileMs = 0
                var appleAnchorTitleQueries = 0
                var appleAnchorCreditMatches = 0
                fun observe(shelves: List<HomeShelf>, prefix: String) {
                    val ids = shelves.flatMap { it.items }.mapNotNull { it.browseId }
                    assertEquals("Duplicate artist release $sourceName/$index", ids.size, ids.distinct().size)
                    assertTrue("Foreign artist release $sourceName/$index", ids.all { it.startsWith(prefix) })
                    assertTrue("Artist profile lost releases $sourceName/$index", ids.size >= visible)
                    visible = ids.size
                    sections = shelves.size
                    if (!profile && visible > 0) {
                        profile = true
                        landing = visible
                        firstProfileMs = ((System.nanoTime() - (profileStarted ?: started)) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    }
                }
                val complete = try {
                    withTimeoutOrNull(if (case.extensive) 210_000L else 100_000L) {
                        val nameKey = artistParityFold(case.name)
                        val titleKey = artistParityFold(case.anchor)
                        val query = case.name + " " + case.anchor
                        when (provider) {
                            mb -> {
                                val artists = (mbSearch.searchArtists(case.name, 50)
                                    as? MusicBrainzCatalogSearchResult.Success)?.page?.items
                                val anchors = (mbSearch.searchReleaseGroups(query, 50)
                                    as? MusicBrainzCatalogSearchResult.Success)?.page?.items
                                if (artists == null || anchors == null) failed = true
                                val creditedIds = anchors.orEmpty()
                                    .filter { artistParityFold(it.title) == titleKey }
                                    .flatMap { it.artistCreditMbids.orEmpty() }.toSet()
                                val artist = artists.orEmpty().filter {
                                    artistParityFold(it.name) == nameKey &&
                                        it.musicBrainzId in creditedIds
                                }.distinctBy { it.musicBrainzId }.singleOrNull()
                                if (artist != null) {
                                    matched = true
                                    profileStarted = System.nanoTime()
                                    val first = (retryTransientArtistDiscography(fetch = {
                                        mbGraph.browseReleaseGroupsForArtist(artist.musicBrainzId, limit = 75)
                                    }) as? MusicBrainzCatalogGraphResult.Success)?.value
                                    if (first == null) failed = true
                                    else {
                                        val groups = linkedMapOf<String, MusicBrainzReleaseGroupHit>()
                                        fun publish(hits: List<MusicBrainzReleaseGroupHit>) {
                                            hits.forEach { groups.putIfAbsent(it.musicBrainzId, it) }
                                            val rows = groups.values.map { hit ->
                                                com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                                                    hit.musicBrainzId,
                                                    com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
                                                    hit.title, hit.artistDisplay,
                                                    releaseDate = hit.firstReleaseDate,
                                                    releaseKind = hit.primaryType,
                                                    secondaryTypes = hit.secondaryTypes,
                                                    artistScopeMbid = artist.musicBrainzId,
                                                )
                                            }
                                            observe(canonicalNativeSections(
                                                com.music.bitchord.ui.screens.CanonicalSearchKind.ARTIST,
                                                rows, "Álbuns", "Álbuns", "Singles e EPs",
                                            ), "catalog:RELEASE_GROUP:")
                                        }
                                        publish(first.items)
                                        val auto = MusicBrainzArtistDiscographyPager.collect(
                                            first, maxAdditionalPages = 4,
                                            fetch = { offset ->
                                                mbGraph.browseReleaseGroupsForArtist(artist.musicBrainzId, 75, offset)
                                            },
                                            onPage = { hits, _ -> autoPages++; publish(hits) },
                                        )
                                        var offset = auto.nextOffset
                                        while (visible < target && offset != null && gestures < 12) {
                                            val previous = offset
                                            val page = (mbGraph.browseReleaseGroupsForArtist(
                                                artist.musicBrainzId, offset = previous,
                                            ) as? MusicBrainzCatalogGraphResult.Success)?.value
                                            gestures++
                                            if (page == null || page.offset != previous ||
                                                page.nextOffset?.let { it <= previous } == true ||
                                                (page.consumedCount == 0 && page.total > previous)
                                            ) { failed = true; break }
                                            publish(page.items)
                                            offset = page.nextOffset
                                        }
                                        exhausted = offset == null
                                        incomplete = !auto.complete && offset != null
                                    }
                                }
                            }
                            apple -> {
                                val artists = appleRepo.searchArtists(case.name, "br", 50)
                                    as? AppleEditorialArtistLoad.Success
                                val anchors = appleRepo.searchAlbums(query, "br", 50)
                                    as? AppleEditorialLoad.Success
                                if (artists == null || anchors == null) failed = true
                                // Reuse the already frozen edition labels for
                                // this same work; no new corpus alias is introduced.
                                val acceptedAnchorTitles = setOf(titleKey) + albumParityCases.filter {
                                    artistParityFold(it.artist) == nameKey &&
                                        artistParityFold(it.title) == titleKey
                                }.flatMap { it.titleAliases }.map(::artistParityFold)
                                fun matchingCredits(load: AppleEditorialLoad.Success?): Set<Long> =
                                    load?.releases.orEmpty().filter {
                                        it.storefront == "br" && artistParityFold(it.title) in acceptedAnchorTitles &&
                                            artistParityFold(it.artistName) == nameKey
                                    }.mapNotNull { it.artistId }.toSet()
                                val creditedIds = matchingCredits(anchors).toMutableSet()
                                val exactArtistIds = artists?.artists.orEmpty().filter {
                                    artistParityFold(it.title) == nameKey
                                }.map { it.id }.toSet()
                                // Independently corroborate the frozen work by
                                // its title field if the compound search omitted it.
                                // Artist names alone still cannot establish identity.
                                if (!failed && exactArtistIds.isNotEmpty() &&
                                    creditedIds.intersect(exactArtistIds).isEmpty()
                                ) {
                                    appleAnchorTitleQueries++
                                    val titleProbe = appleRepo.searchAlbums(
                                        case.anchor, "br", 200,
                                        attribute = ItunesCatalogSearchAttribute.ALBUM_TERM,
                                    ) as? AppleEditorialLoad.Success
                                    if (titleProbe == null) failed = true
                                    creditedIds += matchingCredits(titleProbe)
                                }
                                appleAnchorCreditMatches = creditedIds.intersect(exactArtistIds).size
                                val artist = artists?.artists.orEmpty().filter {
                                    artistParityFold(it.title) == nameKey && it.id in creditedIds
                                }.distinctBy { it.id }.singleOrNull()
                                if (artist != null) {
                                    matched = true
                                    profileStarted = System.nanoTime()
                                    val pager = AppleArtistDiscographyPager(
                                        artist.id, artist.title, "br", pageSize = 24,
                                        source = ItunesArtistAlbumDiscoverySource(appleRepo),
                                    )
                                    fun publish(snapshot: AppleArtistPageSnapshot) {
                                        assertTrue(snapshot.visible.all {
                                            it.artistId == artist.id && it.storefront == "br"
                                        })
                                        val detail = appleNativeArtistDetail(
                                            AppleEditorialLoad.Success(snapshot.visible,
                                                if (snapshot.coverageUncertain)
                                                    AppleEditorialReadState.POSSIBLY_TRUNCATED
                                                else AppleEditorialReadState.COMPLETE),
                                            artist.scopedId, artist.title,
                                        )
                                        val native = detail?.let {
                                            com.music.bitchord.ui.screens.appleArtistAsSharedDetail(it)
                                        }
                                        assertNotNull("Apple shared detail missing", native)
                                        assertEquals(artist.scopedId, native?.page?.browseId)
                                        observe(native?.page?.sections.orEmpty(), "itunes:br:album:")
                                        incomplete = snapshot.coverageUncertain
                                    }
                                    var result = pager.start()
                                    if (result !is AppleArtistPageLoad.Success) failed = true
                                    else {
                                        publish(result.snapshot)
                                        while (visible < target && result is AppleArtistPageLoad.Success &&
                                            result.snapshot.hasMore && gestures < 12
                                        ) {
                                            result = pager.loadMore()
                                            gestures++
                                            if (result is AppleArtistPageLoad.Success) publish(result.snapshot)
                                            else failed = true
                                        }
                                        exhausted = !pager.snapshot().hasMore && !pager.snapshot().coverageUncertain
                                        val trackResult = appleRepo.artistTracks(artist.id, "br", 40)
                                            as? AppleEditorialTrackLoad.Success
                                        tracks = trackResult?.let(::appleNativeTrackSearchRows).orEmpty()
                                            .count { it.artistBrowseId == artist.scopedId }
                                    }
                                }
                            }
                            else -> if (token != null) {
                                val artists = (discogsArtists.search(case.name)
                                    as? DiscogsNativeArtistSearchResult.Success)?.page?.artists
                                val anchors = (discogsAlbums.search(query)
                                    as? DiscogsNativeReleaseSearchResult.Success)?.page?.releases
                                if (artists == null || anchors == null) failed = true
                                val searchIds = artists.orEmpty().filter {
                                    artistParityFold(com.music.bitchord.ui.screens.discogsArtistDisplayName(it.name)) == nameKey
                                }.map { it.artistId }.toSet()
                                val credited = linkedSetOf<Long>()
                                for (candidate in anchors.orEmpty().filter {
                                    artistParityFold(it.title).contains(titleKey)
                                }.take(5)) {
                                    val response = discogsDetails.fetchRelease(candidate.releaseId)
                                    if (response is DiscogsLookupResult.Throttled ||
                                        response is DiscogsLookupResult.Unauthorized ||
                                        response is DiscogsLookupResult.Disabled) {
                                        failed = true
                                        break
                                    }
                                    val record = (response as? DiscogsLookupResult.Found)?.record ?: continue
                                    if (record.link.id != candidate.releaseId || record.unofficial ||
                                        artistParityFold(record.title) != titleKey) continue
                                    val id = record.artistIds.singleOrNull() ?: continue
                                    if (id in searchIds) credited += id
                                }
                                val artist = artists.orEmpty().filter { it.artistId in credited }
                                    .distinctBy { it.artistId }.singleOrNull()
                                if (artist != null && !failed) {
                                    matched = true
                                    profileStarted = System.nanoTime()
                                    val known = mutableListOf<DiscogsNativeArtistRelease>()
                                    var page = 1
                                    var hasMore = true
                                    while (hasMore && visible < target && gestures <= 12) {
                                        val window = discogsCatalog.loadWindow(
                                            artist.artistId, page, known.toList(),
                                            onProgress = { partial ->
                                                val preview = discogsArtistPage(
                                                    com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow(
                                                        artist.artistId, artist.name,
                                                    ),
                                                    discogsMergeArtistPageReleases(known + partial),
                                                    true, incomplete,
                                                )
                                                observe(preview.page.sections, "discogs:release:")
                                                tracks = preview.tracks.size
                                            },
                                        )
                                        val result = window.result as? DiscogsNativeArtistLoad.Success
                                        if (result == null) { failed = true; break }
                                        assertTrue(result.releases.all { it.artistId == artist.artistId })
                                        known += result.releases
                                        assertEquals(known.size, discogsMergeArtistPageReleases(known).size)
                                        page += window.completedPages
                                        incomplete = incomplete || window.settledIncomplete
                                        hasMore = result.hasMore || result.retryCurrentPage
                                        val detail = discogsArtistPage(
                                            com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow(
                                                artist.artistId, artist.name,
                                            ),
                                            known, hasMore, incomplete,
                                        )
                                        assertEquals(artist.browseId, detail.page.browseId)
                                        observe(detail.page.sections, "discogs:release:")
                                        if (gestures == 0) landing = visible
                                        tracks = detail.tracks.size
                                        assertTrue(tracks <= 16)
                                        assertTrue(detail.tracks.all { track ->
                                            known.any { release ->
                                                track.key.startsWith("discogs:track:${release.releaseId}:") &&
                                                    track.credit == release.title
                                            }
                                        })
                                        if (result.retryCurrentPage) { failed = true; break }
                                        if (window.completedPages == 0) break
                                        if (visible < target && hasMore) {
                                            if (gestures == 12) break
                                            gestures++
                                        }
                                    }
                                    exhausted = !hasMore
                                }
                            }
                        }
                        true
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    false
                }
                val status = when {
                    provider == discogs && token == null -> "token_not_configured"
                    complete == null -> "timeout_or_partial"
                    failed -> "source_interruption"
                    !matched -> "identity_not_corroborated"
                    profile && visible >= target -> "target_reached"
                    exhausted && !incomplete -> "source_exhausted_below_target"
                    incomplete -> "incomplete_coverage_below_target"
                    else -> "gesture_budget_below_target"
                }
                report("artist_matrix_" + sourceName + "_" + (index + 1).toString().padStart(2, '0'), Sample(
                    status = status, eligible = visible, visible = visible,
                    counts = mapOf(
                        "anchor_verified" to if (matched) 1 else 0,
                        "profile_projection_verified" to if (profile) 1 else 0,
                        "verified_target" to target,
                        "landing_visible_releases" to landing,
                        "source_gestures" to gestures,
                        "automatic_pages" to autoPages,
                        "apple_anchor_title_queries" to appleAnchorTitleQueries,
                        "apple_anchor_credit_matches" to appleAnchorCreditMatches,
                        "artist_master_lookup_calls" to (artistMasterCalls - beforeMasterCalls),
                        "artist_release_lookup_calls" to (artistReleaseCalls - beforeReleaseCalls),
                        "artist_edition_malformed" to (artistMalformed - beforeMalformed),
                        "artist_edition_unavailable" to (artistUnavailable - beforeUnavailable),
                        "artist_edition_throttled" to (artistThrottled - beforeThrottled),
                        "artist_edition_unauthorized" to (artistUnauthorized - beforeUnauthorized),
                        "artist_edition_missing" to (artistMissing - beforeMissing),
                        "artist_edition_disabled" to (artistDisabled - beforeDisabled),
                        "detail_http_reads" to (detailHttpReads - beforeHttpReads),
                        "detail_local_body_limits" to (detailLocalBodyLimits - beforeLocalLimits),
                        "detail_upstream_http_413" to (detailHttp413 - beforeHttp413),
                        "detail_http_5xx" to (detailHttp5xx - beforeHttp5xx),
                        "detail_other_http_failures" to (detailHttpOther - beforeHttpOther),
                        "detail_io_failures" to (detailIoFailures - beforeIoFailures),
                        "detail_other_transport_failures" to (detailOtherFailures - beforeOtherFailures),
                        "release_sections" to sections,
                        "verified_track_rows" to tracks,
                        "first_profile_ms" to firstProfileMs,
                        "incomplete_coverage" to if (incomplete) 1 else 0,
                        "elapsed_ms" to ((System.nanoTime() - started) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ) + DiscogsReleaseParseRejection.values().associate { reason ->
                        "detail_parse_" + reason.name.lowercase(java.util.Locale.ROOT) to
                            ((detailParseRejections[reason] ?: 0) -
                                (beforeParseRejections[reason] ?: 0))
                    },
                ))
            }
        }
    }

    private data class AlbumParityCase(
        val artist: String, val title: String, val kind: String,
        val artistAliases: Set<String> = emptySet(),
        val titleAliases: Set<String> = emptySet(),
    ) {
        fun matchesArtist(value: String?): Boolean =
            value != null && (artistAliases + artist).any {
                artistParityKey(it) == artistParityKey(value)
            }
        fun matchesTitle(value: String?): Boolean =
            value != null && (titleAliases + title).any {
                artistParityKey(it) == artistParityKey(value)
            }
    }

    companion object {
        private fun artistParityKey(value: String): String =
            java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
                .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
    }

    // A release can have different editions across stores. These cases
    // identify works, not a fabricated equivalence of physical pressings.
    // In the last five cases prefer a positively evidenced multidisc edition.
    private val albumParityCases = listOf(
        AlbumParityCase("David Bowie", "Hunky Dory", "album",
            titleAliases = setOf("Hunky Dory (2015 Remaster)", "Hunky Dory (Remastered)")),
        AlbumParityCase("Iron Maiden", "Powerslave", "album",
            titleAliases = setOf("Powerslave (2015 Remaster)", "Powerslave (Remastered)")),
        AlbumParityCase("Depeche Mode", "Violator", "album",
            titleAliases = setOf("Violator (Deluxe)", "Violator (2006 Remaster)")),
        AlbumParityCase("Portishead", "Dummy", "album"),
        AlbumParityCase("Massive Attack", "Mezzanine", "album",
            titleAliases = setOf("Mezzanine (Deluxe Edition)")),
        AlbumParityCase("New Order", "Blue Monday", "single_ep",
            titleAliases = setOf("Blue Monday - Single", "Blue Monday - EP")),
        AlbumParityCase("Depeche Mode", "Personal Jesus", "single_ep",
            titleAliases = setOf("Personal Jesus - Single", "Personal Jesus - EP")),
        AlbumParityCase("New Order", "The Perfect Kiss", "single_ep",
            titleAliases = setOf("The Perfect Kiss - Single", "The Perfect Kiss - EP")),
        AlbumParityCase("Aphex Twin", "Come to Daddy", "single_ep",
            titleAliases = setOf("Come to Daddy - EP", "Come To Daddy (EP)")),
        AlbumParityCase("Joy Division", "Love Will Tear Us Apart", "single_ep",
            titleAliases = setOf("Love Will Tear Us Apart - Single", "Love Will Tear Us Apart - EP")),
        AlbumParityCase("Pink Floyd", "The Wall", "multi",
            titleAliases = setOf("The Wall (Remastered)")),
        AlbumParityCase("The Beatles", "The Beatles", "multi",
            artistAliases = setOf("Beatles, The"),
            titleAliases = setOf("The Beatles (White Album)", "The Beatles (Remastered)",
                "The Beatles (White Album) [Super Deluxe]")),
        AlbumParityCase("Led Zeppelin", "Physical Graffiti", "multi",
            titleAliases = setOf("Physical Graffiti (Remastered)", "Physical Graffiti (Deluxe Edition)")),
        AlbumParityCase("The Smashing Pumpkins", "Mellon Collie and the Infinite Sadness", "multi",
            artistAliases = setOf("Smashing Pumpkins"),
            titleAliases = setOf("Mellon Collie and the Infinite Sadness (Remastered)",
                "Mellon Collie and the Infinite Sadness (Deluxe Edition)")),
        AlbumParityCase("Nine Inch Nails", "The Fragile", "multi",
            titleAliases = setOf("The Fragile (Remastered)")),
    )

    /**
     * #229 fixed 45 cells. Select a source-owned candidate by labelled
     * artist/title, then open its exact Release/collection route.
     * The detailed comparison uses the same production projections as the
     * app. It neither equates IDs across providers nor claims UI back-stack
     * validation. Decoded endpoint counts are not raw network row counts.
     */
    @Test fun live_album_parity_matrix() = runBlocking {
        assumeTrue("Only the observational CI job may access remote services",
            System.getenv("IDM_CATALOG_LIVE") == "1")
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val mbGate = gateFor(mb, caps)
        val mbClient = MusicBrainzClient(admission = { mbGate })
        val mbSearch = MusicBrainzCatalogSearch(mbClient)
        val mbGraph = MusicBrainzCatalogGraph(mbClient)
        val appleGate = gateFor(apple, caps)
        val appleClient = ItunesCatalogClient(
            transport = KtorItunesCatalogTransport(
                admission = RateLimitedProviderTransport { appleGate },
            ),
        )
        val appleRepo = AppleEditorialCatalogRepository(appleClient)
        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(String::isNotBlank)
        val discogsGate = gateFor(discogs, caps)
        val discogsGuard = RateLimitedProviderTransport { discogsGate }
        val discogsSearch = DiscogsNativeReleaseSearchClient(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
        )
        val discogsCdSearch = DiscogsNativeReleaseSearchClient(
            transport = KtorDiscogsNativeArtistSearchTransport(entity = "release", cdEditionsOnly = true),
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
        )
        val discogsDetails = DiscogsClient(
            enabled = token != null, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            admission = discogsGuard, tokenProvider = { token },
        )
        val discogsArtist = DiscogsNativeArtistCatalog(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
            editions = DiscogsNativeEditionClient(discogsDetails),
        )
        val providers = when (System.getenv("IDM_CATALOG_LIVE_SCOPE") ?: "full") {
            "full" -> listOf(mb, apple, discogs)
            "itunes-albums" -> listOf(apple)
            else -> error("Unsupported IDM live album scope")
        }
        for (provider in providers) {
            val sourceName = when (provider) {
                mb -> "musicbrainz"
                apple -> "itunes"
                else -> "discogs"
            }
            for ((index, case) in albumParityCases.withIndex()) {
                val start = System.nanoTime()
                var matched = false
                var projected = false
                var failed = false
                var partial = false
                var typeVerified = false
                var appleHeaderTypeVerified = false
                var appleTrackLabelTypeVerified = false
                var appleRuleCandidate = ItunesEditorialReleaseFormat.UNCLASSIFIED
                var appleRuleTypeMatches = false
                var appleRuleExplicitConflict = false
                var appleCommercialTypeMatches = false
                var appleDisplayTypeMatches = false
                var appleCommercialLowerBoundAlbum = false
                var appleDisplayTypeKnown = false
                var appleSelectedPublicOfferVerified = false
                var appleIndependentOfferDisplayKnown = false
                var appleIndependentOfferDisplayMatches = false
                var decoded = 0
                var displayed = 0
                var discs = 0
                var durations = 0
                var positions = 0
                var artistRoute = 0
                var related = 0
                var versions = 0
                var versionCoverageUncertain = false
                var candidatesRead = 0
                var searchGestures = 0
                var partialAppleCandidates = 0
                var discogsFormatQueries = 0
                var appleSearchRequests = 0
                var appleArtistCollectionDiscoveryCalls = 0
                var appleArtistCollectionVerifiedCandidates = 0
                var appleSongCollectionDiscoveryCalls = 0
                var appleSongCollectionVerifiedCandidates = 0
                var appleSearchRawRows = 0
                var appleSearchCreditMatches = 0
                var appleSearchTitleMatches = 0
                var appleSearchWorkMatches = 0
                var appleSearchWorkRankAdmitted = 0
                var appleAlbumDiagnostics: AppleAlbumReadDiagnostics? = null
                var appleSevenPlusAudioTracks = false
                var appleIndependentOfferAttempted = false
                var appleIndependentOfferVerified = false
                var appleIndependentDetailComplete = false
                var appleIndependentFrozenTitleMatches = false
                var appleFrozenTitleVerified = false
                var appleQualifiedEditionVerified = false
                var appleRelatedYearEditionVerified = false
                var appleRelatedYearEditionComplete = false
                var appleAlternativeTrackRouteVerified = false
                var appleVideoProbeAttempted = false
                var appleVideoProbeSameCollection = false
                var appleVideoProbeVerifiedRows = 0
                var appleVideoProbeCountReconciled = false
                val complete = try {
                    withTimeoutOrNull(120_000L) {
                        val query = case.artist + " " + case.title
                        when (provider) {
                            mb -> {
                                val search = mbSearch.searchReleases(query, 50)
                                    as? MusicBrainzCatalogSearchResult.Success
                                if (search == null) failed = true
                                val candidates = search?.page?.items.orEmpty().filter {
                                    case.matchesTitle(it.title) && case.matchesArtist(it.artistDisplay) &&
                                        isCuratedMusicBrainzRelease(it)
                                }
                                var chosen: MusicBrainzCatalogReleaseDetail? = null
                                var selected: MusicBrainzReleaseHit? = null
                                for (candidate in candidates.take(if (case.kind == "multi") 6 else 3)) {
                                    candidatesRead++
                                    val detail = (mbGraph.lookupRelease(candidate.musicBrainzId)
                                        as? MusicBrainzCatalogGraphResult.Success)?.value
                                    if (detail == null) { failed = true; continue }
                                    assertEquals(candidate.musicBrainzId, detail.musicBrainzId)
                                    if (!case.matchesTitle(detail.title) ||
                                        !case.matchesArtist(detail.artistDisplay) ||
                                        !detail.status.equals("Official", ignoreCase = true)
                                    ) continue
                                    val page = canonicalVerifiedLiveReleaseDetailPage(detail, null)
                                    if (page == null) { partial = true; continue }
                                    chosen = detail
                                    selected = candidate
                                    if (case.kind != "multi" ||
                                        detail.tracks.map { it.mediumNumber }.distinct().size >= 2) break
                                }
                                if (chosen != null && selected != null) {
                                    val detail = chosen
                                    matched = true
                                    val page = requireNotNull(canonicalVerifiedLiveReleaseDetailPage(detail, null))
                                    assertEquals(detail.title, page.title)
                                    val expected = detail.tracks.sortedWith(
                                        compareBy<MusicBrainzCatalogTrack> { it.mediumNumber }
                                            .thenBy { it.trackNumber },
                                    )
                                    assertEquals(expected.map { it.trackMbid }, page.rows.map { it.trackMbid })
                                    assertEquals(expected.map { it.recordingMbid }, page.rows.map { it.musicBrainzId })
                                    assertEquals(expected.map { it.durationMs }, page.rows.map { it.durationMs })
                                    assertTrue(page.rows.none { it.hasCatalogedAsset })
                                    decoded = expected.size
                                    displayed = page.rows.size
                                    discs = expected.map { it.mediumNumber }.distinct().size
                                    positions = expected.size
                                    durations = expected.count { it.durationMs != null }
                                    projected = displayed > 0
                                    partial = !detail.completeness.countsExplicitAndExact ||
                                        !detail.completeness.positionsExplicitUnique
                                    val group = detail.groupMbid
                                    if (group != null) {
                                        val groupDetail = (mbGraph.lookupReleaseGroup(group)
                                            as? MusicBrainzCatalogGraphResult.Success)?.value
                                        val primary = groupDetail?.primaryType
                                        typeVerified = if (case.kind == "single_ep")
                                            primary in setOf("Single", "EP") else primary == "Album"
                                        val alternatives = (mbGraph.browseGroupReleases(group, 75)
                                            as? MusicBrainzCatalogGraphResult.Success)?.value
                                        if (alternatives != null) {
                                            versions = canonicalReleaseGroupEditionRows(
                                                group, alternatives.items, detail.musicBrainzId,
                                            ).size
                                            versionCoverageUncertain = alternatives.nextOffset != null
                                        } else versionCoverageUncertain = true
                                    }
                                    // Artist relation navigation is not exercised by this direct
                                    // release fallback projection; do not report it as verified.
                                }
                            }
                            apple -> {
                                // Use the actual native search pager, not a separate raw
                                // 50-result repository query. Frozen title/credit labels still
                                // independently select the identity; no corpus substitutions.
                                val actualSource = ItunesPublicSearchSource(appleClient)
                                val observedSource = object : ApplePublicSearchSource {
                                    override val supportsArtistCollectionDiscovery = actualSource.supportsArtistCollectionDiscovery
                                    override suspend fun discoverArtistCollections(
                                        originalQuery: String, artistId: Long, credit: String,
                                        country: String, knownIds: Set<Long>,
                                    ): ItunesCatalogResult {
                                        appleArtistCollectionDiscoveryCalls++
                                        val response = actualSource.discoverArtistCollections(
                                            originalQuery, artistId, credit, country, knownIds,
                                        )
                                        if (response is ItunesCatalogResult.Success)
                                            appleArtistCollectionVerifiedCandidates += response.items.size
                                        return response
                                    }
                                    override val supportsSongCollectionDiscovery = actualSource.supportsSongCollectionDiscovery
                                    override suspend fun discoverSongCollections(
                                        originalQuery: String, workTerm: String, country: String, knownIds: Set<Long>,
                                    ): ItunesCatalogResult {
                                        appleSongCollectionDiscoveryCalls++
                                        val response = actualSource.discoverSongCollections(
                                            originalQuery, workTerm, country, knownIds,
                                        )
                                        if (response is ItunesCatalogResult.Success)
                                            appleSongCollectionVerifiedCandidates += response.items.size
                                        return response
                                    }
                                    override suspend fun search(
                                        term: String, entity: ItunesCatalogEntity, country: String,
                                        attribute: ItunesCatalogSearchAttribute?,
                                    ): ItunesCatalogResult {
                                        appleSearchRequests++
                                        val response = actualSource.search(term, entity, country, attribute)
                                        if (response is ItunesCatalogResult.Success) {
                                            val rows = response.items
                                            appleSearchRawRows += rows.size
                                            appleSearchCreditMatches += rows.count { case.matchesArtist(it.artistName) }
                                            appleSearchTitleMatches += rows.count { case.matchesTitle(it.title) }
                                            val exact = rows.filter {
                                                case.matchesTitle(it.title) && case.matchesArtist(it.artistName)
                                            }
                                            appleSearchWorkMatches += exact.size
                                            appleSearchWorkRankAdmitted += exact.count {
                                                applePublicSearchMatchRank(query, it) != null
                                            }
                                        }
                                        return response
                                    }
                                }
                                val pager = ApplePublicSearchPager(query, ItunesCatalogEntity.ALBUM,
                                    "br", source = observedSource)
                                val candidateBudget = if (case.kind == "multi") 6 else 3
                                // The original frozen aliases remain independently reported.
                                // Only explicitly recognized mastering/edition labels may nominate
                                // another commercial edition of the same work, never a bare year
                                // suffix, sequel, live recording or tribute.
                                fun acceptedAppleTitle(value: String): Boolean =
                                    case.matchesTitle(value) || (
                                        applePublicAlbumWorkKey(value) != searchLabelKey(value) &&
                                            applePublicAlbumWorkKey(value) == searchLabelKey(case.title)
                                    )
                                var search = pager.start()
                                var selected: ItunesEditorialRelease? = null
                                var result: AppleEditorialLoad.Success? = null
                                var selectedQuality = -1
                                val attemptedIds = linkedSetOf<Long>()
                                var verifiedGoal = false
                                var relatedYearEdition: ItunesCatalogItem? = null
                                fun matchesRelatedYearEdition(row: ItunesCatalogItem): Boolean {
                                    // A year-labelled reissue/rework is separately relevant,
                                    // but not evidence of the originally frozen release.
                                    val base = applePublicAlbumWorkKey(row.title)
                                    val work = searchLabelKey(case.title)
                                    return case.kind == "single_ep" &&
                                        case.matchesArtist(row.artistName) && row.artistId != null &&
                                        base.startsWith("$work ") &&
                                        Regex("(19|20)[0-9]{2}").matches(base.removePrefix("$work ")) &&
                                        ItunesEditorialCatalog.formatFromAppleTitle(row.title) in
                                            setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
                                }
                                // Hydrate a visible candidate before spending the remaining
                                // search budget. Continue only if the edition is incomplete,
                                // does not meet the disc goal, or no suitable candidate exists.
                                while (search is ApplePublicSearchPageLoad.Success) {
                                    val visible = search.snapshot.visible
                                    if (relatedYearEdition == null)
                                        relatedYearEdition = visible.firstOrNull(::matchesRelatedYearEdition)
                                    val candidates = visible.filter {
                                        acceptedAppleTitle(it.title) && case.matchesArtist(it.artistName) &&
                                            it.artistId != null && it.id !in attemptedIds
                                    }.sortedBy { if (case.matchesTitle(it.title)) 0 else 1 }
                                    for (candidate in candidates.take(candidateBudget - attemptedIds.size)) {
                                        attemptedIds += candidate.id
                                        candidatesRead++
                                        val response = appleRepo.albumDetail(candidate.id, "br")
                                            as? AppleEditorialLoad.Success
                                        if (response == null) { failed = true; break }
                                        val record = response.releases.singleOrNull() ?: continue
                                        assertEquals(candidate.scopedId, record.appleScopedId)
                                        if (!acceptedAppleTitle(record.title) || !case.matchesArtist(record.artistName) ||
                                            candidate.artistId != record.artistId || record.tracks.isEmpty()
                                        ) continue
                                        val completeTracks = response.state == AppleEditorialReadState.COMPLETE
                                        val multi = record.tracks.mapNotNull { it.discNumber }.distinct().size >= 2
                                        if (!completeTracks) partialAppleCandidates++
                                        val meetsDiscGoal = case.kind != "multi" || multi
                                        val quality = (if (meetsDiscGoal) 2 else 0) + (if (completeTracks) 1 else 0)
                                        if (quality > selectedQuality) {
                                            selected = record
                                            result = response
                                            selectedQuality = quality
                                        }
                                        if (completeTracks && meetsDiscGoal) {
                                            verifiedGoal = true
                                            break
                                        }
                                    }
                                    if (verifiedGoal || failed || attemptedIds.size >= candidateBudget ||
                                        !search.snapshot.hasMore || searchGestures >= 12
                                    ) break
                                    searchGestures++
                                    search = pager.loadMore()
                                }
                                if (search is ApplePublicSearchPageLoad.Failure) failed = true
                                val yearCandidate = relatedYearEdition
                                if (selected == null && !failed && yearCandidate != null) {
                                    val alternative = appleRepo.albumDetail(yearCandidate.id, "br")
                                        as? AppleEditorialLoad.Success
                                    val detail = alternative?.releases?.singleOrNull()?.takeIf {
                                        it.appleScopedId == yearCandidate.scopedId &&
                                            it.title == yearCandidate.title && it.artistId == yearCandidate.artistId &&
                                            case.matchesArtist(it.artistName) && it.storefront == "br"
                                    }
                                    appleRelatedYearEditionVerified = detail != null
                                    appleRelatedYearEditionComplete = detail != null &&
                                        alternative?.state == AppleEditorialReadState.COMPLETE &&
                                        detail.tracks.isNotEmpty()
                                }
                                // An unavailable standalone single must not make its
                                // music disappear. Observe the native track route separately:
                                // exact source title/credit and collection membership, never
                                // claim that a compilation is the missing original edition.
                                if (selected == null && !failed && case.kind == "single_ep") {
                                    val songPager = ApplePublicSearchPager(query, ItunesCatalogEntity.SONG,
                                        "br", source = ItunesPublicSearchSource(appleClient))
                                    val songPage = songPager.start() as? ApplePublicSearchPageLoad.Success
                                    val song = songPage?.snapshot?.visible?.firstOrNull {
                                        it.type == ItunesCatalogItemType.TRACK &&
                                            it.artistId != null && case.matchesArtist(it.artistName) &&
                                            searchLabelKey(it.title) == searchLabelKey(case.title) &&
                                            it.collectionId != null && !it.collectionTitle.isNullOrBlank()
                                    }
                                    if (song != null) {
                                        val collection = appleRepo.albumDetail(requireNotNull(song.collectionId), "br")
                                            as? AppleEditorialLoad.Success
                                        val record = collection?.releases?.singleOrNull()
                                        appleAlternativeTrackRouteVerified =
                                            record != null && record.appleCollectionId == song.collectionId &&
                                            record.storefront == song.storefront &&
                                            record.title == song.collectionTitle &&
                                            record.tracks.any {
                                                it.scopedId == song.scopedId && it.title == song.title &&
                                                    it.artistId == song.artistId &&
                                                    it.artistName == song.artistName
                                            }
                                    }
                                }
                                // Independent storefront offers documented on 2026-11-24.
                                // These public Apple BR URLs nominate an exact lookup ONLY.
                                // They are not injected into production search, do not replace
                                // the frozen corpus, and cannot turn a missing case green.
                                // The 2011 EP is explicitly a different commercial edition.
                                val offer = when (index) {
                                    // https://music.apple.com/br/album/blue-monday-2020-digital-master/1526111071
                                    5 -> 1526111071L to "Blue Monday (2020 Digital Master)"
                                    // https://music.apple.com/br/album/personal-jesus-2011-ep/665719937
                                    6 -> 665719937L to "Personal Jesus 2011 - EP"
                                    // Public US offer; BR lookup must independently prove availability.
                                    // https://music.apple.com/us/album/the-perfect-kiss-2022-digital-master-single/1677848600
                                    7 -> 1677848600L to "The Perfect Kiss (2022 Digital Master) - Single"
                                    // https://music.apple.com/br/album/come-to-daddy/282478739
                                    8 -> 282478739L to "Come to Daddy"
                                    // Public US offer, independently check BR and do not replace a partial result.
                                    // https://music.apple.com/us/album/mellon-collie-and-the-infinite-sadness-remastered-2012/721224313
                                    13 -> 721224313L to "Mellon Collie And The Infinite Sadness (2012 Remastered)"
                                    // https://music.apple.com/br/album/the-fragile/1440836334
                                    14 -> 1440836334L to "The Fragile"
                                    else -> null
                                }
                                if ((selected == null || result?.state == AppleEditorialReadState.PARTIAL_TRACKLIST) &&
                                    offer != null && selected?.appleCollectionId != offer.first
                                ) {
                                    appleIndependentOfferAttempted = true
                                    val independent = appleRepo.albumDetail(offer.first, "br")
                                        as? AppleEditorialLoad.Success
                                    val proof = independent?.releases?.singleOrNull()?.takeIf {
                                        it.appleCollectionId == offer.first && it.storefront == "br" &&
                                            case.matchesArtist(it.artistName) &&
                                            artistParityKey(it.title) == artistParityKey(offer.second)
                                    }
                                    if (proof != null) {
                                        appleIndependentOfferVerified = true
                                        appleIndependentOfferDisplayKnown =
                                            proof.displayFormat != ItunesEditorialReleaseFormat.UNCLASSIFIED
                                        appleIndependentOfferDisplayMatches = if (case.kind == "single_ep")
                                            proof.displayFormat in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
                                            else proof.displayFormat == ItunesEditorialReleaseFormat.ALBUM
                                        appleIndependentFrozenTitleMatches = case.matchesTitle(proof.title)
                                        appleIndependentDetailComplete =
                                            independent?.state == AppleEditorialReadState.COMPLETE &&
                                                proof.tracks.isNotEmpty()
                                    }
                                }
                                if (selected != null && result != null) {
                                    val record = selected
                                    appleSelectedPublicOfferVerified = offer != null &&
                                        record.appleCollectionId == offer.first && record.storefront == "br" &&
                                        case.matchesArtist(record.artistName) &&
                                        artistParityKey(record.title) == artistParityKey(offer.second)
                                    appleAlbumDiagnostics = result.albumDiagnostics
                                    appleFrozenTitleVerified = case.matchesTitle(record.title)
                                    appleQualifiedEditionVerified = !appleFrozenTitleVerified &&
                                        acceptedAppleTitle(record.title)
                                    matched = true
                                    // An extra non-audio entity observation is diagnostic only.
                                    // Do not infer missing songs from a video associated by name.
                                    val expectedCount = record.trackCount
                                    if (result.state == AppleEditorialReadState.PARTIAL_TRACKLIST &&
                                        expectedCount != null && expectedCount > record.tracks.size &&
                                        result.albumDiagnostics?.let {
                                            !it.sourcePossiblyTruncated && it.positionlessTracks == 0 &&
                                                it.duplicatePositions == 0 &&
                                                it.lookup?.let { read ->
                                                    read.rejectedSongRows == 0 &&
                                                        read.removedDuplicateOrDisputedSongs == 0 &&
                                                        read.foreignSongRows == 0
                                                } == true
                                        } == true
                                    ) {
                                        appleVideoProbeAttempted = true
                                        val video = appleClient.observeAlbumMusicVideoRows(record.appleCollectionId, "br")
                                            as? ItunesCatalogResult.Success
                                        val same = video?.items?.singleOrNull {
                                            it.type == ItunesCatalogItemType.ALBUM &&
                                                it.id == record.appleCollectionId && it.storefront == "br" &&
                                                it.title == record.title && it.artistId == record.artistId &&
                                                it.trackCount == expectedCount
                                        }
                                        appleVideoProbeSameCollection = same != null
                                        appleVideoProbeVerifiedRows = video?.albumLookupObservation?.verifiedMusicVideoRows ?: 0
                                        appleVideoProbeCountReconciled = same != null &&
                                            video?.possiblyTruncated == false &&
                                            appleVideoProbeVerifiedRows == expectedCount - record.tracks.size
                                    }
                                    val detail = requireNotNull(appleNativeAlbumDetail(result, record.appleScopedId))
                                    val page = requireNotNull(com.music.bitchord.ui.screens.appleAlbumAsSharedDetail(detail))
                                    assertEquals(record.appleScopedId, page.page.browseId)
                                    assertEquals(record.title, page.page.title)
                                    val expected = record.tracks.sortedWith(
                                        compareBy<ItunesCatalogItem> { it.discNumber ?: Int.MAX_VALUE }
                                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.id },
                                    )
                                    assertEquals(expected.map { it.scopedId }, page.metadataTracks.map { it.key })
                                    assertEquals(expected.map { it.title }, page.metadataTracks.map { it.title })
                                    assertEquals(expected.map { it.discNumber }, page.metadataTracks.map { it.discNumber })
                                    assertEquals(expected.map { it.trackNumber }, page.metadataTracks.map { it.trackNumber })
                                    assertEquals(expected.map { it.durationMs }, page.metadataTracks.map { it.durationMs })
                                    decoded = expected.size
                                    displayed = page.metadataTracks.size
                                    discs = expected.mapNotNull { it.discNumber }.distinct().size
                                    positions = expected.count { it.trackNumber != null }
                                    durations = expected.count { it.durationMs != null }
                                    projected = displayed > 0
                                    partial = detail.partialTracklist
                                    val formatMatches = if (case.kind == "single_ep")
                                        record.format in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
                                        else record.format == ItunesEditorialReleaseFormat.ALBUM
                                    appleSevenPlusAudioTracks = result.state == AppleEditorialReadState.COMPLETE &&
                                        record.tracks.size >= 7
                                    typeVerified = formatMatches
                                    fun matchesExpected(format: ItunesEditorialReleaseFormat): Boolean =
                                        if (case.kind == "single_ep")
                                            format in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
                                        else format == ItunesEditorialReleaseFormat.ALBUM
                                    appleCommercialTypeMatches = matchesExpected(record.commercialAudioFormat)
                                    appleDisplayTypeMatches = matchesExpected(record.displayFormat)
                                    appleDisplayTypeKnown = record.displayFormat != ItunesEditorialReleaseFormat.UNCLASSIFIED
                                    appleCommercialLowerBoundAlbum = partial &&
                                        record.commercialAudioFormat == ItunesEditorialReleaseFormat.ALBUM
                                    assertEquals(record.displayFormat.name, detail.album.category.name)
                                    val headerFormat = ItunesEditorialCatalog.formatFromAppleTitle(record.title)
                                    appleHeaderTypeVerified = formatMatches && headerFormat == record.format
                                    appleTrackLabelTypeVerified = formatMatches && headerFormat == ItunesEditorialReleaseFormat.UNCLASSIFIED
                                    appleRuleCandidate = ItunesEditorialCatalog.observeAppleAudioRuleCandidate(
                                        record, result.state == AppleEditorialReadState.COMPLETE,
                                    )
                                    appleRuleTypeMatches = if (case.kind == "single_ep")
                                        appleRuleCandidate in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
                                        else appleRuleCandidate == ItunesEditorialReleaseFormat.ALBUM
                                    appleRuleExplicitConflict = record.format != ItunesEditorialReleaseFormat.UNCLASSIFIED &&
                                        appleRuleCandidate != ItunesEditorialReleaseFormat.UNCLASSIFIED &&
                                        record.format != appleRuleCandidate
                                    record.artistId?.let { artistId ->
                                        assertEquals("itunes:br:artist:$artistId", page.albumArtistBrowseId)
                                        artistRoute = 1
                                        val siblings = appleRepo.artistDiscography(artistId, "br", 40)
                                            as? AppleEditorialLoad.Success
                                        if (siblings != null) {
                                            val rows = appleNativeSearchRows(siblings.copy(
                                                releases = siblings.releases.filter {
                                                    it.artistId == artistId && it.appleCollectionId != record.appleCollectionId
                                                },
                                            ))
                                            val enriched = requireNotNull(com.music.bitchord.ui.screens.appleAlbumAsSharedDetail(
                                                detail.copy(relatedAlbums = rows),
                                            ))
                                            related = enriched.page.sections.sumOf { it.items.size }
                                            assertTrue(enriched.page.sections.flatMap { it.items }.all { item ->
                                                rows.any { it.browseId == item.browseId &&
                                                    it.artistBrowseId == page.albumArtistBrowseId }
                                            })
                                        }
                                    }
                                }
                            }
                            else -> if (token != null) {
                                val search = discogsSearch.search(query)
                                    as? DiscogsNativeReleaseSearchResult.Success
                                if (search == null) failed = true
                                var chosen: DiscogsRecord? = null
                                var selectedQuality = -1
                                fun matching(hits: List<DiscogsNativeReleaseSearchHit>) = hits.filter {
                                    artistParityKey(it.title).contains(artistParityKey(case.title))
                                }
                                val normal = matching(search?.page?.releases.orEmpty())
                                // Discogs vinyl sides can be auto-coupled. Do not
                                // invent physical disc numbers from A/B/C/D.
                                // For the frozen multidisc cases, sample CD
                                // candidates as well as unfiltered editions, using
                                // the same eight-detail budget and exact ID route.
                                val cd = if (case.kind == "multi" && search != null) {
                                    discogsFormatQueries++
                                    val filtered = discogsCdSearch.search(query)
                                    if (filtered is DiscogsNativeReleaseSearchResult.Success)
                                        matching(filtered.page.releases)
                                    else {
                                        failed = true
                                        emptyList()
                                    }
                                } else emptyList()
                                val candidates = if (case.kind == "multi")
                                    discogsMultidiscSearchCandidates(cd, normal)
                                else normal.take(5)
                                // Search titles/format are hints, never the selected identity.
                                for (candidate in candidates) {
                                    candidatesRead++
                                    val response = discogsDetails.fetchRelease(candidate.releaseId)
                                    if (response is DiscogsLookupResult.Throttled ||
                                        response is DiscogsLookupResult.Unauthorized ||
                                        response is DiscogsLookupResult.Disabled
                                    ) { failed = true; break }
                                    val record = (response as? DiscogsLookupResult.Found)?.record ?: continue
                                    assertEquals(candidate.releaseId, record.link.id)
                                    if (record.unofficial || !case.matchesTitle(record.title) ||
                                        !case.matchesArtist(record.artistNames.singleOrNull()?.let {
                                            com.music.bitchord.ui.screens.discogsArtistDisplayName(it)
                                        }) || record.displayTracks.isEmpty()
                                    ) continue
                                    val trial = discogsReleasePage(record.artistNames.single(), record)
                                    val multi = trial.tracks.mapNotNull { it.discNumber }.distinct().size >= 2
                                    val meetsDiscGoal = case.kind != "multi" || multi
                                    val completeTracks = !record.nativeTracklistIncomplete
                                    val typeMatches = record.formats.any { format ->
                                        if (case.kind == "single_ep")
                                            format.equals("Single", true) || format.equals("EP", true)
                                        else format.equals("Album", true)
                                    }
                                    // A complete tracklist alone does not prove the requested
                                    // release type. Continue within the existing detail budget.
                                    val quality = (if (meetsDiscGoal) 4 else 0) +
                                        (if (completeTracks) 2 else 0) + (if (typeMatches) 1 else 0)
                                    if (quality > selectedQuality) {
                                        chosen = record
                                        selectedQuality = quality
                                    }
                                    if (meetsDiscGoal && completeTracks && typeMatches) break
                                }
                                if (chosen != null) {
                                    val record = chosen
                                    matched = true
                                    val page = discogsReleasePage(record.artistNames.single(), record)
                                    assertEquals("discogs:release:${record.link.id}", page.page.browseId)
                                    assertEquals(record.title, page.page.title)
                                    assertEquals(record.displayTracks.map { it.title }, page.tracks.map { it.title })
                                    assertEquals(record.displayTracks.map { it.position }, page.tracks.map { it.sourcePosition })
                                    assertEquals(record.displayTracks.map { it.durationMs }, page.tracks.map { it.durationMs })
                                    assertEquals(page.tracks.size, page.tracks.map { it.key }.distinct().size)
                                    decoded = record.displayTracks.size
                                    displayed = page.tracks.size
                                    discs = page.tracks.mapNotNull { it.discNumber }.distinct().size
                                    positions = page.tracks.count { it.sourcePosition != null }
                                    durations = page.tracks.count { it.durationMs != null }
                                    projected = displayed > 0
                                    partial = record.nativeTracklistIncomplete
                                    typeVerified = record.formats.any { format ->
                                        if (case.kind == "single_ep")
                                            format.equals("Single", true) || format.equals("EP", true)
                                        else format.equals("Album", true)
                                    }
                                    val variants = if (!failed) sampleDiscogsVerifiedVersions(record,
                                        discogsDetails::fetchMasterVersionPreview, discogsDetails::fetchRelease)
                                        else DiscogsVerifiedVersionSample(incomplete = true)
                                    versionCoverageUncertain = variants.incomplete
                                    val artistId = record.artistIds.singleOrNull()
                                    val siblings = if (artistId != null && !failed) {
                                        assertEquals("discogs:artist:$artistId", page.albumArtistBrowseId)
                                        artistRoute = 1
                                        (discogsArtist.load(artistId, 1) as? DiscogsNativeArtistLoad.Success)
                                            ?.releases.orEmpty()
                                    } else emptyList()
                                    val enriched = discogsReleasePage(record.artistNames.single(), record,
                                        siblings, variants.records, variants.incomplete)
                                    versions = enriched.page.sections.singleOrNull { it.title == "Versões" }
                                        ?.items.orEmpty().size
                                    related = enriched.page.sections.filter { it.title.startsWith("Mais de ") }
                                        .sumOf { it.items.size }
                                    assertTrue(enriched.page.sections.flatMap { it.items }.all { item ->
                                        siblings.any { it.browseId == item.browseId && it.artistId == artistId } ||
                                            variants.records.any { "discogs:release:${it.link.id}" == item.browseId &&
                                                it.masterId == record.masterId && !it.unofficial }
                                    })
                                }
                            }
                        }
                        true
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    false
                }
                // Coverage/type/navigation are explicit separate dimensions.
                // A healthy page with unknown type does not prove #229 closed.
                val status = when {
                    provider == discogs && token == null -> "token_not_configured"
                    complete == null -> "timeout_or_partial"
                    projected && partial -> "partial_tracklist"
                    projected && case.kind == "multi" && discs < 2 -> "multidisc_not_observed"
                    projected && decoded == displayed && appleQualifiedEditionVerified -> "qualified_edition_detail_verified"
                    projected && decoded == displayed -> "detail_verified"
                    failed -> "source_interruption"
                    matched -> "projection_unavailable"
                    else -> "labelled_work_not_found"
                }
                report("album_matrix_" + sourceName + "_" + (index + 1).toString().padStart(2, '0'), Sample(
                    status = status, eligible = decoded, visible = displayed,
                    counts = mapOf(
                        "identity_verified" to if (matched) 1 else 0,
                        "detail_projected" to if (projected) 1 else 0,
                        "tracklist_partial" to if (partial) 1 else 0,
                        "type_verified" to if (typeVerified) 1 else 0,
                        "apple_header_type_verified" to if (appleHeaderTypeVerified) 1 else 0,
                        "apple_track_label_type_verified" to if (appleTrackLabelTypeVerified) 1 else 0,
                        "apple_audio_rule_album" to if (appleRuleCandidate == ItunesEditorialReleaseFormat.ALBUM) 1 else 0,
                        "apple_audio_rule_ep" to if (appleRuleCandidate == ItunesEditorialReleaseFormat.EP) 1 else 0,
                        "apple_audio_rule_single" to if (appleRuleCandidate == ItunesEditorialReleaseFormat.SINGLE) 1 else 0,
                        "apple_audio_rule_matches_expected" to if (appleRuleTypeMatches) 1 else 0,
                        "apple_audio_rule_explicit_conflict" to if (appleRuleExplicitConflict) 1 else 0,
                        "apple_commercial_type_matches_expected" to if (appleCommercialTypeMatches) 1 else 0,
                        "apple_display_type_matches_expected" to if (appleDisplayTypeMatches) 1 else 0,
                        "apple_display_type_known" to if (appleDisplayTypeKnown) 1 else 0,
                        "apple_selected_public_offer_verified" to if (appleSelectedPublicOfferVerified) 1 else 0,
                        "apple_independent_offer_display_known" to if (appleIndependentOfferDisplayKnown) 1 else 0,
                        "apple_independent_offer_display_matches_expected" to if (appleIndependentOfferDisplayMatches) 1 else 0,
                        "apple_commercial_lower_bound_album" to if (appleCommercialLowerBoundAlbum) 1 else 0,
                        "source_discs_observed" to discs,
                        "source_track_positions" to positions,
                        "source_track_durations" to durations,
                        "artist_route_verified" to artistRoute,
                        "related_release_cards" to related,
                        "version_cards" to versions,
                        "version_coverage_uncertain" to if (versionCoverageUncertain) 1 else 0,
                        "detail_candidates_read" to candidatesRead,
                        "native_search_gestures" to searchGestures,
                        "partial_apple_candidates" to partialAppleCandidates,
                        "discogs_format_queries" to discogsFormatQueries,
                        "apple_search_requests" to appleSearchRequests,
                        "apple_artist_collection_discovery_calls" to appleArtistCollectionDiscoveryCalls,
                        "apple_artist_collection_verified_candidates" to appleArtistCollectionVerifiedCandidates,
                        "apple_song_collection_discovery_calls" to appleSongCollectionDiscoveryCalls,
                        "apple_song_collection_verified_candidates" to appleSongCollectionVerifiedCandidates,
                        "apple_independent_offer_attempted" to if (appleIndependentOfferAttempted) 1 else 0,
                        "apple_independent_offer_verified" to if (appleIndependentOfferVerified) 1 else 0,
                        "apple_independent_detail_complete" to if (appleIndependentDetailComplete) 1 else 0,
                        "apple_independent_frozen_title_matches" to if (appleIndependentFrozenTitleMatches) 1 else 0,
                        "apple_related_year_edition_verified" to if (appleRelatedYearEditionVerified) 1 else 0,
                        "apple_related_year_edition_complete" to if (appleRelatedYearEditionComplete) 1 else 0,
                        "apple_mixed_lookup_attempted" to if (appleAlbumDiagnostics?.mixedLookupAttempted == true) 1 else 0,
                        "apple_mixed_lookup_verified" to if (appleAlbumDiagnostics?.mixedLookupVerified == true) 1 else 0,
                        "apple_album_song_search_attempted" to if (appleAlbumDiagnostics?.albumSongSearchAttempted == true) 1 else 0,
                        "apple_album_song_search_verified" to if (appleAlbumDiagnostics?.albumSongSearchVerified == true) 1 else 0,
                        "apple_alternative_track_route_verified" to if (appleAlternativeTrackRouteVerified) 1 else 0,
                        "apple_verified_excluded_non_audio" to (appleAlbumDiagnostics?.verifiedExcludedNonAudioRows ?: 0),
                        "apple_frozen_title_verified" to if (appleFrozenTitleVerified) 1 else 0,
                        "apple_qualified_edition_verified" to if (appleQualifiedEditionVerified) 1 else 0,
                        "apple_video_probe_attempted" to if (appleVideoProbeAttempted) 1 else 0,
                        "apple_video_probe_same_collection" to if (appleVideoProbeSameCollection) 1 else 0,
                        "apple_video_probe_verified_rows" to appleVideoProbeVerifiedRows,
                        "apple_video_probe_count_reconciled" to if (appleVideoProbeCountReconciled) 1 else 0,
                        "apple_seven_plus_audio_tracks" to if (appleSevenPlusAudioTracks) 1 else 0,
                        "apple_search_decoded_rows" to appleSearchRawRows,
                        "apple_search_credit_matches" to appleSearchCreditMatches,
                        "apple_search_title_matches" to appleSearchTitleMatches,
                        "apple_search_work_matches" to appleSearchWorkMatches,
                        "apple_search_work_rank_admitted" to appleSearchWorkRankAdmitted,
                        "apple_lookup_declared_track_count" to (appleAlbumDiagnostics?.declaredTrackCount ?: 0),
                        "apple_lookup_track_count_known" to if (appleAlbumDiagnostics?.declaredTrackCount != null) 1 else 0,
                        "apple_lookup_decoded_tracks" to (appleAlbumDiagnostics?.decodedTracks ?: 0),
                        "apple_lookup_positionless_tracks" to (appleAlbumDiagnostics?.positionlessTracks ?: 0),
                        "apple_lookup_duplicate_positions" to (appleAlbumDiagnostics?.duplicatePositions ?: 0),
                        "apple_lookup_possibly_truncated" to if (appleAlbumDiagnostics?.sourcePossiblyTruncated == true) 1 else 0,
                        "apple_lookup_declared_rows" to (appleAlbumDiagnostics?.lookup?.declaredResults ?: 0),
                        "apple_lookup_raw_rows" to (appleAlbumDiagnostics?.lookup?.rawRows ?: 0),
                        "apple_lookup_matching_songs" to (appleAlbumDiagnostics?.lookup?.matchingSongRows ?: 0),
                        "apple_lookup_matching_non_songs" to (appleAlbumDiagnostics?.lookup?.matchingNonSongRows ?: 0),
                        "apple_lookup_foreign_songs" to (appleAlbumDiagnostics?.lookup?.foreignSongRows ?: 0),
                        "apple_lookup_rejected_songs" to (appleAlbumDiagnostics?.lookup?.rejectedSongRows ?: 0),
                        "apple_lookup_removed_duplicate_songs" to (appleAlbumDiagnostics?.lookup?.removedDuplicateOrDisputedSongs ?: 0),
                        "source_interruption" to if (failed) 1 else 0,
                        "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                ))
            }
        }
    }

    private data class SearchParityCase(
        val query: String, val kind: String, val title: String,
        val artist: String? = null, val anchor: String? = null,
        val aliases: Set<String> = emptySet(), val artistAliases: Set<String> = emptySet(),
        val explicitDiscogsSuffix: Boolean = false,
    )

    private data class SearchParityRow(
        val id: String, val title: String, val artist: String? = null,
    )

    private fun searchLabelKey(text: String?): String =
        java.text.Normalizer.normalize(text.orEmpty().trim(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    /**
     * Reviewer input, not a relevance verdict. An exact title with another
     * artist can be relevant for a title-only query, whereas an explicit
     * artist/work query requires its credit. Do not use the production rank
     * function as its own judge. Retain the evidence for independent review.
     */
    private fun reviewLabel(value: String?): String =
        value.orEmpty().filterNot { it.isISOControl() ||
            Character.getType(it) == Character.FORMAT.toInt() }
            .replace(Regex("\\s+"), " ").trim()
            // Escape before truncating so the final public label is bounded.
            .replace("::", ": :").replace("://", ": / /").take(180)

    private fun reviewJsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    private fun reviewRowJson(
        ordinal: Int, row: SearchParityRow, targetJudgement: Int,
        qualifiedVerified: Boolean,
    ): String = buildString {
        append("{\"rank\":").append(ordinal)
        append(",\"title\":").append(reviewJsonString(reviewLabel(row.title)))
        append(",\"credit\":").append(reviewJsonString(reviewLabel(row.artist)))
        append(",\"target_label\":").append(targetJudgement)
        append(",\"qualified_verified\":").append(qualifiedVerified)
        append("}")
    }

    @Test fun relevanceReviewIsBoundedAndDoesNotPublishNativeIds() {
        val id = "itunes:br:album:123456789"
        val row = SearchParityRow(id, "Work\n::warning::\u202e" + "x".repeat(220), "Credit")
        val json = reviewRowJson(1, row, -1, false)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("rank", "title", "credit", "target_label", "qualified_verified"), obj.keys)
        assertFalse(json.contains(id))
        assertFalse(json.contains("::"))
        assertTrue(reviewLabel(row.title).length <= 180)
        assertEquals("A B", reviewLabel(" A  B "))
        assertEquals("A: / /B", reviewLabel("A://B"))
        assertTrue(reviewLabel("://".repeat(100)).length <= 180)
    }

    private fun SearchParityCase.titleMatches(text: String?): Boolean =
        text != null && (aliases + title).any { searchLabelKey(it) == searchLabelKey(text) }

    private fun SearchParityCase.artistMatches(text: String?): Boolean =
        text != null && (artistAliases + (artist ?: title)).any {
            searchLabelKey(it) == searchLabelKey(text)
        }

    private fun searchAlbumCase(index: Int, query: String): SearchParityCase {
        val work = albumParityCases[index]
        return SearchParityCase(query, "album", work.title, work.artist,
            aliases = work.titleAliases, artistAliases = work.artistAliases)
    }

    private val searchParityCases = listOf(
        SearchParityCase("ILLIT", "artist", "ILLIT", anchor = "SUPER REAL ME"),
        SearchParityCase("IVE", "artist", "IVE", anchor = "I'VE IVE"),
        SearchParityCase("Beyonce", "artist", "Beyoncé", anchor = "Lemonade"),
        SearchParityCase("Beatles", "artist", "The Beatles", anchor = "Abbey Road",
            artistAliases = setOf("Beatles, The")),
        SearchParityCase("Muse", "artist", "Muse", anchor = "Absolution"),
        SearchParityCase("Ghost", "artist", "Ghost", anchor = "Meliora"),
        SearchParityCase("Phoenix", "artist", "Phoenix", anchor = "Wolfgang Amadeus Phoenix"),
        SearchParityCase("Joao Gilberto", "artist", "João Gilberto", anchor = "Chega de Saudade"),
        SearchParityCase("Portishead", "artist", "Portishead", anchor = "Dummy"),
        SearchParityCase("ILLIT (2)", "artist", "ILLIT", anchor = "SUPER REAL ME",
            explicitDiscogsSuffix = true),
        searchAlbumCase(0, "David Bowie Hunky Dory"),
        searchAlbumCase(1, "Powerslave"),
        searchAlbumCase(2, "Violator Depeche Mode"),
        searchAlbumCase(3, "Dummy Portishead"),
        searchAlbumCase(4, "Mezzanine"),
        searchAlbumCase(5, "Blue Monday New Order"),
        searchAlbumCase(7, "The Perfect Kiss"),
        searchAlbumCase(9, "Love Will Tear Us Apart"),
        SearchParityCase("Magnetic ILLIT", "track", "Magnetic", "ILLIT", "SUPER REAL ME"),
        SearchParityCase("Hello", "track", "Hello", "Adele", "25"),
        SearchParityCase("One Metallica", "track", "One", "Metallica", "...And Justice for All"),
        SearchParityCase("Halo Beyonce", "track", "Halo", "Beyoncé", "I Am... Sasha Fierce"),
        SearchParityCase("1901 Phoenix", "track", "1901", "Phoenix", "Wolfgang Amadeus Phoenix"),
        SearchParityCase("Chega de Saudade Joao Gilberto", "track", "Chega de Saudade",
            "João Gilberto", "Chega de Saudade"),
        SearchParityCase("metal", "category", "metal"),
        SearchParityCase("Eletronica", "category", "electronic"),
        SearchParityCase("k-pop", "category", "k-pop"),
        SearchParityCase("house", "category", "house"),
        SearchParityCase("hard rock", "category", "hard rock"),
        SearchParityCase("smooth jazz", "category", "smooth jazz"),
    )

    /**
     * AMS native-adapter baseline on the EXACT same fixed IDM corpus.
     * No other provider client is constructed. Categories are the shared local
     * taxonomy, not a claim that Apple Web implements native genre search.
     * Rank is native adapter order, not yet the production UI projection.
     */
    /** Independent AMS editorial matrix, same 10 artist and 15 release cases.
     * This observes the real provider gateway, not yet UI projection.
     * No source identifiers or payloads are exported in the aggregate reports.
     */
    @Test fun live_ams_editorial_parity_matrix() = runBlocking {
        assumeTrue("Only the dedicated AMS editorial workflow may access remote services",
            System.getenv("AMS_EDITORIAL_LIVE") == "1")
        val scope = System.getenv("AMS_EDITORIAL_SCOPE") ?: error("Missing editorial scope")
        require(scope in setOf("artist", "album"))
        val source = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val gate = gateFor(source, setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY))
        val catalog = AppleMusicWebCatalog(AppleMusicWebPolicyTransport(admission = RateLimitedProviderTransport { gate }))
        val gateway = AppleMusicWebEditorialGateway(catalog, gate)
        val reader = CatalogSearchAdapterReader(gate, listOf(AppleMusicWebSearchAdapter(gateway)))
        val sf = "br"
        suspend fun search(kind: CatalogSearchKind, query: String): List<CatalogSearchItem>? =
            withTimeoutOrNull(60_000L) {
                (reader.read(source, CatalogSearchRequest(kind, query, sf, limit = 24))
                    as? ProviderCallResult.Success)?.value?.items
            }
        suspend fun album(id: String): AppleMusicWebOutcome<AppleMusicWebAlbum>? =
            withTimeoutOrNull(90_000L) {
                (gateway.album(id, sf) as? ProviderCallResult.Success)?.value?.outcome
            }
        fun <T> value(outcome: AppleMusicWebOutcome<T>?): T? = when (outcome) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial -> outcome.value
            else -> null
        }
        fun workMatches(actual: String, expected: String): Boolean =
            searchLabelKey(actual) == searchLabelKey(expected) ||
                applePublicAlbumWorkKey(actual) == searchLabelKey(expected)

        var failed = 0
        if (scope == "artist") {
            assertEquals(10, artistParityCases.size)
            for ((index, case) in artistParityCases.withIndex()) {
                val start = System.nanoTime()
                val artists = search(CatalogSearchKind.ARTIST, case.name)
                val anchors = search(CatalogSearchKind.ALBUM, case.name + " " + case.anchor)
                val artistIds = artists.orEmpty().filter {
                    artistParityFold(it.title) == artistParityFold(case.name)
                }.map { it.identity.nativeId }.toSet()
                val proven = linkedSetOf<String>()
                var evidenceReads = 0
                for (candidate in anchors.orEmpty().filter {
                    workMatches(it.title, case.anchor) && artistParityFold(it.subtitle) == artistParityFold(case.name)
                }.take(3)) {
                    evidenceReads++
                    val record = value(album(candidate.identity.nativeId)) ?: continue
                    if (!workMatches(record.identity.title, case.anchor) ||
                        artistParityFold(record.identity.artistName) != artistParityFold(case.name)) continue
                    proven += record.artists.filter {
                        it.id in artistIds && artistParityFold(it.title) == artistParityFold(case.name)
                    }.map { it.id }
                }
                val artistId = proven.singleOrNull()
                val outcome = artistId?.let { id ->
                    withTimeoutOrNull(90_000L) {
                        (gateway.artist(id, sf) as? ProviderCallResult.Success)?.value?.outcome
                    }
                }
                val artist = value(outcome)
                val verified = artist != null && artist.identity.id == artistId &&
                    artistParityFold(artist.identity.title) == artistParityFold(case.name)
                val complete = verified && outcome is AppleMusicWebOutcome.Complete
                val items = if (artist != null) artist.albums + artist.singlesAndEps +
                    artist.featuredAlbums + artist.topSongs + artist.similarArtists + artist.artistPlaylists
                    else emptyList()
                var pagingChecked = 0
                var pagingOverlap = 0
                var pagingIncomplete = 0
                var nextRows = 0
                if (verified && case.extensive && artistId != null) {
                    suspend fun shelf(page: Int): AppleMusicWebOutcome<AppleMusicWebPage>? =
                        withTimeoutOrNull(60_000L) {
                            (gateway.artistCollection(artistId, sf, AppleMusicWebArtistShelf.STUDIO_ALBUMS,
                                page, 10) as? ProviderCallResult.Success)?.value?.outcome
                        }
                    val first = shelf(1)
                    val firstPage = value(first)
                    if (first !is AppleMusicWebOutcome.Complete) pagingIncomplete++
                    if (firstPage?.hasMore == true) {
                        pagingChecked++
                        val second = shelf(2)
                        val secondPage = value(second)
                        if (second !is AppleMusicWebOutcome.Complete || secondPage == null ||
                            (secondPage.items.isEmpty() && secondPage.hasMore)) pagingIncomplete++
                        nextRows = secondPage?.items?.size ?: 0
                        pagingOverlap = firstPage.items.map { it.scopedId }.toSet()
                            .intersect(secondPage?.items.orEmpty().map { it.scopedId }.toSet()).size
                    }
                }
                val status = when {
                    artists == null || anchors == null -> "search_interruption"
                    proven.size > 1 -> "ambiguous_identity"
                    artistId == null -> "identity_not_corroborated"
                    !verified -> "detail_unavailable"
                    !complete -> "partial_detail"
                    pagingIncomplete > 0 || pagingOverlap > 0 -> "pagination_incomplete"
                    else -> "complete"
                }
                if (status != "complete") failed++
                report("ams_artist_" + (index + 1).toString().padStart(2, '0'), Sample(
                    status = status, eligible = if (verified) 1 else 0,
                    visible = items.distinctBy { it.scopedId }.size,
                    artwork = items.distinctBy { it.scopedId }.count { it.artworkUrl != null },
                    counts = mapOf(
                        "identity_verified" to if (verified) 1 else 0,
                        "detail_complete" to if (complete) 1 else 0,
                        "identity_evidence_reads" to evidenceReads,
                        "top_songs" to (artist?.topSongs?.size ?: 0),
                        "studio_albums" to (artist?.albums?.size ?: 0),
                        "singles_and_eps" to (artist?.singlesAndEps?.size ?: 0),
                        "featured_albums" to (artist?.featuredAlbums?.size ?: 0),
                        "similar_artists" to (artist?.similarArtists?.size ?: 0),
                        "playlists" to (artist?.artistPlaylists?.size ?: 0),
                        "unresolved_references" to (artist?.unresolvedShelves?.values?.sum() ?: 0),
                        "paging_checked" to pagingChecked,
                        "paging_overlap" to pagingOverlap,
                        "paging_incomplete" to pagingIncomplete,
                        "next_page_rows" to nextRows,
                        "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    )
                ))
            }
        } else {
            assertEquals(15, albumParityCases.size)
            for ((index, case) in albumParityCases.withIndex()) {
                val start = System.nanoTime()
                val discovery = AppleMusicWebReleaseDiscovery
                var selected: AppleMusicWebOutcome<AppleMusicWebAlbum>? = null
                var reads = 0
                var searchReads = 0
                var searchFailures = 0
                var shelfReads = 0
                var shelfFailures = 0
                var discoveredRows = 0
                var candidateRows = 0
                var songLinkReads = 0
                var songLinkFailures = 0
                var rejectedDetail = 0
                var rejectedCredit = 0
                var rejectedTitle = 0
                var rejectedArtistLink = 0
                val checked = mutableSetOf<String>()
                fun multi(record: AppleMusicWebAlbum): Boolean =
                    record.tracks.filter { it.kind == AppleMusicWebKind.SONG }
                        .mapNotNull { it.discNumber }.distinct().size > 1
                fun preferred(): Boolean {
                    val record = value(selected) ?: return false
                    return selected is AppleMusicWebOutcome.Complete && (case.kind != "multi" || multi(record))
                }
                fun matchesCredit(credit: String?): Boolean =
                    discovery.creditMatches(credit, case.artist, case.artistAliases)
                fun candidateTitle(title: String): Boolean =
                    discovery.candidateTitle(title, case.title, case.titleAliases, case.kind == "single_ep")
                fun verifiedTitle(record: AppleMusicWebAlbum): Boolean {
                    if (discovery.titleMatches(record.identity.title, case.title, case.titleAliases)) return true
                    // A year-qualified single is only accepted with a complete
                    // audio relationship proving the named work and its credit.
                    return case.kind == "single_ep" &&
                        discovery.relatedSingleYear(record.identity.title, case.title) &&
                        !record.possiblyTruncated && record.tracks.any {
                            it.kind == AppleMusicWebKind.SONG &&
                                discovery.songWorkMatches(it.title, case.title) && matchesCredit(it.artistName)
                        }
                }
                suspend fun consider(id: String, expectedArtistId: String? = null) {
                    if (reads >= 10 || !checked.add(id)) return
                    reads++
                    val outcome = album(id)
                    val record = value(outcome)
                    if (record == null || record.identity.id != id) { rejectedDetail++; return }
                    if (!matchesCredit(record.identity.artistName)) { rejectedCredit++; return }
                    if (!verifiedTitle(record)) { rejectedTitle++; return }
                    if (!record.artists.any {
                            matchesCredit(it.title) && (expectedArtistId == null || it.id == expectedArtistId)
                        }) { rejectedArtistLink++; return }
                    val previous = value(selected)
                    if (previous == null ||
                        (outcome is AppleMusicWebOutcome.Complete && selected !is AppleMusicWebOutcome.Complete) ||
                        (outcome is AppleMusicWebOutcome.Complete && case.kind == "multi" && multi(record) && !multi(previous)))
                        selected = outcome
                }
                // First preserve the existing compound route. Only unsuccessful
                // or non-preferred discovery opens bounded alternate routes.
                for (query in discovery.alternateQueries(case.artist, case.title, case.titleAliases)) {
                    if (preferred() || reads >= 10) break
                    searchReads++
                    val found = search(CatalogSearchKind.ALBUM, query)
                    if (found == null) { searchFailures++; continue }
                    discoveredRows += found.size
                    for (candidate in found.filter { matchesCredit(it.subtitle) && candidateTitle(it.title) }) {
                        candidateRows++
                        consider(candidate.identity.nativeId)
                        if (preferred() || reads >= 10) break
                    }
                }
                // If search never exposes the release, inspect the native
                // shelves of matching artist IDs, then verify the same ID is
                // present in the exact album's artist relationship.
                if (!preferred() && reads < 10) {
                    searchReads++
                    val artists = search(CatalogSearchKind.ARTIST, case.artist)
                    if (artists == null) searchFailures++
                    val shelves = when (case.kind) {
                        "single_ep" -> listOf(AppleMusicWebArtistShelf.SINGLES_AND_EPS, AppleMusicWebArtistShelf.ALL_ALBUMS)
                        else -> listOf(AppleMusicWebArtistShelf.STUDIO_ALBUMS, AppleMusicWebArtistShelf.ALL_ALBUMS)
                    }
                    for (artist in artists.orEmpty().filter { matchesCredit(it.title) }.take(2)) {
                        if (preferred() || reads >= 10) break
                        for (shelf in shelves) {
                            if (preferred() || reads >= 10) break
                            for (page in 1..3) {
                                shelfReads++
                                val result = withTimeoutOrNull(60_000L) {
                                    (gateway.artistCollection(artist.identity.nativeId, sf, shelf, page, 25)
                                        as? ProviderCallResult.Success)?.value?.outcome
                                }
                                if (result !is AppleMusicWebOutcome.Complete) shelfFailures++
                                val collection = value(result) ?: break
                                discoveredRows += collection.items.size
                                for (release in collection.items.filter {
                                    candidateTitle(it.title) && (it.artistName == null || matchesCredit(it.artistName))
                                }) {
                                    candidateRows++
                                    consider(release.id, artist.identity.nativeId)
                                    if (preferred() || reads >= 10) break
                                }
                                if (preferred() || reads >= 10 || !collection.hasMore) break
                            }
                        }
                    }
                }
                // A source-owned song can expose a release that album search
                // omits. It nominates only native relationship IDs, not an
                // inferred album ID, title, or artist ownership.
                if (!preferred() && reads < 10 && case.kind == "single_ep") {
                    searchReads++
                    val tracks = search(CatalogSearchKind.TRACK, case.title + " " + case.artist)
                    if (tracks == null) searchFailures++
                    for (track in tracks.orEmpty().filter {
                        matchesCredit(it.subtitle) && discovery.songWorkMatches(it.title, case.title)
                    }.take(5)) {
                        if (preferred() || reads >= 10) break
                        songLinkReads++
                        val linked = withTimeoutOrNull(60_000L) {
                            (gateway.songCollections(track.identity.nativeId, sf)
                                as? ProviderCallResult.Success)?.value?.outcome
                        }
                        val collection = value(linked)
                        if (collection == null) { songLinkFailures++; continue }
                        if (!matchesCredit(collection.song.artistName) ||
                            !discovery.songWorkMatches(collection.song.title, case.title)) continue
                        for (id in collection.albumIds.take(4)) {
                            consider(id)
                            if (preferred() || reads >= 10) break
                        }
                    }
                }
                val record = value(selected)
                val songs = record?.tracks.orEmpty().filter { it.kind == AppleMusicWebKind.SONG }
                val complete = selected is AppleMusicWebOutcome.Complete
                val positions = songs.mapNotNull { track ->
                    track.discNumber?.let { disc -> track.trackNumber?.let { disc to it } }
                }
                val status = when {
                    record == null && searchFailures == searchReads -> "search_interruption"
                    record == null -> "identity_not_corroborated"
                    !complete -> "partial_detail"
                    songs.isEmpty() -> "no_audio_metadata"
                    case.kind == "multi" && !multi(record) -> "multidisc_not_corroborated"
                    positions.size != songs.size || positions.distinct().size != positions.size -> "track_positions_incomplete"
                    songs.any { it.durationMillis == null } -> "track_durations_incomplete"
                    else -> "complete"
                }
                if (status != "complete") failed++
                report("ams_album_" + (index + 1).toString().padStart(2, '0'), Sample(
                    status = status, eligible = if (record != null) 1 else 0, visible = songs.size,
                    artwork = if (record?.identity?.artworkUrl != null) 1 else 0,
                    counts = mapOf(
                        "identity_verified" to if (record != null) 1 else 0,
                        "detail_complete" to if (complete) 1 else 0,
                        "identity_evidence_reads" to reads,
                        "discovery_search_reads" to searchReads,
                        "discovery_search_failures" to searchFailures,
                        "discovery_shelf_reads" to shelfReads,
                        "discovery_shelf_failures" to shelfFailures,
                        "discovery_rows" to discoveredRows,
                        "discovery_candidate_rows" to candidateRows,
                        "discovery_song_link_reads" to songLinkReads,
                        "discovery_song_link_failures" to songLinkFailures,
                        "rejected_detail" to rejectedDetail,
                        "rejected_credit" to rejectedCredit,
                        "rejected_title" to rejectedTitle,
                        "rejected_artist_link" to rejectedArtistLink,
                        "source_track_rows" to (record?.tracks?.size ?: 0),
                        "audio_metadata_rows" to songs.size,
                        "non_audio_rows" to ((record?.tracks?.size ?: 0) - songs.size),
                        "durations_present" to songs.count { it.durationMillis != null },
                        "positions_present" to positions.size,
                        "duplicate_positions" to (positions.size - positions.distinct().size),
                        "distinct_discs" to songs.mapNotNull { it.discNumber }.distinct().size,
                        "multidisc_expected" to if (case.kind == "multi") 1 else 0,
                        "multidisc_observed" to if (record != null && multi(record)) 1 else 0,
                        "is_single_present" to if (record?.identity?.isSingle != null) 1 else 0,
                        "is_single_true" to if (record?.identity?.isSingle == true) 1 else 0,
                        "release_date_present" to if (record?.identity?.releaseDate != null) 1 else 0,
                        "source_track_count_present" to if (record?.identity?.trackCount != null) 1 else 0,
                        "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    )
                ))
            }
        }
        assertEquals("Incomplete AMS editorial cells in " + scope, 0, failed)
    }

    @Test fun live_ams_search_parity_matrix() = runBlocking {
        assumeTrue("Enable only via the dedicated AMS search workflow",
            System.getenv("AMS_SEARCH_LIVE") == "1")
        val source = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val capabilities = setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val gate = gateFor(source, capabilities)
        val catalog = AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        ))
        val gateway = AppleMusicWebEditorialGateway(catalog, gate)
        val reader = CatalogSearchAdapterReader(gate, listOf(AppleMusicWebSearchAdapter(gateway)))
        val sf = "br"
        assertEquals(30, searchParityCases.size)
        fun nativeId(item: CatalogSearchItem): String =
            "apple_music_web:" + item.identity.storefront + ":" +
                item.identity.kind.name.lowercase(java.util.Locale.ROOT) + ":" + item.identity.nativeId

        suspend fun read(kind: CatalogSearchKind, query: String, page: Int = 1,
                         continuation: CatalogSearchContinuation? = null): CatalogSearchPage? =
            withTimeoutOrNull(60_000L) {
                when (val result = reader.read(source, CatalogSearchRequest(kind, query, sf, page, 24, continuation))) {
                    is ProviderCallResult.Success -> result.value.takeUnless { it.partial }
                    is ProviderCallResult.Failed -> {
                        val cause = result.cause
                        val reason = when (cause) {
                            is AppleMusicWebHttpException -> "http_" + cause.status
                            is AppleMusicWebProtocolException -> when (cause.message) {
                                "Missing search results envelope" -> "missing_results_envelope"
                                "Missing requested search result type" -> "missing_requested_type"
                                "Invalid search data" -> "invalid_search_data"
                                "Search error envelope" -> "search_error_envelope"
                                "Cyclic cursor" -> "cyclic_cursor"
                                else -> "protocol"
                            }
                            is java.io.IOException -> "io"
                            else -> "other"
                        }
                        println("AMS_SEARCH_READ_FAILURE|kind=" + kind.name + "|page=" + page + "|reason=" + reason)
                        null
                    }
                    ProviderCallResult.Denied, ProviderCallResult.Stale -> null
                }
            }

        val dir = File(System.getenv("IDM_CATALOG_METRICS_DIR") ?: "build/ams-search-metrics", "top10-review")
        check(dir.exists() || dir.mkdirs())
        var interrupted = 0
        var paginationProblems = 0
        for ((index, case) in searchParityCases.withIndex()) {
            val start = System.nanoTime()
            var rows = emptyList<SearchParityRow>()
            var nativeRows = emptyList<SearchParityRow>()
            val provenIds = mutableSetOf<String>()
            val qualifiedIds = mutableSetOf<String>()
            var evidenceReads = 0
            var complete = true
            var categoryRoute = false
            var proofComplete = true
            var offered = false
            var secondPageChecked = false
            var secondPageRows = 0
            var overlap = 0
            var rawOverlap = 0
            var continuationFailed = false
            if (case.kind == "category") {
                val categories = canonicalExploreGenreSearchHits(case.query, limit = 10)
                assertTrue(categories.all {
                    it.subtitle == "Categoria" && it.browseId.orEmpty().startsWith("catalog:GENRE:")
                })
                rows = categories.mapNotNull { item ->
                    item.browseId?.let { SearchParityRow(it, item.title) }
                }
                categoryRoute = rows.any { it.id == "catalog:GENRE:" + case.title }
                if (categoryRoute) provenIds += "catalog:GENRE:" + case.title
                offered = categoryRoute
            } else {
                val kind = when (case.kind) {
                    "artist" -> CatalogSearchKind.ARTIST
                    "album" -> CatalogSearchKind.ALBUM
                    "track" -> CatalogSearchKind.TRACK
                    else -> error("Unexpected search category")
                }
                val first = read(kind, case.query)
                complete = first != null
                if (first != null) {
                    assertEquals(source, first.provider)
                    assertEquals(sf, first.storefront)
                    rows = first.items.map { SearchParityRow(nativeId(it), it.title, it.subtitle) }
                    nativeRows = first.items.sortedBy { it.sourcePosition ?: Int.MAX_VALUE }
                        .map { SearchParityRow(nativeId(it), it.title, it.subtitle) }
                    // Observe original landing and ranked landing independently.
                    // Continuations never repair or inflate either Top5/Top10 result.
                    if (first.hasMore) {
                        secondPageChecked = true
                        val next = first.continuation?.let { read(kind, case.query, 2, it) }
                        if (next == null || (next.items.isEmpty() && next.hasMore)) {
                            continuationFailed = true
                        } else {
                            secondPageRows = next.items.size
                            rawOverlap = next.sourceOverlapCount
                            overlap = next.items.map(::nativeId).toSet().intersect(rows.map { it.id }.toSet()).size
                            if (overlap > 0) println("AMS_SEARCH_PAGE_OVERLAP|case=" + (index + 1) + "|count=" + overlap)
                        }
                    }
                    if (kind == CatalogSearchKind.ARTIST) {
                        // Independent same-source proof: exact anchor release and its
                        // explicit artist relationship, never a name-only identity.
                        val anchor = read(CatalogSearchKind.ALBUM, case.title + " " + case.anchor.orEmpty())
                        proofComplete = anchor != null
                        fun anchorTitle(title: String): Boolean =
                            applePublicAlbumWorkKey(title) == searchLabelKey(case.anchor)
                        val candidates = anchor?.items.orEmpty().filter {
                            anchorTitle(it.title) && case.artistMatches(it.subtitle)
                        }.take(3)
                        val checkedAlbums = mutableSetOf<String>()
                        suspend fun proveAnchor(albumId: String) {
                            if (!checkedAlbums.add(albumId)) return
                            evidenceReads++
                            val response = withTimeoutOrNull(60_000L) {
                                gateway.album(albumId, sf)
                            } as? ProviderCallResult.Success
                            // Artist relationship evidence is independent of
                            // tracklist completeness; only exact returned
                            // album/artist IDs and credit may be credited.
                            val album = when (val outcome = response?.value?.outcome) {
                                is AppleMusicWebOutcome.Complete -> outcome.value
                                is AppleMusicWebOutcome.Partial -> outcome.value
                                else -> null
                            }
                            if (album == null) {
                                proofComplete = false
                                return
                            }
                            if (!anchorTitle(album.identity.title) ||
                                !case.artistMatches(album.identity.artistName)) return
                            val ids = album.artists.filter { case.artistMatches(it.title) }.map { it.scopedId }
                            if (searchLabelKey(album.identity.title) == searchLabelKey(case.anchor)) provenIds += ids
                            else qualifiedIds += ids
                        }
                        for (candidate in candidates) proveAnchor(candidate.identity.nativeId)
                        // Same-source, exact-ID discography probe for visible
                        // homonyms when search did not provide the anchor.
                        if (provenIds.isEmpty() && qualifiedIds.isEmpty()) {
                            for (candidate in first.items.take(10).filter { case.artistMatches(it.title) }.take(3)) {
                                evidenceReads++
                                val response = withTimeoutOrNull(60_000L) {
                                    gateway.artist(candidate.identity.nativeId, sf)
                                } as? ProviderCallResult.Success
                                val artist = when (val outcome = response?.value?.outcome) {
                                    is AppleMusicWebOutcome.Complete -> outcome.value
                                    is AppleMusicWebOutcome.Partial -> outcome.value
                                    else -> null
                                }
                                if (artist == null) {
                                    proofComplete = false
                                    continue
                                }
                                val releases = (artist.albums + artist.singlesAndEps + artist.featuredAlbums)
                                    .distinctBy { it.scopedId }
                                for (release in releases.filter { anchorTitle(it.title) }.take(2)) {
                                    proveAnchor(release.id)
                                }
                            }
                        }
                        offered = provenIds.isNotEmpty() || qualifiedIds.isNotEmpty()
                    } else {
                        offered = rows.any { case.titleMatches(it.title) && case.artistMatches(it.artist) }
                        if (!offered) {
                            val probe = read(kind, case.title + " " + case.artist.orEmpty())
                            proofComplete = probe != null
                            offered = probe?.items.orEmpty().any {
                                case.titleMatches(it.title) && case.artistMatches(it.subtitle)
                            }
                        }
                        if (kind == CatalogSearchKind.ALBUM) {
                            // Confirm recognized edition titles by their exact
                            // native collection detail, not by ranker's score.
                            for (candidate in first.items.take(10).filter {
                                !case.titleMatches(it.title) && case.artistMatches(it.subtitle) &&
                                    applePublicAlbumWorkKey(it.title) == searchLabelKey(case.title)
                            }.take(3)) {
                                evidenceReads++
                                val response = withTimeoutOrNull(60_000L) {
                                    gateway.album(candidate.identity.nativeId, sf)
                                } as? ProviderCallResult.Success
                                val album = (response?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
                                if (album == null) {
                                    proofComplete = false
                                    continue
                                }
                                if (album.identity.id == candidate.identity.nativeId &&
                                    searchLabelKey(album.identity.title) == searchLabelKey(candidate.title) &&
                                    case.artistMatches(album.identity.artistName) &&
                                    album.artists.any { case.artistMatches(it.title) } &&
                                    album.tracks.isNotEmpty()) {
                                    qualifiedIds += nativeId(candidate)
                                }
                            }
                        }
                    }
                } else {
                    proofComplete = false
                }
            }
            assertTrue(rows.all { it.title.isNotBlank() })
            assertEquals(rows.size, rows.map { it.id }.distinct().size)
            fun label(row: SearchParityRow): Int =
                when {
                    case.kind == "category" -> if (row.id in provenIds) 1 else -1
                    case.kind == "artist" -> if (!case.artistMatches(row.title)) 0
                        else if (row.id in provenIds) 1 else -1
                    !case.titleMatches(row.title) -> 0
                    !case.artistMatches(row.artist) -> -1
                    else -> 1
                }
            val labels = rows.take(10).map(::label)
            val nativeLabels = (if (case.kind == "category") rows else nativeRows).take(10).map(::label)
            val topFive = labels.take(5).any { it == 1 }
            val topTen = labels.any { it == 1 }
            val qualifiedTopFive = rows.take(5).any { it.id in qualifiedIds }
            val qualifiedTopTen = rows.take(10).any { it.id in qualifiedIds }
            val caseNumber = (index + 1).toString().padStart(2, '0')
            val review = rows.take(10).mapIndexed { rank, row ->
                reviewRowJson(rank + 1, row, labels[rank], row.id in qualifiedIds)
            }
            val nativeDir = File(dir.parentFile, "native-order-review")
            check(nativeDir.exists() || nativeDir.mkdirs())
            val rawReview = (if (case.kind == "category") rows else nativeRows).take(10).mapIndexed { rank, row ->
                reviewRowJson(rank + 1, row, nativeLabels[rank], row.id in qualifiedIds)
            }
            File(nativeDir, "apple_music_web_" + caseNumber + ".json").writeText(
                "{\"schema\":1,\"provider\":\"apple_music_web\",\"case\":" + (index + 1) +
                    ",\"kind\":" + reviewJsonString(case.kind) + ",\"complete\":" + complete +
                    ",\"rows\":[" + rawReview.joinToString(",") + "]}"
            )
            File(dir, "apple_music_web_" + caseNumber + ".json").writeText(
                "{\"schema\":1,\"provider\":\"apple_music_web\",\"case\":" + (index + 1) +
                    ",\"kind\":" + reviewJsonString(case.kind) + ",\"complete\":" + complete +
                    ",\"rows\":[" + review.joinToString(",") + "]}"
            )
            report("search_matrix_apple_music_web_" + caseNumber, Sample(
                status = when {
                    case.kind == "category" -> if (categoryRoute) "local_category_verified" else "local_category_missing"
                    !complete -> "source_interruption"
                    topFive -> "target_in_top_five"
                    topTen -> "target_below_top_five"
                    offered -> "target_proven_not_visible"
                    else -> "target_not_corroborated"
                },
                eligible = labels.count { it == 1 }, visible = rows.size,
                counts = mapOf(
                    "target_proven" to if (offered || topTen) 1 else 0,
                    "top_five_target" to if (topFive) 1 else 0,
                    "top_ten_target" to if (topTen) 1 else 0,
                    "qualified_top_five" to if (qualifiedTopFive) 1 else 0,
                    "qualified_top_ten" to if (qualifiedTopTen) 1 else 0,
                    "strict_or_qualified_top_five" to if (topFive || qualifiedTopFive) 1 else 0,
                    "strict_or_qualified_top_ten" to if (topTen || qualifiedTopTen) 1 else 0,
                    "identity_evidence_reads" to evidenceReads,
                    "native_top_five_target" to if (nativeLabels.take(5).any { it == 1 }) 1 else 0,
                    "native_top_ten_target" to if (nativeLabels.any { it == 1 }) 1 else 0,
                    "native_first_ten_rows" to nativeLabels.size,
                    "first_ten_rows" to labels.size,
                    "labelled_target_rows" to labels.count { it == 1 },
                    "labelled_non_target_rows" to labels.count { it == 0 },
                    "unjudged_rows" to labels.count { it == -1 },
                    "local_category_route_verified" to if (categoryRoute) 1 else 0,
                    "source_interruption" to if (!complete) 1 else 0,
                    "proof_interruption" to if (!proofComplete) 1 else 0,
                    "second_page_checked" to if (secondPageChecked) 1 else 0,
                    "second_page_rows" to secondPageRows,
                    "second_page_overlap" to overlap,
                    "second_page_source_overlap" to rawOverlap,
                    "continuation_interruption" to if (continuationFailed) 1 else 0,
                    "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                ),
            ))
            if (!complete) interrupted++
            if (continuationFailed || overlap > 0) paginationProblems++
        }
        // Retain all case reports even when the native source contract fails.
        assertEquals("Native landing interruptions", 0, interrupted)
        assertEquals("Native continuation interruptions or repeated IDs", 0, paginationProblems)
    }

    /**
     * #230 prelabelled targets, not a synthetic popularity ranking. Target
     * precision is a stricter and different metric from general relevance:
     * same-title namesakes and unresolved artist credits remain unjudged.
     * Independent offer probes stay in the same provider. For Discogs tracks
     * the release path is separate and NEVER credited as a native track hit.
     */
    @Test fun live_search_parity_matrix() = runBlocking {
        assumeTrue("Only the observational CI job may access remote services",
            System.getenv("IDM_CATALOG_LIVE") == "1")
        val caps = setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val mbGate = gateFor(mb, caps)
        val mbSearch = MusicBrainzCatalogSearch(MusicBrainzClient(admission = { mbGate }))
        val appleGate = gateFor(apple, caps)
        val appleClient = ItunesCatalogClient(transport = KtorItunesCatalogTransport(
            admission = RateLimitedProviderTransport { appleGate },
        ))
        val appleRepo = AppleEditorialCatalogRepository(appleClient)
        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")?.trim()?.takeIf(String::isNotBlank)
        val discogsGate = gateFor(discogs, caps)
        val guard = RateLimitedProviderTransport { discogsGate }
        val discogsArtists = DiscogsNativeArtistSearchClient(
            enabled = token != null, tokenProvider = { token }, admission = guard,
        )
        val discogsReleases = DiscogsNativeReleaseSearchClient(
            enabled = token != null, tokenProvider = { token }, admission = guard,
        )
        val discogsDetails = DiscogsClient(
            enabled = token != null, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            admission = guard, tokenProvider = { token },
        )

        suspend fun anchorRecords(case: SearchParityCase): List<DiscogsRecord> {
            val title = case.anchor ?: case.title
            val artist = case.artist ?: case.title
            val search = discogsReleases.search("$artist $title")
                as? DiscogsNativeReleaseSearchResult.Success ?: return emptyList()
            val records = mutableListOf<DiscogsRecord>()
            for (candidate in search.page.releases.filter {
                searchLabelKey(it.title).contains(searchLabelKey(title))
            }.take(5)) {
                val response = discogsDetails.fetchRelease(candidate.releaseId)
                if (response is DiscogsLookupResult.Throttled ||
                    response is DiscogsLookupResult.Unauthorized ||
                    response is DiscogsLookupResult.Disabled) break
                val record = (response as? DiscogsLookupResult.Found)?.record ?: continue
                assertTrue(record.link.id == candidate.releaseId)
                val credit = record.artistNames.singleOrNull()?.let {
                    com.music.bitchord.ui.screens.discogsArtistDisplayName(it)
                }
                if (!record.unofficial && case.artistMatches(credit) &&
                    searchLabelKey(record.title) == searchLabelKey(title)) records += record
            }
            return records
        }

        val providers = when (System.getenv("IDM_CATALOG_LIVE_SCOPE") ?: "full") {
            "full" -> listOf(mb, apple, discogs)
            "itunes-musicbrainz-search" -> listOf(mb, apple)
            else -> error("Unsupported IDM live search scope")
        }
        for (provider in providers) {
            val sourceName = when (provider) { mb -> "musicbrainz"; apple -> "itunes"; else -> "discogs" }
            for ((index, case) in searchParityCases.withIndex()) {
                val start = System.nanoTime()
                var rows: List<SearchParityRow> = emptyList()
                val provenIds = mutableSetOf<String>()
                var offered = false
                var failed = false
                var categoryRoute = false
                var releasePath = false
                var homonymCandidates = 0
                var homonymDetailReads = 0
                var homonymCorroborated = false
                var appleQualifiedTitleTopFive = false
                var appleQualifiedAnchorTopFive = false
                var appleContinuationGestures = 0
                var appleContinuationTargetRecovered = false
                var appleContinuationQualifiedRecovered = false
                var appleContinuationInterrupted = false
                var appleLandingDiscoveredTargetRank = 0
                var appleLandingDiscoveredQualifiedRank = 0
                var appleQualifiedIdentityVerified = false
                val reviewVerifiedQualifiedIds = mutableSetOf<String>()
                var appleQualifiedIdentityReads = 0
                var mbAnchorFieldProbe = false
                var mbAnchorArtistIdReads = 0
                var mbLandingRawTargetRank = 0
                var mbLandingCuratedTargetRank = 0
                val supported = provider != discogs || case.kind != "track"
                val complete = try {
                    withTimeoutOrNull(100_000L) {
                        when {
                            case.kind == "category" -> {
                                val categories = canonicalExploreGenreSearchHits(case.query, limit = 10)
                                assertTrue(categories.all {
                                    it.subtitle == "Categoria" &&
                                        it.browseId.orEmpty().startsWith("catalog:GENRE:")
                                })
                                rows = categories.mapNotNull { item ->
                                    item.browseId?.let { SearchParityRow(it, item.title) }
                                }
                                categoryRoute = rows.any { it.id == "catalog:GENRE:${case.title}" }
                                if (categoryRoute) provenIds += "catalog:GENRE:${case.title}"
                            }
                            provider == mb -> when (case.kind) {
                                "artist" -> {
                                    val response = mbSearch.searchArtists(case.query, 15)
                                        as? MusicBrainzCatalogSearchResult.Success
                                    if (response == null) failed = true
                                    val rawArtists = response?.page?.items.orEmpty()
                                    val displayArtists = publicMusicBrainzArtistRows(case.query, rawArtists)
                                    rows = displayArtists.map {
                                        SearchParityRow("catalog:ARTIST:${it.musicBrainzId}", it.name)
                                    }
                                    val anchors = mbSearch.searchReleaseGroups(case.title + " " + case.anchor, 50)
                                        as? MusicBrainzCatalogSearchResult.Success
                                    fun matchingCredits(hits: List<MusicBrainzReleaseGroupHit>) =
                                        hits.filter {
                                            searchLabelKey(it.title) == searchLabelKey(case.anchor) &&
                                                case.artistMatches(it.artistDisplay)
                                        }.flatMap { it.artistCreditMbids.orEmpty() }
                                    var credits = matchingCredits(anchors?.page?.items.orEmpty())
                                    if (credits.isEmpty() && !case.anchor.isNullOrBlank()) {
                                        mbAnchorFieldProbe = true
                                        val exact = mbSearch.searchReleaseGroupsForTitleArtist(
                                            case.anchor, case.title,
                                        ) as? MusicBrainzCatalogSearchResult.Success
                                        credits = matchingCredits(exact?.page?.items.orEmpty())
                                    }
                                    if (credits.isEmpty() && !case.anchor.isNullOrBlank()) {
                                        // Last independent evidence route: search the anchor
                                        // by native MBID and literal title, rather than hoping
                                        // the work occurs in the first general discography page.
                                        // At most the five visible literal namesakes; no change
                                        // to ranking and no identity inference from name alone.
                                        val namesakes = displayArtists.take(5).filter {
                                            case.artistMatches(it.name)
                                        }.distinctBy { it.musicBrainzId }
                                        for (artist in namesakes) {
                                            mbAnchorArtistIdReads++
                                            val discography = mbSearch.searchReleaseGroupsForArtistMbidAndTitle(
                                                artist.musicBrainzId, case.anchor,
                                            ) as? MusicBrainzCatalogSearchResult.Success
                                            val found = matchingCredits(discography?.page?.items.orEmpty())
                                                .filter { it == artist.musicBrainzId }
                                            if (found.isNotEmpty()) {
                                                credits = found
                                                break
                                            }
                                        }
                                    }
                                    provenIds += credits.map { "catalog:ARTIST:$it" }
                                    mbLandingRawTargetRank = rawArtists.indexOfFirst {
                                        "catalog:ARTIST:${it.musicBrainzId}" in provenIds
                                    } + 1
                                    mbLandingCuratedTargetRank = rows.indexOfFirst { it.id in provenIds } + 1
                                    offered = provenIds.isNotEmpty()
                                }
                                "track" -> {
                                    val response = mbSearch.searchRecordings(
                                        case.query, MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT, recoverWeakLanding = true,
                                    )
                                        as? MusicBrainzCatalogSearchResult.Success
                                    if (response == null) failed = true
                                    val raw = response?.page?.items.orEmpty()
                                    mbLandingRawTargetRank = raw.indexOfFirst {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay)
                                    } + 1
                                    val curated = publicMusicBrainzWorkRows(case.query, raw,
                                        MusicBrainzRecordingHit::title, MusicBrainzRecordingHit::artistDisplay)
                                        .filter(::isCuratedMusicBrainzRecording)
                                    mbLandingCuratedTargetRank = curated.indexOfFirst {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay)
                                    } + 1
                                    rows = curated.map { SearchParityRow("catalog:RECORDING:${it.musicBrainzId}", it.title, it.artistDisplay) }
                                    val probe = mbSearch.searchRecordings(case.title + " " + case.artist, 50)
                                        as? MusicBrainzCatalogSearchResult.Success
                                    offered = (response?.page?.items.orEmpty() + probe?.page?.items.orEmpty())
                                        .any { case.titleMatches(it.title) && case.artistMatches(it.artistDisplay) &&
                                            isCuratedMusicBrainzRecording(it) }
                                }
                                else -> {
                                    val groups = mbSearch.searchReleaseGroups(
                                        case.query, MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT, recoverWeakLanding = true,
                                    )
                                        as? MusicBrainzCatalogSearchResult.Success
                                    val editions = mbSearch.searchReleases(case.query, MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT)
                                        as? MusicBrainzCatalogSearchResult.Success
                                    if (groups == null || editions == null) failed = true
                                    val rawGroups = groups?.page?.items.orEmpty()
                                    val rawEditions = editions?.page?.items.orEmpty()
                                    val rawMatches = rawGroups.map {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay)
                                    } + rawEditions.map {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay)
                                    }
                                    mbLandingRawTargetRank = rawMatches.indexOfFirst { it } + 1
                                    val groupHits = publicMusicBrainzWorkRows(case.query, rawGroups,
                                        MusicBrainzReleaseGroupHit::title, MusicBrainzReleaseGroupHit::artistDisplay)
                                        .filter(::isCuratedMusicBrainzReleaseGroup)
                                    val releaseHits = publicMusicBrainzWorkRows(case.query, rawEditions,
                                        MusicBrainzReleaseHit::title, MusicBrainzReleaseHit::artistDisplay)
                                        .filter(::isCuratedMusicBrainzRelease)
                                    val nativeRows = groupHits.map {
                                        com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                                            it.musicBrainzId, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
                                            it.title, it.artistDisplay, releaseDate = it.firstReleaseDate,
                                            releaseKind = it.primaryType, secondaryTypes = it.secondaryTypes,
                                            artistCreditMbids = it.artistCreditMbids,
                                        )
                                    } + releaseHits.map {
                                        com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                                            it.musicBrainzId, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE,
                                            it.title, it.artistDisplay, releaseDate = it.releaseDate,
                                            coverGroupMbid = it.releaseGroupMbid, artistCreditMbids = it.artistCreditMbids,
                                        )
                                    }
                                    val credits = (groupHits.map {
                                        "catalog:RELEASE_GROUP:${it.musicBrainzId}" to it.artistDisplay
                                    } + releaseHits.map {
                                        "catalog:RELEASE:${it.musicBrainzId}" to it.artistDisplay
                                    }).toMap()
                                    rows = rankMusicBrainzInitialAlbumRows(
                                        case.query,
                                        canonicalSearchRowsWithoutRedundantEditions(
                                            canonicalSearchRowsWithOfficialEditionTitles(nativeRows, editions?.page?.items.orEmpty()),
                                        ),
                                        groupHits, releaseHits,
                                    ).map {
                                        val id = canonicalNativeBrowseId(it)
                                        SearchParityRow(id, it.title, credits[id])
                                    }
                                    mbLandingCuratedTargetRank = rows.indexOfFirst {
                                        case.titleMatches(it.title) && case.artistMatches(it.artist)
                                    } + 1
                                    val probe = mbSearch.searchReleaseGroups(case.title + " " + case.artist, 50)
                                        as? MusicBrainzCatalogSearchResult.Success
                                    offered = (rawGroups.filter(::isCuratedMusicBrainzReleaseGroup) + probe?.page?.items.orEmpty()).any {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay) &&
                                            isCuratedMusicBrainzReleaseGroup(it)
                                    } || rawEditions.filter(::isCuratedMusicBrainzRelease).any {
                                        case.titleMatches(it.title) && case.artistMatches(it.artistDisplay)
                                    }
                                }
                            }
                            provider == apple -> {
                                val entity = when (case.kind) {
                                    "artist" -> ItunesCatalogEntity.ARTIST
                                    "track" -> ItunesCatalogEntity.SONG
                                    else -> ItunesCatalogEntity.ALBUM
                                }
                                val pager = ApplePublicSearchPager(case.query, entity, "br", pageSize = 24,
                                    source = ItunesPublicSearchSource(appleClient))
                                val response = pager.start() as? ApplePublicSearchPageLoad.Success
                                if (response == null) failed = true
                                val visible = response?.snapshot?.visible.orEmpty()
                                rows = when (case.kind) {
                                    "artist" -> appleNativeArtistSearchRows(AppleEditorialArtistLoad.Success(
                                        visible, response?.snapshot?.coverageUncertain == true,
                                    )).map { SearchParityRow(it.browseId, it.title) }
                                    "track" -> appleNativeTrackSearchRows(AppleEditorialTrackLoad.Success(
                                        visible, response?.snapshot?.coverageUncertain == true,
                                    )).map { SearchParityRow(it.key, it.title, it.artistName) }
                                    else -> appleNativeAlbumSearchRowsInSourceOrder(visible, "br")
                                        .map { SearchParityRow(it.browseId, it.title, it.artistName) }
                                }
                                val qualifiedAnchorIds = mutableSetOf<String>()
                                val qualifiedAnchorCollections = linkedMapOf<String, Long>()
                                fun qualifiedTitle(value: String): Boolean =
                                    applePublicAlbumWorkKey(value) != searchLabelKey(value) &&
                                        applePublicAlbumWorkKey(value) == searchLabelKey(case.title)
                                when (case.kind) {
                                    "artist" -> {
                                        val probe = appleRepo.searchAlbums(case.title + " " + case.anchor, "br", 50)
                                            as? AppleEditorialLoad.Success
                                        provenIds += probe?.releases.orEmpty().filter {
                                            searchLabelKey(it.title) == searchLabelKey(case.anchor) &&
                                                case.artistMatches(it.artistName)
                                        }.mapNotNull { it.artistId?.let { id -> "itunes:br:artist:$id" } }
                                        val qualifiedAnchors = probe?.releases.orEmpty().filter {
                                            case.artistMatches(it.artistName) && it.storefront == "br" &&
                                                applePublicAlbumWorkKey(it.title) != searchLabelKey(it.title) &&
                                                applePublicAlbumWorkKey(it.title) == searchLabelKey(case.anchor)
                                        }
                                        for (anchor in qualifiedAnchors) {
                                            val artistId = anchor.artistId ?: continue
                                            val key = "itunes:br:artist:$artistId"
                                            qualifiedAnchorIds += key
                                            qualifiedAnchorCollections.putIfAbsent(key, anchor.appleCollectionId)
                                        }
                                        offered = provenIds.isNotEmpty()
                                    }
                                    "track" -> {
                                        val probe = appleRepo.searchTracks(case.title + " " + case.artist, "br", 100)
                                            as? AppleEditorialTrackLoad.Success
                                        offered = (visible + probe?.tracks.orEmpty()).any {
                                            case.titleMatches(it.title) && case.artistMatches(it.artistName)
                                        }
                                    }
                                    else -> {
                                        val probe = appleRepo.searchAlbums(case.title + " " + case.artist, "br", 50)
                                            as? AppleEditorialLoad.Success
                                        offered = visible.any { case.titleMatches(it.title) && case.artistMatches(it.artistName) } ||
                                            probe?.releases.orEmpty().any {
                                                case.titleMatches(it.title) && case.artistMatches(it.artistName)
                                            }
                                    }
                                }
                                // Keep the original strict Top 5 independent of these
                                // additional commercial-edition and continuation observations.
                                appleQualifiedTitleTopFive = case.kind == "album" &&
                                    rows.take(5).any { !case.titleMatches(it.title) &&
                                        qualifiedTitle(it.title) && case.artistMatches(it.artist) }
                                appleQualifiedAnchorTopFive = case.kind == "artist" &&
                                    rows.take(5).any { it.id in qualifiedAnchorIds && it.id !in provenIds &&
                                        case.artistMatches(it.title) }
                                fun strictCandidate(item: ItunesCatalogItem): Boolean = when (case.kind) {
                                    "artist" -> item.scopedId in provenIds && case.artistMatches(item.title)
                                    else -> case.titleMatches(item.title) && case.artistMatches(item.artistName)
                                }
                                fun qualifiedCandidate(item: ItunesCatalogItem): Boolean = when (case.kind) {
                                    "artist" -> item.scopedId in qualifiedAnchorIds &&
                                        item.scopedId !in provenIds && case.artistMatches(item.title)
                                    "album" -> !case.titleMatches(item.title) &&
                                        qualifiedTitle(item.title) && case.artistMatches(item.artistName)
                                    else -> false
                                }
                                appleLandingDiscoveredTargetRank = pager.firstDiscoveredRank(::strictCandidate)
                                appleLandingDiscoveredQualifiedRank = pager.firstDiscoveredRank(::qualifiedCandidate)
                                // Independently corroborate a qualified top-five result using
                                // the exact Apple collection ID and credit, never another source.
                                // Keep this evidence separate from the frozen strict score.
                                if (appleQualifiedTitleTopFive || appleQualifiedAnchorTopFive) {
                                    val candidate = visible.take(5).firstOrNull(::qualifiedCandidate)
                                    val collectionId = candidate?.let {
                                        if (case.kind == "artist") qualifiedAnchorCollections[it.scopedId]
                                        else it.id
                                    }
                                    if (candidate != null && collectionId != null && collectionId > 0L) {
                                        appleQualifiedIdentityReads++
                                        val lookup = appleClient.lookupSearchCollection(collectionId, "br")
                                            as? ItunesCatalogResult.Success
                                        val exact = lookup?.items?.singleOrNull {
                                            it.id == collectionId && it.type == ItunesCatalogItemType.ALBUM &&
                                                it.storefront == "br" && case.artistMatches(it.artistName)
                                        }
                                        appleQualifiedIdentityVerified = exact != null && if (case.kind == "artist") {
                                            exact.artistId == candidate.id &&
                                                applePublicAlbumWorkKey(exact.title) == searchLabelKey(case.anchor)
                                        } else {
                                            exact.artistId != null && exact.artistId == candidate.artistId &&
                                                searchLabelKey(exact.title) == searchLabelKey(candidate.title) &&
                                                qualifiedTitle(exact.title)
                                        }
                                        if (appleQualifiedIdentityVerified)
                                            reviewVerifiedQualifiedIds += candidate.scopedId
                                    }
                                }
                                // Measure later availability only if the initial page has
                                // neither accepted strict nor edition-qualified target.
                                // Never reorder or overwrite the first-page metric.
                                if (response != null && visible.none { strictCandidate(it) || qualifiedCandidate(it) } &&
                                    (case.kind != "artist" || provenIds.isNotEmpty() || qualifiedAnchorIds.isNotEmpty())
                                ) {
                                    var more = response.snapshot.hasMore
                                    while (more && appleContinuationGestures < 2 &&
                                        !appleContinuationTargetRecovered && !appleContinuationQualifiedRecovered
                                    ) {
                                        appleContinuationGestures++
                                        when (val continuation = pager.loadMore()) {
                                            is ApplePublicSearchPageLoad.Failure -> {
                                                appleContinuationInterrupted = true
                                                more = false
                                            }
                                            is ApplePublicSearchPageLoad.Success -> {
                                                val candidates = continuation.snapshot.visible
                                                appleContinuationTargetRecovered = candidates.any(::strictCandidate)
                                                appleContinuationQualifiedRecovered = candidates.any(::qualifiedCandidate)
                                                more = continuation.snapshot.hasMore
                                            }
                                        }
                                    }
                                }
                            }
                            token != null -> when (case.kind) {
                                "track" -> {
                                    // Discogs' database Search does not expose an
                                    // independently typed recording route.
                                    val records = anchorRecords(case)
                                    for (record in records) {
                                        val page = discogsReleasePage(record.artistNames.joinToString(", "), record)
                                        assertTrue(page.page.browseId == "discogs:release:${record.link.id}")
                                        if (page.tracks.any { case.titleMatches(it.title) }) {
                                            releasePath = true
                                            offered = true
                                            break
                                        }
                                    }
                                }
                                "artist" -> {
                                    var page = 1
                                    var accepted = emptyList<com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow>()
                                    while (page in 1..3) {
                                        val answer = discogsArtists.search(case.query, page)
                                            as? DiscogsNativeArtistSearchResult.Success
                                        if (answer == null) { failed = true; break }
                                        val source = answer.page.artists.map {
                                            com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow(it.artistId, it.name)
                                        }
                                        val resolved = if (page == 1) discogsResolveArtistSearchHomonyms(
                                            query = case.query, rows = source,
                                            stillCurrent = { true },
                                            searchReleases = { discogsReleases.search(case.query) },
                                            readRelease = discogsDetails::fetchRelease,
                                        ) else DiscogsArtistSearchResolution(source)
                                        homonymCandidates += resolved.ambiguousCandidates
                                        homonymDetailReads += resolved.detailReads
                                        homonymCorroborated = homonymCorroborated || resolved.corroboratedSelection
                                        val candidates = com.music.bitchord.ui.screens.discogsSearchDistinctArtists(
                                            case.query, resolved.rows,
                                        )
                                        val exact = candidates.any {
                                            (com.music.bitchord.ui.screens.discogsSearchArtistRank(case.query, it.name) ?: 99) <= 2
                                        }
                                        accepted = if (exact) candidates.filter {
                                            (com.music.bitchord.ui.screens.discogsSearchArtistRank(case.query, it.name) ?: 99) <= 2
                                        } else candidates.take(12)
                                        if (!com.music.bitchord.ui.screens.discogsSearchNeedsNextRelevantPage(
                                            accepted.size, answer.page.nextPage,
                                        )) break
                                        page = answer.page.nextPage ?: break
                                    }
                                    assertTrue(accepted.map {
                                        searchLabelKey(com.music.bitchord.ui.screens.discogsArtistDisplayName(it.name))
                                    }.distinct().size == accepted.size)
                                    rows = accepted.map { SearchParityRow(it.browseId, it.name) }
                                    provenIds += anchorRecords(case).mapNotNull {
                                        it.artistIds.singleOrNull()?.let { id -> "discogs:artist:$id" }
                                    }
                                    offered = provenIds.isNotEmpty() && (!case.explicitDiscogsSuffix ||
                                        rows.any { it.id in provenIds && searchLabelKey(it.title) == searchLabelKey(case.query) })
                                }
                                else -> {
                                    var page = 1
                                    var accepted = emptyList<DiscogsNativeReleaseSearchHit>()
                                    while (page in 1..3) {
                                        val answer = discogsReleases.search(case.query, page)
                                            as? DiscogsNativeReleaseSearchResult.Success
                                        if (answer == null) { failed = true; break }
                                        accepted = answer.page.releases.filter {
                                            com.music.bitchord.ui.screens.discogsSearchReleaseRank(case.query, it.title) != null
                                        }.sortedWith(compareBy<DiscogsNativeReleaseSearchHit> {
                                            com.music.bitchord.ui.screens.discogsSearchReleaseRank(case.query, it.title)
                                                ?: Int.MAX_VALUE
                                        }.thenBy { it.releaseId })
                                        if (!com.music.bitchord.ui.screens.discogsSearchNeedsNextRelevantPage(
                                            accepted.size, answer.page.nextPage,
                                        )) break
                                        page = answer.page.nextPage ?: break
                                    }
                                    rows = accepted.map {
                                        val display = com.music.bitchord.ui.screens.discogsSearchReleaseDisplay(it.title)
                                        SearchParityRow(it.browseId, display.title,
                                            display.artistCredit?.let { name ->
                                                com.music.bitchord.ui.screens.discogsArtistDisplayName(name)
                                            })
                                    }
                                    for (candidate in rows.take(10).filter {
                                        case.titleMatches(it.title) && case.artistMatches(it.artist)
                                    }) {
                                        val id = candidate.id.substringAfterLast(':').toLong()
                                        val response = discogsDetails.fetchRelease(id)
                                        if (response is DiscogsLookupResult.Throttled ||
                                            response is DiscogsLookupResult.Unauthorized ||
                                            response is DiscogsLookupResult.Disabled) { failed = true; break }
                                        val record = (response as? DiscogsLookupResult.Found)?.record ?: continue
                                        assertTrue(record.link.id == id)
                                        val name = record.artistNames.singleOrNull()?.let {
                                            com.music.bitchord.ui.screens.discogsArtistDisplayName(it)
                                        }
                                        if (!record.unofficial && case.titleMatches(record.title) && case.artistMatches(name))
                                            provenIds += candidate.id
                                    }
                                    offered = provenIds.isNotEmpty()
                                    if (!offered && !failed) offered = anchorRecords(case).isNotEmpty()
                                }
                            }
                        }
                        true
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    false
                }
                val prefix = when {
                    case.kind == "category" -> "catalog:GENRE:"
                    provider == mb && case.kind == "artist" -> "catalog:ARTIST:"
                    provider == mb && case.kind == "track" -> "catalog:RECORDING:"
                    provider == mb -> "catalog:RELEASE"
                    provider == apple && case.kind == "artist" -> "itunes:br:artist:"
                    provider == apple && case.kind == "track" -> "itunes:br:track:"
                    provider == apple -> "itunes:br:album:"
                    case.kind == "artist" -> "discogs:artist:"
                    else -> "discogs:release:"
                }
                assertTrue(rows.all { it.id.startsWith(prefix) && it.title.isNotBlank() })
                assertEquals(rows.size, rows.map { it.id }.distinct().size)
                assertTrue(rows.none {
                    searchLabelKey(it.title) in setOf("artista discogs", "lançamento discogs", "lancamento discogs")
                })
                // -1 = unresolved; 0 = not the prelabelled target; 1 = target.
                // Unknown real namesakes must not disappear from the denominator.
                val judgements = rows.take(10).map { row ->
                    when {
                        case.kind == "category" -> if (row.id in provenIds) 1 else -1
                        case.kind == "artist" -> {
                            val name = if (provider == discogs)
                                com.music.bitchord.ui.screens.discogsArtistDisplayName(row.title)
                            else row.title
                            if (!case.artistMatches(name)) 0
                            else if (row.id in provenIds && (!case.explicitDiscogsSuffix ||
                                provider != discogs || searchLabelKey(row.title) == searchLabelKey(case.query))) 1
                            else -1
                        }
                        !case.titleMatches(row.title) -> 0
                        !case.artistMatches(row.artist) -> -1
                        provider == discogs && row.id !in provenIds -> -1
                        else -> 1
                    }
                }
                if (System.getenv("IDM_SEARCH_TOP10_REVIEW") == "1" &&
                    (provider == mb || provider == apple)
                ) {
                    val dir = File(System.getenv("IDM_CATALOG_METRICS_DIR")
                        ?: "build/idm-catalog-metrics", "top10-review")
                    check(dir.exists() || dir.mkdirs())
                    val caseNumber = (index + 1).toString().padStart(2, '0')
                    val review = rows.take(10).mapIndexed { rank, row ->
                        reviewRowJson(rank + 1, row, judgements[rank],
                            row.id in reviewVerifiedQualifiedIds)
                    }
                    File(dir, "${sourceName}_${caseNumber}.json").writeText(
                        "{\"schema\":1,\"provider\":${reviewJsonString(sourceName)}," +
                            "\"case\":${index + 1},\"kind\":${reviewJsonString(case.kind)}," +
                            "\"complete\":${complete != null && !failed},\"rows\":[" +
                            review.joinToString(",") + "]}"
                    )
                }
                val found = judgements.take(5).any { it == 1 }
                val targetProven = offered || (case.kind == "category" && categoryRoute) ||
                    judgements.any { it == 1 }
                val status = when {
                    case.kind == "category" -> if (categoryRoute) "local_category_verified" else "local_category_missing"
                    provider == discogs && token == null -> "token_not_configured"
                    !supported -> if (releasePath) "unsupported_track_search_release_path_verified"
                        else "unsupported_track_search_release_path_unverified"
                    complete == null -> "timeout_or_partial"
                    found -> "target_in_top_five"
                    failed -> "source_interruption"
                    judgements.any { it == 1 } -> "target_below_top_five"
                    targetProven -> "target_proven_not_visible"
                    else -> "target_not_corroborated"
                }
                report("search_matrix_" + sourceName + "_" + (index + 1).toString().padStart(2, '0'), Sample(
                    status = status, eligible = judgements.count { it == 1 }, visible = rows.size,
                    counts = mapOf(
                        "target_proven" to if (targetProven) 1 else 0,
                        "top_five_target" to if (found) 1 else 0,
                        "first_ten_rows" to judgements.size,
                        "labelled_target_rows" to judgements.count { it == 1 },
                        "labelled_non_target_rows" to judgements.count { it == 0 },
                        "unjudged_rows" to judgements.count { it == -1 },
                        "local_category_route_verified" to if (categoryRoute) 1 else 0,
                        "native_search_supported" to if (supported) 1 else 0,
                        "release_track_path_verified" to if (releasePath) 1 else 0,
                        "source_interruption" to if (failed) 1 else 0,
                        "homonym_candidates" to homonymCandidates,
                        "homonym_detail_reads" to homonymDetailReads,
                        "homonym_corroborated" to if (homonymCorroborated) 1 else 0,
                        "apple_qualified_title_top_five" to if (appleQualifiedTitleTopFive) 1 else 0,
                        "apple_qualified_anchor_top_five" to if (appleQualifiedAnchorTopFive) 1 else 0,
                        "apple_continuation_gestures" to appleContinuationGestures,
                        "apple_continuation_target_recovered" to if (appleContinuationTargetRecovered) 1 else 0,
                        "apple_continuation_qualified_recovered" to if (appleContinuationQualifiedRecovered) 1 else 0,
                        "apple_continuation_interrupted" to if (appleContinuationInterrupted) 1 else 0,
                        "apple_landing_discovered_target_rank" to appleLandingDiscoveredTargetRank,
                        "apple_landing_discovered_qualified_rank" to appleLandingDiscoveredQualifiedRank,
                        "apple_qualified_identity_verified" to if (appleQualifiedIdentityVerified) 1 else 0,
                        "apple_qualified_identity_reads" to appleQualifiedIdentityReads,
                        "mb_anchor_field_probe" to if (mbAnchorFieldProbe) 1 else 0,
                        "mb_anchor_artist_id_reads" to mbAnchorArtistIdReads,
                        "mb_landing_raw_target_rank" to mbLandingRawTargetRank,
                        "mb_landing_curated_target_rank" to mbLandingCuratedTargetRank,
                        "elapsed_ms" to ((System.nanoTime() - start) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                ))
            }
        }
    }

}
