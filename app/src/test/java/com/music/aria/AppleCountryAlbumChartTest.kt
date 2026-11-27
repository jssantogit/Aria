package com.music.aria

import com.music.aria.data.catalog.metadata.*
import org.junit.Assert.*
import org.junit.Test

class AppleCountryAlbumChartTest {
    private fun feed(vararg songs: String): String =
        """{"feed":{"title":"Top Albums","results":[${songs.joinToString(",")}]}}"""

    private fun album(id: String = "123", country: String = "br", artist: String = "Cantora") =
        """{"id":"$id","kind":"albums","name":"Uma Canção","artistName":"$artist",
            "artworkUrl100":"https://is2-ssl.mzstatic.com/image/thumb/X/100x100bb.jpg",
            "url":"https://music.apple.com/$country/album/teste/$id"}"""

    @Test fun territoryAndRankingIdentityIsNeverAMusicBrainzOrAudioId() {
        val result = parseAppleCountryAlbumChart(feed(album()), "br", 1234L)!!
        assertEquals("Brasil", result.territory.displayName)
        assertEquals("itunes:br:album:123", result.albums.single().albumBrowseId)
        assertEquals("catalog:ALBUM_CHART:itunes:br", appleAlbumChartBrowseId("br"))
        assertEquals("Brasil", parseAppleAlbumChartBrowseId("catalog:ALBUM_CHART:itunes:br")?.displayName)
        assertNull(parseAppleAlbumChartBrowseId("catalog:ALBUM_CHART:itunes:unknown"))
        assertEquals(1, result.albums.single().position)
    }

    @Test fun rejectsCrossStorefrontAndMalformedLinksWithoutInventingMatches() {
        assertNull(parseAppleCountryAlbumChart(feed(album(country = "us")), "br", 1234))
        assertNull(parseAppleCountryAlbumChart(feed(album(id = "123"), album(id = "123")), "br", 1234))
        val wrongKind = album().replace("\"albums\"", "\"songs\"")
        assertNull(parseAppleCountryAlbumChart(feed(wrongKind), "br", 1234))
        val malicious = album().replace("music.apple.com", "music.apple.com.attacker.invalid")
        assertNull(parseAppleCountryAlbumChart(feed(malicious), "br", 1234))
        assertNull(parseAppleCountryAlbumChart(feed(album(id = "123")), "zz", 1234))
        assertNull(parseAppleCountryAlbumChart(feed(album(id = "123")), "br", -1))
    }

    @Test fun missingRowsDoNotCauseSyntheticRankChangesOrImages() {
        val broken = album(id = "124").replace("\"name\":\"Uma Canção\"", "\"name\":\"\"")
        val valid = album(id = "125").replace("is2-ssl.mzstatic.com", "evil.example")
        val chart = parseAppleCountryAlbumChart(feed(album(), broken, valid), "br", 1234)!!
        assertEquals(listOf(1, 3), chart.albums.map { it.position })
        assertNull(chart.albums.last().artworkUrl)
        assertEquals(2, chart.albums.size)
        assertNull(parseAppleCountryAlbumChart("""{"feed":{"results":[]}}""", "br", 1234))
    }
}
