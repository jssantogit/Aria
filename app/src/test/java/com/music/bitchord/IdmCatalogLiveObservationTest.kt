package com.music.bitchord

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
 * There are no raw JSON bodies, queries, names, IDs or tokens in artifacts.
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
        sample.counts.forEach { (key, value) ->
            require(Regex("[a-z_]+").matches(key) && value >= 0)
            require(key !in lines)
            lines[key] = value.toString()
        }
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
        val narrow = scanMusicBrainzRecentEditorial(
            musicBrainz, now.minusDays(30).toString(), date, maxPages = 3,
        )
        val wide = if (canonicalPublicNeedsMoreEditorialRows(narrow.rows))
            scanMusicBrainzRecentEditorial(
                musicBrainz, now.minusDays(90).toString(), date, maxPages = 2,
            ) else null
        val currentRows = (narrow.rows + wide?.rows.orEmpty())
            .distinctBy { it.musicBrainzId }
        val historical = if (canonicalPublicNeedsMoreEditorialRows(currentRows))
            scanMusicBrainzRecentEditorial(
                musicBrainz, now.minusDays(365).toString(),
                now.minusDays(91).toString(), maxPages = 1,
            ) else null
        val appMbRows = (currentRows + historical?.rows.orEmpty())
            .distinctBy { it.musicBrainzId }
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
        val scans = listOfNotNull(narrow, wide, historical)
        report("musicbrainz_app_flow", Sample(
            status = when {
                scans.any { it.sourceFailure } -> "partial_source_failure"
                appMbItems.isEmpty() -> "empty_after_filters"
                else -> "verified"
            },
            raw = scans.sumOf { it.sourcePositions },
            parsed = scans.sumOf { it.parsedRows },
            eligible = appMbRows.size, visible = appMbItems.size,
            requests = scans.sumOf { it.attempts },
            artwork = appMbItems.count { !it.thumbnailUrl.isNullOrBlank() },
            counts = mapOf(
                "narrow_window_reads" to narrow.attempts,
                "wide_window_reads" to (wide?.attempts ?: 0),
                "historical_window_reads" to (historical?.attempts ?: 0),
                "raw_cursor_windows_open" to scans.count { it.cursorRemaining },
            ),
            shelves = appMbShelves,
        ))

        // One real Home scroll window, using the exact cursor chosen by
        // initialCanonicalHomePageSnapshot. This is a bounded observation,
        // never an assertion that a source has any specific number of albums.
        val mbInitialPaging = initialCanonicalHomePageSnapshot(
            date, narrow, wide, historical,
        )
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
        report("musicbrainz_home_granular", Sample(
            status = when {
                appMbItems.isEmpty() -> "initial_no_editorial"
                !mbInitialPaging.hasMore -> "no_source_cursor"
                mbHomeMore == null -> "timeout_or_unavailable"
                mbHomeMore.sourceFailure -> "retryable_interruption"
                mbHomeExtra == null -> "no_new_verified_records"
                else -> "verified"
            },
            raw = mbHomeMore?.sourcePositions,
            parsed = mbHomeMore?.parsedRows,
            eligible = mbHomeMore?.rows?.size ?: 0,
            visible = mbHomeExtra?.items?.size ?: 0,
            requests = mbHomeMore?.attempts,
            artwork = mbHomeExtra?.items?.count {
                !it.thumbnailUrl.isNullOrBlank()
            } ?: 0,
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
        var firstInitialVerifiedNs: Long? = null
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
                    native.discover(
                        DiscogsEditorialQuery("K-Pop", isStyle = true),
                        maxReleases = 3,
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
        val extraRead = if (discogsRead is DiscogsEditorialResult.Verified)
            withTimeoutOrNull(50_000L) {
                try {
                    discogsGranularScanner?.discoverGranular(
                        query = DiscogsEditorialQuery("K-Pop", isStyle = true),
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
                        extraRead.retryCursor.candidatePosition
                    else -> 0
                },
                "retry_needed" to if (extraRead is DiscogsGranularRead.Interrupted) 1 else 0,
            ),
        ))

        // Reproduce one FULL app Home gesture, not merely the first two
        // index positions. Production checks up to three four-candidate
        // chunks, rotating to the next source Genre/Style predicate when
        // the current chunk does not yield a new verified Master.
        val homeQueries = discogsHomeGranularQueries()
        val discogsHomeInitial = initialDiscogsHomePageSnapshot(
            verifiedDiscogs, homeQueries.size,
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
        val homeCanStart = discogsRead is DiscogsEditorialResult.Verified &&
            discogsShelves.isNotEmpty() && homeQueries.isNotEmpty()
        val discogsHomeNext = if (homeCanStart) withTimeoutOrNull(110_000L) {
            var state = discogsHomeInitial
            val previouslyKnown = state.knownMasterIds
            for (batch in 1..3) {
                val index = state.nextQueryIndex ?: break
                val cursor = state.cursors.getOrNull(index) ?: break
                val query = homeQueries.getOrNull(index) ?: break
                val response = try {
                    discogsGranularScanner?.discoverGranular(
                        query = query, cursor = cursor,
                        knownMasterIds = state.knownMasterIds,
                        maxCandidates = 4,
                        onVerified = {
                            if (firstHomeVerifiedNs == null)
                                firstHomeVerifiedNs = System.nanoTime()
                        },
                    ) ?: break
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    homeTransportException = true
                    break
                }
                homeChunks++
                homeConsumed += when (response) {
                    is DiscogsGranularRead.Page -> response.consumedCandidates
                    is DiscogsGranularRead.Interrupted ->
                        (response.retryCursor.candidatePosition -
                            cursor.candidatePosition).coerceAtLeast(0)
                }
                state = advanceDiscogsHomePage(state, index, response)
                    ?: state.copy(loading = false, failed = true)
                if (state.failed || !state.hasMore ||
                    state.knownMasterIds.any { it !in previouslyKnown }
                ) break
            }
            state
        } else null
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
            discogsHomeContinuationShelf(discogs, discogsShelves, it)
        }
        report("discogs_home_granular", Sample(
            status = when {
                token == null -> "token_not_configured"
                discogsRead !is DiscogsEditorialResult.Verified ->
                    "initial_unverified"
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
                discogsEditorial = UiState.Success(discogsShelves),
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
        val sourceVisibleCounts = listOf(
            appMbItems.size, appleItems.size, discogsItems.size,
        )
        // Each order represents an alternate Home, not another shelf on
        // the same screen. Never sum repeated cards across permutations.
        val sourceCandidates = appMbItems.size + appleItems.size + discogsItems.size
        report("collective", Sample(
            status = when {
                token == null -> "partial_discogs_token_not_configured"
                discogsRead !is DiscogsEditorialResult.Verified ->
                    "partial_discogs_unverified"
                successful == orders.size -> "all_sources_observed"
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
                        discogsRead !is DiscogsEditorialResult.Verified).count { it },
                "first_order_visible" to firstOrderVisible,
            ),
        ))
        assertEquals(6, orders.size)
    }
}
