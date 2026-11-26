package com.music.bitchord.ui

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalGenreArtistShelfTest {
    @Test fun pseudoArtistsCannotPolluteGenreDiscovery() {
        val rows = listOf("[unknown]", "Various Artists", "[no artist]", "Unknown Artist")
            .mapIndexed { i, name ->
                CanonicalCatalogSearchRow("unrelated-$i", CanonicalSearchKind.ARTIST, name, null)
            } + CanonicalCatalogSearchRow(
                "77777777-7777-4777-8777-777777777777",
                CanonicalSearchKind.ARTIST, "Metallica", null,
            )
        val shelf = requireNotNull(canonicalGenreArtistsShelf(
            CanonicalGenreArtistSnapshot("metal", rows),
        ))
        assertEquals(listOf("Metallica"), shelf.items.map { it.title })
    }

    @Test fun onlyExactArtistIdentitiesAppearAndNothingIsLabeledTrending() {
        val mbid = "11111111-1111-4111-8111-111111111111"
        val artist = CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.ARTIST, "Artista A", "Brasil")
        val snapshot = CanonicalGenreArtistSnapshot("jazz", listOf(
            artist, artist, CanonicalCatalogSearchRow(
                "22222222-2222-4222-8222-222222222222",
                CanonicalSearchKind.RELEASE_GROUP, "Álbum", "Artista A",
            ),
        ))
        val shelf = canonicalGenreArtistsShelf(snapshot)!!
        assertEquals("Artistas do gênero", shelf.title)
        assertEquals(1, shelf.items.size)
        assertEquals("catalog:ARTIST:$mbid", shelf.items.single().browseId)
        assertNull(shelf.items.single().videoId)
    }
}
