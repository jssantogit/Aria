package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import org.junit.Assert.*
import org.junit.Test

class AppleCountryChartTest {
    private fun feed(vararg songs: String): String =
        """{"feed":{"title":"Top Songs","results":[${songs.joinToString(",")}]}}"""

    private fun song(id: String = "123", country: String = "br", artist: String = "Cantora") =
        """{"id":"$id","kind":"songs","name":"Uma Canção","artistName":"$artist",
            "artworkUrl100":"https://is2-ssl.mzstatic.com/image/thumb/X/100x100bb.jpg",
            "url":"https://music.apple.com/$country/album/teste/456?i=$id"}"""

    @Test fun territoryAndRankingIdentityIsNeverAMusicBrainzOrAudioId() {
        val result = parseAppleCountryChart(feed(song()), "br", 1234L)!!
        assertEquals("Brasil", result.territory.displayName)
        assertEquals("itunes:br:track:123", result.songs.single().songBrowseId)
        assertEquals("catalog:CHART:itunes:br", appleChartBrowseId("br"))
        assertEquals("Brasil", parseAppleChartBrowseId("catalog:CHART:itunes:br")?.displayName)
        assertNull(parseAppleChartBrowseId("catalog:CHART:itunes:unknown"))
        assertEquals(1, result.songs.single().position)
    }

    @Test fun rejectsCrossStorefrontAndMalformedLinksWithoutInventingMatches() {
        assertNull(parseAppleCountryChart(feed(song(country = "us")), "br", 1234))
        assertNull(parseAppleCountryChart(feed(song(id = "123"), song(id = "123")), "br", 1234))
        val wrongKind = song().replace("\"songs\"", "\"albums\"")
        assertNull(parseAppleCountryChart(feed(wrongKind), "br", 1234))
        val malicious = song().replace("music.apple.com", "music.apple.com.attacker.invalid")
        assertNull(parseAppleCountryChart(feed(malicious), "br", 1234))
        assertNull(parseAppleCountryChart(feed(song(id = "123")), "zz", 1234))
        assertNull(parseAppleCountryChart(feed(song(id = "123")), "br", -1))
    }

    @Test fun missingRowsDoNotCauseSyntheticRankChangesOrImages() {
        val broken = song(id = "124").replace("\"name\":\"Uma Canção\"", "\"name\":\"\"")
        val valid = song(id = "125").replace("is2-ssl.mzstatic.com", "evil.example")
        val chart = parseAppleCountryChart(feed(song(), broken, valid), "br", 1234)!!
        assertEquals(listOf(1, 3), chart.songs.map { it.position })
        assertNull(chart.songs.last().artworkUrl)
        assertEquals(2, chart.songs.size)
        assertNull(parseAppleCountryChart("""{"feed":{"results":[]}}""", "br", 1234))
    }
}
