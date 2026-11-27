package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsVerifiedAppleArtworkTest {
    private val upc = "8801234567893"
    private val art = "https://is1-ssl.mzstatic.com/image/thumb/Music/a.jpg"
    private val store = "https://music.apple.com/br/album/example/101"
    private val record = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, 456L),
        title = "Example", artistNames = listOf("Artist"),
        country = "KR", year = 2024, labels = emptyList(),
        barcodes = listOf(upc), formats = listOf("File", "FLAC", "EP"),
        tracklist = listOf("1" to "First", "2" to "Second"),
        unofficial = false, artistIds = listOf(99L),
    )
    private val album = ItunesCatalogItem(
        ItunesCatalogItemType.ALBUM, 101L, "br", "Example - EP",
        "Artist", 88L, 101L, store, "2024-03-25", null,
        null, null, null, artworkUrl = art, trackCount = 2,
    )
    private fun track(id: Long, number: Int, title: String) = ItunesCatalogItem(
        ItunesCatalogItemType.TRACK, id, "br", title,
        "Artist", 88L, 101L, null, null, null,
        120000L, number, 1,
    )
    private val first = track(201L, 1, "First")
    private val second = track(202L, 2, "Second")
    private val result = ItunesCatalogResult.Success(listOf(album, first, second))

    @Test fun exactDigitalProductRetainsBothSourceIdsAndPromotionLink() {
        val proof = discogsVerifiedAppleArtwork(record, upc, result, "br")
        assertEquals(DiscogsVerifiedAppleArtwork(456L, "itunes:br:album:101", art, store), proof)
    }

    @Test fun physicalMixedUnofficialAndAmbiguousBarcodeEditionsDoNotEvenNominateLookup() {
        for (edition in listOf(
            record.copy(formats = listOf("CD", "EP")),
            record.copy(formats = listOf("File", "Vinyl", "EP")),
            record.copy(unofficial = true),
            record.copy(nativeTracklistIncomplete = true),
            record.copy(barcodes = listOf(upc, "123456789012")),
            record.copy(artistIds = emptyList()),
            record.copy(tracklist = listOf("A1" to "First", "A2" to "Second")),
        )) assertNull(discogsDigitalArtworkUpc(edition))
    }

    @Test fun conflictingProductTrackOrderYearStorefrontAndIncompleteResponseFailClosed() {
        for (bad in listOf(
            result.copy(possiblyTruncated = true),
            result.copy(items = listOf(album, first)),
            result.copy(items = listOf(album, first, first)),
            result.copy(items = listOf(album, first.copy(title = "Second"), second.copy(title = "First"))),
            result.copy(items = listOf(album.copy(artistName = "Namesake"), first, second)),
            result.copy(items = listOf(album.copy(releaseDate = "2025-01-01"), first, second)),
            result.copy(items = listOf(album.copy(upc = "123456789012"), first, second)),
            result.copy(items = listOf(album.copy(storeUrl = "https://music.apple.com/us/album/example/101"), first, second)),
            result.copy(items = listOf(album.copy(artworkUrl = "https://example.com/a.jpg"), first, second)),
            result.copy(items = listOf(album, album.copy(id = 102L), first, second)),
        )) assertNull(discogsVerifiedAppleArtwork(record, upc, bad, "br"))
    }

    @Test fun artworkOnlyAdmissionAndRevocationAreIndependentOfCatalogPermission() = runBlocking {
        val cap = ProviderCapability.RELEASE_ARTWORK
        val key = BuiltInProviderKeys.ITUNES
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(key, setOf(cap), ProviderReadiness.READY)),
            choices = mapOf(cap to listOf(ProviderChoice(key, enabled, 0))),
        )
        val gate = ProviderPolicyGate(policy(false))
        var calls = 0
        val resolver = DiscogsAppleArtworkResolver(
            lookup = AppleArtworkUpcSource { barcode, country ->
                assertEquals(upc, barcode)
                assertEquals("br", country)
                calls++
                if (calls == 1) gate.replace(policy(false))
                result
            },
            gateProvider = { gate },
        )
        assertNull(resolver.resolve(record))
        assertEquals(0, calls)
        gate.replace(policy(true))
        assertNull(resolver.resolve(record))
        assertEquals(1, calls)
        gate.replace(policy(true))
        assertEquals(art, resolver.resolve(record)?.artworkUrl)
        assertEquals(2, calls)
    }
}
