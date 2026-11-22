package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * A tag search is valid membership evidence, but contains no public ranking.
 * Do not relabel this shelf as 'Artistas em alta'.
 */
internal data class CanonicalGenreArtistSnapshot(
    val tag: String,
    val rows: List<CanonicalCatalogSearchRow>,
)

internal fun canonicalGenreArtistsShelf(
    snapshot: CanonicalGenreArtistSnapshot,
): HomeShelf? {
    val cards = snapshot.rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.ARTIST && it.title.isNotBlank() }
        .distinctBy { it.musicBrainzId }
        .take(18)
        .map { row ->
            ShelfItem(
                title = row.title,
                subtitle = "Artista",
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = canonicalNativeBrowseId(row),
            )
        }.toList()
    return cards.takeIf { it.isNotEmpty() }?.let {
        HomeShelf("Artistas do gênero", it)
    }
}
