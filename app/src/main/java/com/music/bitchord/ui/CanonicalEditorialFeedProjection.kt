package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * Pure projection for the existing Home/Explore widgets.
 * Keeping these provider-neutral selections outside BitChordApp prevents a
 * giant Compose function from exceeding the JVM's 64 KiB method limit.
 * No UI elements, composable invocation, identity conversion or network read.
 */
internal fun projectCanonicalHomeEditorial(
    canonicalHomeShelves: List<HomeShelf>,
    appleHomeReleases: UiState<List<ItunesEditorialRelease>>,
    catalogEditorialOrder: List<ProviderKey>,
    canonicalWorldwideLoading: Boolean,
    releasesHeading: String,
    discoveryHeading: String,
    todayIsoDate: String,
    musicBrainzRows: List<CanonicalCatalogSearchRow> = emptyList(),
    picksHeading: String = "Escolha a dedo",
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
    moreHeading: String = "Mais",
): CatalogEditorialSelection {
    val mb = BuiltInProviderKeys.MUSICBRAINZ
    val apple = BuiltInProviderKeys.ITUNES
    val musicBrainzShelves = musicBrainzHomeEditorialShelves(
        canonicalHomeShelves, releasesHeading,
    )
    val musicBrainzExtraShelves = canonicalHomeShelves
        .filter { it.title in setOf("Álbuns para descobrir", "Singles e EPs") }
        .mapNotNull { shelf ->
            // These are independently typed MusicBrainz releases from the
            // same admitted source, never Apple, local, or server cards.
            val items = shelf.items.filter { item ->
                item.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") ||
                    item.browseId.orEmpty().startsWith("catalog:RELEASE:")
            }
            shelf.copy(items = items).takeIf { items.isNotEmpty() }
        }
    // Reuse the existing full native Home composer, retaining real MB IDs.
    // Earlier the projection discarded discovery/album/more shelves even when
    // typed release evidence was already present in the catalog snapshot.
    val verifiedPublicRows = musicBrainzRows.asSequence()
        .filter { canonicalEditorialReleaseType(it) != null }
        .filter { it.releaseDate == null || it.releaseDate.take(10) <= todayIsoDate }
        .distinctBy { it.kind to it.musicBrainzId }
        .toList()
    val nativeMusicBrainzShelves = if (musicBrainzShelves.isNotEmpty() &&
        verifiedPublicRows.size >= 5
    ) canonicalHomeWithWorldwideEditorial(
        localShelves = emptyList(),
        worldwideReleases = verifiedPublicRows,
        releasesHeading = releasesHeading,
        picksHeading = picksHeading,
        trendingHeading = "Em alta",
        worldwideTrending = null,
        todayIsoDate = todayIsoDate,
        albumsHeading = albumsHeading,
        singlesHeading = singlesHeading,
        moreHeading = moreHeading,
    ) else musicBrainzShelves + musicBrainzExtraShelves
    val nativeAppleState: UiState<List<HomeShelf>> = when (val source = appleHomeReleases) {
        is UiState.Success -> UiState.Success(
            appleFirstHomeShelves(
                appleAlbums = source.data,
                existingLocalShelves = emptyList(),
                releasesHeading = releasesHeading,
                discoveryHeading = discoveryHeading,
                todayIsoDate = todayIsoDate,
            ),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val appleState = compositeEditorialReadState(
        nativeAppleState,
        // An empty completed MB primary makes Apple an actively scheduled
        // fallback, not an unstarted/empty source. Preserve Loading until
        // the Apple request settles; never promote Apple before MB finishes.
        requestCanStart = catalogEditorialOrder.firstOrNull() == apple ||
            (catalogEditorialOrder.firstOrNull() == mb &&
                !canonicalWorldwideLoading && musicBrainzShelves.isEmpty()),
        evidenceAuthorized = apple in catalogEditorialOrder,
    )
    return selectCatalogEditorial(
        catalogEditorialOrder, mapOf(
            mb to if (canonicalWorldwideLoading && musicBrainzShelves.isEmpty())
                UiState.Loading else UiState.Success(
                    nativeMusicBrainzShelves,
                ),
            apple to appleState,
        ),
    )
}

/**
 * When a cold/permission-limited installation has little verified editorial
 * content, keep Home usable with actual navigation destinations from Explore.
 * These tiles claim no album, cover, playback, chart or provider popularity.
 */
internal fun canonicalHomeGenreDiscoveryShelf(): HomeShelf = HomeShelf(
    title = "Explore gêneros",
    items = canonicalExploreGenres.take(8).map { genre ->
        com.music.bitchord.data.model.ShelfItem(
            title = genre.title,
            subtitle = "Categoria",
            thumbnailUrl = null,
            videoId = null,
            browseId = canonicalGenreBrowseId(genre),
        )
    },
)

/** Retain personal shelves regardless of catalog preference or upstream status. */
internal fun projectCanonicalHomeDisplay(
    canonicalHomeShelves: List<HomeShelf>,
    selection: CatalogEditorialSelection,
    publicPlaylistShelf: HomeShelf?,
    countryChartShelf: HomeShelf? = null,
    communityShelf: HomeShelf? = null,
    personalRecommendationShelf: HomeShelf? = null,
    chartArtistsShelf: HomeShelf? = null,
): List<HomeShelf> {
    val editorial = listOfNotNull(countryChartShelf?.takeIf { it.items.isNotEmpty() }) +
        (selection.state as? UiState.Success)?.data.orEmpty() +
        listOfNotNull(communityShelf?.takeIf { it.items.isNotEmpty() }) +
        listOfNotNull(personalRecommendationShelf?.takeIf { it.items.isNotEmpty() }) +
        listOfNotNull(chartArtistsShelf?.takeIf { it.items.isNotEmpty() })
    val other = personalHomeShelves(canonicalHomeShelves) +
        listOfNotNull(publicPlaylistShelf?.takeIf { it.items.isNotEmpty() })
    // Do not mistake an empty chart capability for empty music. Show a small
    // navigation rail rather than an invented ranking/album/stream.
    val genreDiscovery = if (editorial.size + other.size < 3)
        listOf(canonicalHomeGenreDiscoveryShelf()) else emptyList()
    return editorial + genreDiscovery + other
}

internal fun projectCanonicalHomeState(
    displayed: List<HomeShelf>,
    selected: CatalogEditorialSelection,
): UiState<List<HomeShelf>> = when {
    displayed.isNotEmpty() -> UiState.Success(displayed)
    selected.state is UiState.Loading -> UiState.Loading
    else -> UiState.Error("Nenhum lançamento disponível nas fontes editoriais habilitadas.")
}

/** Genre tabs use original provider IDs, with public playlists outside fallback. */
internal fun projectCanonicalGenreState(
    tag: String?,
    genrePlaylists: List<ListenBrainzPublicPlaylist>,
    playlistHeading: String,
    appleByTag: Map<String, UiState<List<ItunesEditorialRelease>>>,
    musicBrainzRows: UiState<List<CanonicalCatalogSearchRow>>,
    ordered: List<ProviderKey>,
    releasesHeading: String,
    /** The exact requested tag, never inferred from an old MBID row. */
    musicBrainzResultTag: String? = tag,
    genreArtists: CanonicalGenreArtistSnapshot? = null,
    genrePopular: CanonicalGenrePopularSnapshot? = null,
    genrePopularityAuthorized: Boolean = false,
    todayIsoDate: String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
): UiState<List<HomeShelf>> {
    val playlistShelf = canonicalPublicPlaylistShelf(playlistHeading, genrePlaylists)
    if (tag == null) return UiState.Success(emptyList())
    // Do not leave the page forever Loading when Apple's genre contract
    // does not recognize the tag. Only permitted providers can fill releases.
    val appleSupported = AppleExploreGenrePolicy.supports(tag)
    val appleState: UiState<List<HomeShelf>> = if (!appleSupported) {
        UiState.Success(emptyList())
    } else when (
        val source = appleByTag[tag] ?: UiState.Loading
    ) {
        is UiState.Success -> UiState.Success(
            appleFirstGenreShelves(
                tag, source.data, publicPlaylists = null,
                releaseHeading = releasesHeading,
            ),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val mbState: UiState<List<HomeShelf>> = when (
        val source = if (musicBrainzResultTag == tag) musicBrainzRows else UiState.Loading
    ) {
        is UiState.Success -> UiState.Success(
            canonicalExploreGenreForBrowseId("catalog:GENRE:$tag")?.let { genre ->
                canonicalGenreEditorialShelves(genre, source.data, todayIsoDate)
            } ?: musicBrainzGenreEditorialShelves(source.data, releasesHeading),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val eligibleProviders = if (appleSupported) ordered
        else ordered.filterNot { it == BuiltInProviderKeys.ITUNES }
    val chosen = selectCatalogEditorial(eligibleProviders, mapOf(
        BuiltInProviderKeys.MUSICBRAINZ to mbState,
        BuiltInProviderKeys.ITUNES to appleState,
    ))
    val releases = (chosen.state as? UiState.Success)?.data.orEmpty()
    val knownArtists = genreArtists?.takeIf {
        it.tag == tag && BuiltInProviderKeys.MUSICBRAINZ in ordered
    }?.let(::canonicalGenreArtistsShelf)
    val popular = genrePopular?.takeIf {
        genrePopularityAuthorized && it.tag == tag &&
            BuiltInProviderKeys.MUSICBRAINZ in ordered
    }?.let(::canonicalGenrePopularShelf)
    val all = releases + listOfNotNull(popular) +
        listOfNotNull(knownArtists) +
        listOfNotNull(playlistShelf?.takeIf { it.items.isNotEmpty() })
    return when {
        all.isNotEmpty() -> UiState.Success(all)
        chosen.state is UiState.Loading -> UiState.Loading
        chosen.state is UiState.Error -> chosen.state
        else -> UiState.Error("Nenhum lançamento disponível nas fontes editoriais habilitadas.")
    }
}
