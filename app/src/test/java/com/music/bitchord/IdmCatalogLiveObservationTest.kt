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
        val requests: Int = 0,
        val artwork: Int = 0,
        val shelves: List<HomeShelf> = emptyList(),
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
            "http_or_transport_reads" to sample.requests.toString(),
            "displayed_with_artwork" to sample.artwork.toString(),
        )
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
            "|reads=" + sample.requests)
    }

    private fun cards(shelves: List<HomeShelf>): List<com.music.bitchord.data.model.ShelfItem> =
        shelves.flatMap { it.items }.distinctBy { it.browseId }

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
        val mbTyped = (mbResult as? MusicBrainzCatalogSearchResult.Success)
            ?.page?.items?.let { hits ->
                // The source type remains a ReleaseGroup, not a name match.
                @Suppress("UNCHECKED_CAST")
                canonicalPublicReleaseRows(
                    hits as List<MusicBrainzReleaseGroupHit>, startDate, date,
                )
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
        val mbPage = (mbResult as? MusicBrainzCatalogSearchResult.Success)?.page
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
            shelves = mbShelves,
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
                    query = term, storefront = storefront, limit = 100,
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
        val appleResult = withTimeoutOrNull(95_000L) {
            try { appleHome.discover("br", date) }
            catch (_: Exception) { null }
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
            requests = appleProbes?.probesMade ?: 0,
            artwork = appleItems.count { !it.thumbnailUrl.isNullOrBlank() },
            shelves = appleShelves,
        ))

        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(String::isNotBlank)
        val discogsRead = if (token == null) null else {
            val caps = setOf(ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY)
            val guard = RateLimitedProviderTransport { gateFor(discogs, caps) }
            val native = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { token },
                admission = guard,
                editions = DiscogsNativeEditionClient(
                    DiscogsClient(
                        enabled = true,
                        capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                        admission = guard,
                    ),
                ),
            )
            withTimeoutOrNull(95_000L) {
                try { native.discover(DiscogsEditorialQuery("K-Pop", isStyle = true),
                    maxReleases = 3) }
                catch (_: Exception) { null }
            }
        }
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
            raw = null, parsed = null, eligible = verifiedDiscogs.size,
            visible = discogsItems.size,
            // The adapter performs nested master/release reads not exposed by
            // a central counter yet. Reporting zero would be misleading.
            requests = 0, artwork = discogsItems.count {
                !it.thumbnailUrl.isNullOrBlank()
            }, shelves = discogsShelves,
        ))

        // Same Home priority and fallback selection as MainActivity, evaluated
        // for every possible explicit provider order. No cross-provider IDs.
        val all = listOf(mb, apple, discogs)
        val orders = listOf(
            listOf(mb, apple, discogs), listOf(mb, discogs, apple),
            listOf(apple, mb, discogs), listOf(apple, discogs, mb),
            listOf(discogs, mb, apple), listOf(discogs, apple, mb),
        )
        var successful = 0
        var visible = 0
        for (order in orders) {
            val selection = projectCanonicalHomeEditorial(
                canonicalHomeShelves = mbHome,
                appleHomeReleases = UiState.Success(appleReleases),
                catalogEditorialOrder = order,
                canonicalWorldwideLoading = false,
                releasesHeading = "Lançamentos", discoveryHeading = "Descobrir",
                todayIsoDate = date, musicBrainzRows = mbTyped,
                discogsEditorial = UiState.Success(discogsShelves),
            )
            val owner = selection.provider
            val sourceItems = (selection.state as? UiState.Success)?.data
                ?.flatMap { it.items }.orEmpty()
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
                visible += sourceItems.distinctBy { it.browseId }.size
            }
        }
        report("collective", Sample(
            status = if (successful == 6) "verified_all_orders"
                else "partial_" + successful + "_of_6",
            parsed = 6, eligible = successful, visible = visible,
            requests = 0, artwork = 0,
        ))
        assertEquals(3, all.distinct().size)
    }
}
