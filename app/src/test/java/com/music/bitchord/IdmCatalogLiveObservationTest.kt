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
                        if (response.status == 200) artistIndexCandidates +=
                            DiscogsPublicArtistParsing.page(response.body, page)
                                ?.entries?.size ?: 0
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
            nativeSource = AppleNativeGenreSearchSource { term, storefront ->
                appleRepo.searchAlbums(term, storefront, limit = 200,
                    capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    attribute = ItunesCatalogSearchAttribute.GENRE_INDEX)
            },
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
                                    val response = appleSource.discoverNative(tag, "br")
                                    val result = response as? AppleEditorialLoad.Success
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
        val discogsDetails = DiscogsClient(
            enabled = token != null, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            admission = discogsGuard, tokenProvider = { token },
        )
        val discogsCatalog = DiscogsNativeArtistCatalog(
            enabled = token != null, tokenProvider = { token }, admission = discogsGuard,
            editions = DiscogsNativeEditionClient(discogsDetails),
        )
        for (provider in listOf(mb, apple, discogs)) {
            val sourceName = when (provider) {
                mb -> "musicbrainz"
                apple -> "itunes"
                else -> "discogs"
            }
            for ((index, case) in artistParityCases.withIndex()) {
                val target = if (case.extensive) 30 else 1
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
                                val creditedIds = anchors?.releases.orEmpty().filter {
                                    artistParityFold(it.title) == titleKey &&
                                        artistParityFold(it.artistName) == nameKey
                                }.mapNotNull { it.artistId }.toSet()
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
                        "release_sections" to sections,
                        "verified_track_rows" to tracks,
                        "first_profile_ms" to firstProfileMs,
                        "incomplete_coverage" to if (incomplete) 1 else 0,
                        "elapsed_ms" to ((System.nanoTime() - started) / 1_000_000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    ),
                ))
            }
        }
    }

}
