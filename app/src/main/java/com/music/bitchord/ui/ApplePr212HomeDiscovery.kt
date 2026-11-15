package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy
import com.music.bitchord.data.catalog.metadata.ApplePublicChartEntry
import com.music.bitchord.data.catalog.metadata.ApplePublicChartKind
import com.music.bitchord.data.catalog.metadata.ApplePublicChartsSnapshot
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

/** Only the data contract changes; the PR212 HomeScreen and HeroShelf stay untouched. */
internal data class ApplePr212HomeProjection(
    val shelves: List<HomeShelf>,
    /** Exact Apple IDs for metadata-only click navigation, not stream assets. */
    val rows: List<CanonicalCatalogSearchRow>,
)

private fun normalizedChartLabel(value: String): String =
    java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

private fun chartDisplayKey(entry: ApplePublicChartEntry): String =
    normalizedChartLabel(entry.name) + "|" + normalizedChartLabel(entry.artistName) +
        "|" + entry.kind

/** A stable market-round-robin; never claim an Apple worldwide chart exists. */
internal fun applePr212MarketMix(
    items: List<ApplePublicChartEntry>,
    markets: List<String> = listOf("br", "us", "kr", "jp"),
): List<ApplePublicChartEntry> {
    val groups = markets.map { market ->
        items.filter { it.storefront == market }.sortedBy { it.rank }
    }
    val seen = mutableSetOf<String>()
    return buildList {
        for (position in 0 until 100) {
            for (group in groups) {
                val row = group.getOrNull(position) ?: continue
                if (seen.add(chartDisplayKey(row))) add(row)
            }
        }
    }
}

/** Daily deterministic rotation: stable across refresh/recomposition, two distinct genres. */
internal fun applePr212HomeGenres(date: String): List<CanonicalExploreGenre> {
    val genres = canonicalExploreGenres.filter {
        it.tag in setOf("metal", "k-pop", "rock", "pop", "electronic",
            "hip-hop", "rnb", "jazz", "punk", "classical")
    }
    if (genres.isEmpty()) return emptyList()
    val rotation = Math.floorMod(date.hashCode(), genres.size)
    return listOf(genres[rotation], genres[(rotation + 5) % genres.size]).distinctBy { it.tag }
}

private fun chartRow(entry: ApplePublicChartEntry): CanonicalCatalogSearchRow? {
    if (entry.id <= 0L || entry.name.isBlank() || entry.artistName.isBlank() ||
        !Regex("[a-z]{2}").matches(entry.storefront)
    ) return null
    val kind = if (entry.kind == ApplePublicChartKind.SONGS)
        CanonicalSearchKind.RECORDING else CanonicalSearchKind.RELEASE
    val format = if (entry.kind == ApplePublicChartKind.ALBUMS)
        ItunesEditorialCatalog.formatFromAppleTitle(entry.name) else null
    return CanonicalCatalogSearchRow(
        musicBrainzId = entry.scopedId, kind = kind,
        title = entry.name, subtitle = entry.artistName,
        artworkUri = safeAppleArtwork(entry.artwork),
        releaseDate = entry.releaseDate,
        releaseKind = when (format) {
            ItunesEditorialReleaseFormat.ALBUM -> "Album"
            ItunesEditorialReleaseFormat.EP -> "EP"
            ItunesEditorialReleaseFormat.SINGLE -> "Single"
            else -> null
        },
        hasCatalogedAsset = false,
    )
}

private fun chartArtistRow(entry: ApplePublicChartEntry): CanonicalCatalogSearchRow? {
    val artistId = entry.artistId?.takeIf { it > 0L } ?: return null
    return CanonicalCatalogSearchRow(
        musicBrainzId = "itunes:${entry.storefront}:artist:$artistId",
        kind = CanonicalSearchKind.ARTIST,
        title = entry.artistName, subtitle = "Artista",
        // Chart song/album covers are NOT verified artist portraits.
        artworkUri = null, hasCatalogedAsset = false,
    )
}

internal fun applePr212ChartArtworkPatch(
    snapshot: ApplePublicChartsSnapshot,
    id: String,
    original: String,
    verified: String,
): ApplePublicChartsSnapshot {
    if (safeAppleArtwork(original) == null || safeAppleArtwork(verified) == null ||
        original == verified
    ) return snapshot
    fun patch(items: List<ApplePublicChartEntry>) = items.map {
        if (it.scopedId == id && it.artwork == original)
            it.copy(artwork = verified) else it
    }
    return snapshot.copy(songs = patch(snapshot.songs), albums = patch(snapshot.albums))
}

internal fun applePr212HomeProjection(
    charts: ApplePublicChartsSnapshot,
    recent: List<ShelfItem>,
    genreTracks: Map<String, List<ItunesCatalogItem>>,
    genreAlbums: Map<String, List<ItunesEditorialRelease>>,
    date: String,
): ApplePr212HomeProjection {
    val songs = applePr212MarketMix(charts.songs)
    val albums = applePr212MarketMix(charts.albums)
    val chosenGenres = applePr212HomeGenres(date)
    val rows = linkedMapOf<String, CanonicalCatalogSearchRow>()
    val assigned = mutableSetOf<String>()
    fun cards(values: List<CanonicalCatalogSearchRow>, limit: Int): List<ShelfItem> =
        values.asSequence().filter { assigned.add(it.musicBrainzId) }.take(limit).map { row ->
            rows[row.musicBrainzId] = row
            ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri,
                null, canonicalNativeBrowseId(row))
        }.toList()
    fun native(rows: List<ApplePublicChartEntry>): List<CanonicalCatalogSearchRow> =
        rows.mapNotNull(::chartRow)
    val shelves = buildList {
        // Deliberately FIRST: HomeScreen already renders its index 0 as PR212's hero.
        val lead = cards(native(songs), 12)
        if (lead.isNotEmpty()) add(HomeShelf("Mais relevantes pra você", lead))
        // No guessed play histories: retain only proven local/server listening rows.
        val recents = recent.distinctBy { it.browseId }.take(16)
        if (recents.isNotEmpty()) add(HomeShelf("Reproduções recentes", recents))
        val hotEps = native(albums).filter {
            it.releaseKind in setOf("EP", "Single")
        }
        val singles = cards(hotEps, 16)
        if (singles.isNotEmpty()) add(HomeShelf("Singles e EPs em alta", singles))

        fun genreShelf(genre: CanonicalExploreGenre): HomeShelf? {
            val selected = buildList {
                // These genres are asserted by Apple's own primaryGenreName,
                // not by the MusicBrainz seed category or a matched title.
                addAll(native(songs.filter {
                    AppleExploreGenrePolicy.matches(genre.tag, it.primaryGenre)
                }).take(8))
                addAll(genreTracks[genre.tag].orEmpty()
                    .filter { AppleExploreGenrePolicy.matches(genre.tag, it.genre) }
                    .mapNotNull(ApplePr212CatalogRows::track).take(8))
                addAll(native(albums.filter {
                    AppleExploreGenrePolicy.matches(genre.tag, it.primaryGenre)
                }).take(8))
                addAll(genreAlbums[genre.tag].orEmpty()
                    .filter { AppleExploreGenrePolicy.matches(genre.tag, it.genre) }
                    .mapNotNull(ApplePr212CatalogRows::album).take(8))
            }.distinctBy { it.musicBrainzId }
            val values = cards(selected, 16)
            return if (values.isEmpty()) null else HomeShelf(genre.title, values)
        }

        chosenGenres.getOrNull(0)?.let { genreShelf(it)?.let(::add) }
        // Ranking of artists is BitChord's count of chart appearances, not an
        // official Apple artist ranking. Require the exact artist ID in the feed.
        val featuredArtists = songs.asSequence()
            .mapNotNull(::chartArtistRow)
            .distinctBy { normalizedChartLabel(it.title) }
            .take(16).toList()
        val artistCards = cards(featuredArtists, 12)
        if (artistCards.isNotEmpty()) add(HomeShelf("Artistas em alta", artistCards))
        chosenGenres.getOrNull(1)?.let { genreShelf(it)?.let(::add) }
    }
    return ApplePr212HomeProjection(shelves, rows.values.toList())
}

/**
 * Small, source-scoped adapter through the EXISTING PR212 MainActivity binding.
 * MainActivity passes CanonicalCatalogSearchRow into canonicalHomeWithWorldwideEditorial;
 * we cannot add view-model collectors there without changing the frozen #212 root UI.
 * secondaryTypes stores a private HOME SLOT on these Apple-only rows; it never
 * represents a MusicBrainz secondary type and must not be persisted as one.
 */
private const val APPLE_PR212_HOME_SLOT = "apple-home-slot:"

internal fun applePr212TaggedHomeRows(
    projection: ApplePr212HomeProjection,
    date: String,
): List<CanonicalCatalogSearchRow> {
    val genres = applePr212HomeGenres(date)
    val slots = mapOf(
        "Mais relevantes pra você" to 0,
        "Singles e EPs em alta" to 2,
        (genres.getOrNull(0)?.title ?: "") to 3,
        "Artistas em alta" to 4,
        (genres.getOrNull(1)?.title ?: "") to 5,
    )
    val byId = projection.rows.associateBy { it.musicBrainzId }
    return projection.shelves.flatMap { shelf ->
        val slot = slots[shelf.title] ?: return@flatMap emptyList()
        shelf.items.mapNotNull { item ->
            byId[item.browseId]?.let {
                it.copy(secondaryTypes = "$APPLE_PR212_HOME_SLOT$slot")
            }
        }
    }
}

/** Converts the source adapter back to EXACTLY the existing HomeShelf cards. */
internal fun applePr212NativeHomeFromRows(
    worldwide: List<CanonicalCatalogSearchRow>,
    localShelves: List<HomeShelf>,
    trendingHeading: String,
    date: String,
): List<HomeShelf>? {
    val apple = worldwide.filter { row ->
        row.secondaryTypes?.startsWith(APPLE_PR212_HOME_SLOT) == true &&
            ApplePr212CatalogRoute.parse(row.musicBrainzId) != null &&
            !row.hasCatalogedAsset
    }
    if (apple.none { it.secondaryTypes == "${APPLE_PR212_HOME_SLOT}0" &&
            it.kind == CanonicalSearchKind.RECORDING
        }) return null
    val genres = applePr212HomeGenres(date)
    val titles = listOf(
        "Mais relevantes pra você", "Reproduções recentes", "Singles e EPs em alta",
        genres.getOrNull(0)?.title.orEmpty(), "Artistas em alta",
        genres.getOrNull(1)?.title.orEmpty(),
    )
    val alreadyShown = mutableSetOf<String>()
    return buildList {
        for (slot in titles.indices) {
            if (slot == 1) {
                // Existing local ListeningEventStore + verified local MediaAsset.
                val cards = localShelves.firstOrNull { it.title == trendingHeading }
                    ?.items.orEmpty().filter {
                        it.browseId?.startsWith("local:pick:") == true &&
                            alreadyShown.add(it.browseId!!)
                    }.take(16)
                if (cards.isNotEmpty()) add(HomeShelf(titles[slot], cards))
                continue
            }
            if (titles[slot].isBlank()) continue
            val cards = apple.asSequence().filter {
                it.secondaryTypes == "$APPLE_PR212_HOME_SLOT$slot" &&
                    (slot != 0 || it.kind == CanonicalSearchKind.RECORDING)
            }.filter { alreadyShown.add(it.musicBrainzId) }
                .take(if (slot == 0) 12 else 16)
                .map {
                    ShelfItem(it.title, it.subtitle.orEmpty(), it.artworkUri,
                        null, canonicalNativeBrowseId(it))
                }.toList()
            if (cards.isNotEmpty()) add(HomeShelf(titles[slot], cards))
        }
    }.takeIf { it.firstOrNull()?.title == "Mais relevantes pra você" }
}
