package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import java.util.Locale

/**
 * Public Apple Music Web editorial read boundary. Its data cannot be silently
 * substituted for the independent iTunes commercial catalog or a MusicBrainz
 * identity, and cannot be converted into a playable MediaAsset.
 *
 * Canonical callers receive an exact source/capability/storefront provenance
 * plus an explicit completeness outcome. No UI or Room models are constructed.
 */
internal data class AppleMusicWebEditorialResult<T>(
    val provider: ProviderKey,
    val capability: ProviderCapability,
    val storefront: String,
    val outcome: AppleMusicWebOutcome<T>,
)

/**
 * Two admission boundaries are intentional:
 * - the ProviderQueryExecutor owns the *whole* read, including transformations,
 *   ensuring mid-flight revocation cannot publish stale results;
 * - the AppleMusicWebPolicyTransport owns each physical HTTP dispatch.
 *
 * The production inventory still marks this provider BLOCKED; only explicitly
 * authorized test gates can exercise it before integration/readiness review.
 */
internal class AppleMusicWebEditorialGateway(
    private val catalog: AppleMusicWebCatalog,
    gate: ProviderPolicyGate,
    private val landingCache: AppleMusicWebLandingCache? = null,
) {
    private val executor = ProviderQueryExecutor(gate)
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB

    private fun market(raw: String): String =
        raw.lowercase(Locale.ROOT).also {
            require(Regex("[a-z]{2}").matches(it)) { "Invalid Apple storefront" }
        }

    private fun validateItems(
        actual: List<AppleMusicWebItem>,
        storefront: String,
        allowedKinds: Set<AppleMusicWebKind>,
    ) {
        val seen = HashSet<String>()
        actual.forEach { item ->
            if (item.storefront != storefront || item.kind !in allowedKinds ||
                item.id.isBlank() || !seen.add(item.scopedId)
            ) throw AppleMusicWebProtocolException("Wrong, duplicated or foreign Apple resource")
        }
    }

    private suspend fun <T> guarded(
        capability: ProviderCapability,
        country: String,
        operation: suspend (storefront: String) -> AppleMusicWebOutcome<T>,
    ): ProviderCallResult<AppleMusicWebEditorialResult<T>> =
        executor.execute(capability, key) {
            val storefront = market(country)
            AppleMusicWebEditorialResult(
                provider = key,
                capability = capability,
                storefront = storefront,
                outcome = operation(storefront),
            )
        }

    /**
     * A complete *page* is not a claim that the whole search collection is
     * complete. Page.hasMore remains authoritative for pagination.
     */
    suspend fun search(
        kind: AppleMusicWebKind,
        query: String,
        country: String,
        page: Int = 1,
        limit: Int = 25,
        continuation: String? = null,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebPage>> =
        guarded(ProviderCapability.CATALOG_SEARCH, country) { storefront ->
            val result = catalog.search(kind, query, storefront, page, limit, continuation)
            validateItems(result.items, storefront, setOf(kind))
            AppleMusicWebOutcome.Complete(result)
        }

    suspend fun genres(country: String): ProviderCallResult<AppleMusicWebEditorialResult<List<AppleMusicWebGenre>>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            AppleMusicWebOutcome.Complete(catalog.genres(storefront))
        }

    suspend fun albumGenres(id: String, country: String):
        ProviderCallResult<AppleMusicWebEditorialResult<List<AppleMusicWebGenre>>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            AppleMusicWebOutcome.Complete(catalog.albumGenres(id, storefront))
        }

    suspend fun browseRooms(country: String, genreId: String? = null):
        ProviderCallResult<AppleMusicWebEditorialResult<List<AppleMusicWebRoomRef>>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            AppleMusicWebOutcome.Complete(catalog.browseRooms(storefront, genreId))
        }

    suspend fun searchAppleCurators(query: String, country: String):
        ProviderCallResult<AppleMusicWebEditorialResult<List<AppleMusicWebCuratorRef>>> =
        guarded(ProviderCapability.CATALOG_SEARCH, country) { storefront ->
            AppleMusicWebOutcome.Complete(catalog.searchAppleCurators(query, storefront))
        }

    suspend fun appleCurator(id: String, country: String):
        ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebCurator>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            catalog.appleCurator(id, storefront)
        }

    /** Reusable bounded editorial landing, not just a live-test projection.
     * Search nominations use the independent search capability; exact public
     * references can be resolved with editorial permission alone. All room
     * reads and the final aggregate remain under one editorial ticket.
     */
    suspend fun curatedGenreLanding(tag: String, country: String, refresh: Boolean = false):
        ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebCuratorLanding>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            require(tag in amsEditorialGenreCorpus) { "Unknown AMS genre" }
            if (!refresh) landingCache?.read(storefront, tag)?.let {
                return@guarded AppleMusicWebOutcome.Complete(it)
            }
            val candidates = mutableListOf<AppleMusicWebCuratorRef>()
            var searchReads = 0
            var nominated: AppleMusicWebCuratorRef? = null
            var searchReason = "not_attempted"
            for (query in appleMusicWebCuratorQueries(tag)) {
                searchReads++
                when (val read = searchAppleCurators(query, storefront)) {
                    is ProviderCallResult.Success -> {
                        val rows = (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                            ?: break
                        searchReason = "success"
                        candidates += rows
                        nominated = resolveAppleMusicWebGenreCurator(candidates, storefront, tag)
                        if (nominated != null) break
                    }
                    ProviderCallResult.Denied -> { searchReason = "denied"; break }
                    ProviderCallResult.Stale -> { searchReason = "stale"; break }
                    is ProviderCallResult.Failed -> {
                        val cause = read.cause
                        if (cause is AppleMusicWebHttpException && cause.status in setOf(401,403,429))
                            throw cause
                        searchReason = "failed"
                        break
                    }
                }
            }
            val publicId = appleMusicWebPublicCuratorReference(storefront, tag)
            val id = nominated?.id ?: publicId
                ?: return@guarded AppleMusicWebOutcome.Unavailable("No verified curator nomination")
            val provenance = if (nominated != null) "search" else "public_reference"
            val curator = (catalog.appleCurator(id, storefront) as? AppleMusicWebOutcome.Complete)?.value
                ?: return@guarded AppleMusicWebOutcome.Unavailable("Curator grouping unavailable")
            if (curator.identity.id != id || !appleMusicWebCuratorMatches(curator.identity, storefront, tag))
                return@guarded AppleMusicWebOutcome.Unavailable("Unverified curator identity")
            val shared = appleMusicWebCuratorIsSharedForTag(curator.identity, tag)
            val releaseRooms = curator.rooms.filter(::isAppleMusicWebCuratorReleaseRoom)
            val all = linkedSetOf<String>()
            val accepted = linkedMapOf<String, AppleMusicWebCuratedAlbum>()
            val rejected = linkedSetOf<String>()
            var sourceRows = 0
            var reads = 0
            var failures = 0
            var bounded = releaseRooms.size > 3
            val continuations = mutableListOf<AppleMusicWebRoomContinuation>()
            val pageBudget = if (tag == "soul" && shared) 5 else if (shared) 3 else 1
            for (room in releaseRooms.take(3)) {
                if (room.curatorId != id || room.storefront != storefront || room.genreId != null)
                    throw AppleMusicWebProtocolException("Foreign curator room")
                var cursor: String? = null
                val visited = hashSetOf<String>()
                for (index in 1..pageBudget) {
                    reads++
                    val page = try {
                        catalog.roomContents(room, AppleMusicWebKind.ALBUM, cursor = cursor)
                    } catch (failure: AppleMusicWebHttpException) {
                        if (failure.status in setOf(401,403,429)) throw failure
                        failures++
                        break
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        failures++
                        break
                    }
                    validateItems(page.items, storefront, setOf(AppleMusicWebKind.ALBUM))
                    if (page.unresolvedReferences > 0) { failures++; break }
                    sourceRows += page.sourceRows
                    for (album in page.items) {
                        all += album.scopedId
                        val evidence = appleMusicWebCuratedAlbumEvidence(curator.identity, tag, album, room)
                        if (evidence == AppleMusicWebCuratedAlbumEvidence.REJECTED) {
                            rejected += album.scopedId
                            continue
                        }
                        val previous = accepted[album.scopedId]
                        if (previous == null ||
                            (evidence == AppleMusicWebCuratedAlbumEvidence.EXACT_GENRE_LABEL &&
                                previous.evidence == AppleMusicWebCuratedAlbumEvidence.SOUL_COMBINED_LABEL))
                            accepted[album.scopedId] = AppleMusicWebCuratedAlbum(album, evidence, room)
                    }
                    val next = page.nextCursor ?: break
                    if (!visited.add(next)) throw AppleMusicWebProtocolException("Cyclic curator room cursor")
                    if (index == pageBudget) {
                        bounded = true
                        continuations += AppleMusicWebRoomContinuation(room,next,visited - next)
                    }
                    cursor = next
                }
            }
            continuations += releaseRooms.drop(3).map { AppleMusicWebRoomContinuation(it,null) }
            val result = AppleMusicWebCuratorLanding(
                tag = tag, curator = curator, albums = accepted.values.toList(), shared = shared,
                provenance = provenance, searchCandidates = candidates.size, searchReads = searchReads,
                searchReason = searchReason, sourceRows = sourceRows, distinctCandidates = all.size,
                rejectedDistinct = (all - accepted.keys).size, rejectedObserved = rejected.size,
                roomReads = reads, roomFailures = failures, bounded = bounded,
                continuations = continuations,
            )
            if (failures > 0) AppleMusicWebOutcome.Partial(result, AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED)
            else {
                landingCache?.writeComplete(storefront, tag, result)
                AppleMusicWebOutcome.Complete(result)
            }
        }

    suspend fun roomContents(room: AppleMusicWebRoomRef, kind: AppleMusicWebKind, limit: Int = 25, cursor: String? = null):
        ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebRoomPage>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, room.storefront) { storefront ->
            val page = catalog.roomContents(room, kind, limit, cursor)
            validateItems(page.items, storefront, setOf(kind))
            if (page.unresolvedReferences > 0)
                AppleMusicWebOutcome.Partial(page, AppleMusicWebCompleteness.UNRESOLVED_EDITORIAL_REFERENCES)
            else AppleMusicWebOutcome.Complete(page)
        }

    suspend fun mostPlayedChart(
        kind: AppleMusicWebKind, country: String, genreId: String? = null,
        limit: Int = 25, cursor: String? = null,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebChartPage>> =
        guarded(ProviderCapability.CHART_MOST_PLAYED, country) { storefront ->
            val page = catalog.mostPlayedChart(kind, storefront, genreId, limit, cursor)
            validateItems(page.items, storefront, setOf(kind))
            AppleMusicWebOutcome.Complete(page)
        }

    suspend fun dailyChartDirectory():
        ProviderCallResult<AppleMusicWebEditorialResult<List<AppleMusicWebDailyChartRef>>> =
        guarded(ProviderCapability.CHART_MOST_PLAYED, "us") {
            AppleMusicWebOutcome.Complete(catalog.dailyChartDirectory())
        }

    suspend fun dailyChart(region: String, entry: AppleMusicWebDailyChartRef):
        ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebDailyChart>> =
        guarded(ProviderCapability.CHART_MOST_PLAYED, if (region == "global") "us" else region) { storefront ->
            val outcome = catalog.dailyChart(region, entry)
            when (outcome) {
                is AppleMusicWebOutcome.Complete -> validateItems(outcome.value.songs, storefront, setOf(AppleMusicWebKind.SONG))
                is AppleMusicWebOutcome.Partial -> validateItems(outcome.value.songs, storefront, setOf(AppleMusicWebKind.SONG))
                is AppleMusicWebOutcome.Unavailable -> Unit
            }
            outcome
        }

    /** Search evidence only: exact genre label, no claim to editorial rank.
     * Search permission remains independent of discography and charts. */
    suspend fun genreAlbumSearchPage(tag: String, country: String, cursor: String? = null):
        ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebGenreAlbumPage>> =
        guarded(ProviderCapability.CATALOG_SEARCH, country) { storefront ->
            val page = catalog.search(AppleMusicWebKind.ALBUM, tag, storefront,
                page = if (cursor == null) 1 else 2, limit = 25, continuation = cursor)
            validateItems(page.items, storefront, setOf(AppleMusicWebKind.ALBUM))
            AppleMusicWebOutcome.Complete(AppleMusicWebGenreAlbumPage(
                page.items.filter { item -> item.genreNames.any { appleMusicWebGenreNameMatches(it, tag) } },
                page.items.size, page.nextCursor,
            ))
        }

    suspend fun artist(
        id: String,
        country: String,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebArtist>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val result = catalog.artist(id, storefront)
            if (result.identity.kind != AppleMusicWebKind.ARTIST ||
                result.identity.id != id || result.identity.storefront != storefront
            ) throw AppleMusicWebProtocolException("Wrong Apple artist identity")
            validateItems(result.topSongs, storefront, setOf(AppleMusicWebKind.SONG))
            validateItems(result.albums, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.singlesAndEps, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.featuredAlbums, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.similarArtists, storefront, setOf(AppleMusicWebKind.ARTIST))
            validateItems(result.artistPlaylists, storefront, setOf(AppleMusicWebKind.PLAYLIST))
            if (result.unresolvedShelves.isEmpty()) AppleMusicWebOutcome.Complete(result)
            else AppleMusicWebOutcome.Partial(result, AppleMusicWebCompleteness.UNRESOLVED_EDITORIAL_REFERENCES)
        }

    suspend fun songCollections(
        id: String,
        country: String,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebSongCollections>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val record = catalog.songCollections(id, storefront)
            if (record.song.id != id || record.song.kind != AppleMusicWebKind.SONG ||
                record.song.storefront != storefront)
                throw AppleMusicWebProtocolException("Wrong Apple song identity")
            if (record.hasMore) AppleMusicWebOutcome.Partial(record, AppleMusicWebCompleteness.PAGE_LIMIT)
            else AppleMusicWebOutcome.Complete(record)
        }

    suspend fun album(
        id: String,
        country: String,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebAlbum>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val outcome = catalog.albumOutcome(id, storefront)
            fun validate(record: AppleMusicWebAlbum) {
                if (record.identity.kind != AppleMusicWebKind.ALBUM ||
                    record.identity.id != id || record.identity.storefront != storefront
                ) throw AppleMusicWebProtocolException("Wrong Apple album identity")
                validateItems(record.tracks, storefront, setOf(
                    AppleMusicWebKind.SONG, AppleMusicWebKind.MUSIC_VIDEO,
                ))
                validateItems(record.artists, storefront, setOf(AppleMusicWebKind.ARTIST))
            }
            when (outcome) {
                is AppleMusicWebOutcome.Complete -> validate(outcome.value)
                is AppleMusicWebOutcome.Partial -> validate(outcome.value)
                is AppleMusicWebOutcome.Unavailable -> Unit
            }
            outcome
        }

    suspend fun artistCollection(
        id: String,
        country: String,
        shelf: AppleMusicWebArtistShelf,
        page: Int = 1,
        limit: Int = 20,
        continuation: String? = null,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebPage>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val result = catalog.artistCollection(id, storefront, shelf, page, limit, continuation)
            val kind = when (shelf) {
                AppleMusicWebArtistShelf.TOP_SONGS -> setOf(AppleMusicWebKind.SONG)
                AppleMusicWebArtistShelf.SIMILAR_ARTISTS -> setOf(AppleMusicWebKind.ARTIST)
                AppleMusicWebArtistShelf.MUSIC_VIDEOS -> setOf(AppleMusicWebKind.MUSIC_VIDEO)
                AppleMusicWebArtistShelf.ARTIST_PLAYLISTS,
                AppleMusicWebArtistShelf.FEATURED_PLAYLISTS -> setOf(AppleMusicWebKind.PLAYLIST)
                else -> setOf(AppleMusicWebKind.ALBUM)
            }
            validateItems(result.items, storefront, kind)
            if (result.unresolvedReferences == 0) AppleMusicWebOutcome.Complete(result)
            else AppleMusicWebOutcome.Partial(result, AppleMusicWebCompleteness.UNRESOLVED_EDITORIAL_REFERENCES)
        }
}
