package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTokenParser
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AppleMusicWebNativeProviderTest {
    private class FakeTransport(
        private val response: suspend (ProviderCapability, String, Map<String, String>) -> String,
    ) : AppleMusicWebTransport {
        val calls = mutableListOf<Triple<ProviderCapability, String, Map<String, String>>>()
        override suspend fun get(
            capability: ProviderCapability, path: String, params: Map<String, String>,
        ): String {
            calls += Triple(capability, path, params)
            return response(capability, path, params)
        }
    }

    @Test fun searchFollowsAppleIssuedOffsetRatherThanPageTimesLimit() = runBlocking {
        val fake = FakeTransport { _, _, args ->
            if (args["offset"] == "9") {
                """{"results":{"songs":{"data":[
                    {"id":"3","type":"songs","attributes":{"name":"Third","artistName":"Artist","url":"https://music.apple.com/br/song/x/3"}},
                    {"id":"4","type":"songs","attributes":{"name":"Fourth"}}
                ]}}}"""
            } else {
                """{"results":{"songs":{"data":[
                    {"id":"1","type":"songs","attributes":{"name":"First"}},
                    {"id":"2","type":"songs","attributes":{"name":"Second"}}
                ],"next":"/v1/catalog/br/search?term=Rock&types=songs&offset=9"}}}"""
            }
        }
        val page = AppleMusicWebCatalog(fake).search(
            AppleMusicWebKind.SONG, "Rock", "br", page = 2, limit = 2,
        )
        assertEquals(listOf("3", "4"), page.items.map { it.id })
        assertEquals("apple_music_web:br:song:3", page.items.first().scopedId)
        assertFalse(page.items.first().playable)
        assertNull(page.items.first().musicBrainzId)
        assertEquals(2, fake.calls.size)
        assertEquals("9", fake.calls[1].third["offset"])
        assertTrue(fake.calls.all { it.first == ProviderCapability.CATALOG_SEARCH })
    }

    @Test fun publicPreviewIsScopedToSongAndNeverPromotesFullPlayback() = runBlocking {
        val url = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview/sample.m4a"
        val fake = FakeTransport { _, _, _ ->
            """{"results":{"songs":{"data":[
                {"id":"1","type":"songs","attributes":{"name":"Sample","durationInMillis":180000,
                    "previews":[{"url":"https://example.org/sample.m4a"},{"url":"$url"}]}},
                {"id":"2","type":"songs","attributes":{"name":"Unavailable","previews":[{"url":"http://audio-ssl.itunes.apple.com/sample.m4a"}]}}
            ]}}}"""
        }
        val songs = AppleMusicWebCatalog(fake).search(AppleMusicWebKind.SONG, "Sample", "br").items
        assertEquals(url, songs[0].previewUrl)
        assertEquals(180000L, songs[0].durationMillis)
        assertFalse(songs[0].playable)
        assertNull(songs[1].previewUrl)
        assertNull(com.music.bitchord.data.catalog.provider.applemusicweb.appleMusicWebAudioPreviewUrl(
            "https://audio-ssl.itunes.apple.com@evil.example/sample.m4a"))
        assertNull(com.music.bitchord.data.catalog.provider.applemusicweb.appleMusicWebAudioPreviewUrl(
            "https://audio-ssl.itunes.apple.com/sample.m3u8"))
    }

    @Test fun exactPreviewLookupDoesNotRequireAlbumLinkageOrPromoteFullPlayback() = runBlocking {
        val url = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview/sample.m4a"
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"123","type":"songs","attributes":{"name":"Sample",
                "durationInMillis":180000,"previews":[{"url":"$url"}]}}]}"""
        }
        val catalog = AppleMusicWebCatalog(fake)
        val song = requireNotNull(catalog.songPreview("apple_music_web:br:song:123"))
        assertEquals(url,song.previewUrl)
        assertEquals(180000L,song.durationMillis)
        assertFalse(song.playable)
        assertEquals("apple_music_web:br:song:123",song.scopedId)
        assertEquals(Triple(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/br/songs/123",emptyMap<String,String>()),fake.calls.single())
        for (invalid in listOf("123","apple_music_web:BR:song:123",
            "apple_music_web:br:album:123","apple_music_web:br:song:0123",
            "apple_music_web:br:song:123/456")) {
            assertNull(catalog.songPreview(invalid))
        }
        assertEquals(1,fake.calls.size)
    }

    @Test fun exactPreviewLookupRejectsWrongOrAmbiguousResource() = runBlocking {
        val url = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview/sample.m4a"
        val valid = """{"id":"123","type":"songs","attributes":{"name":"Sample","previews":[{"url":"$url"}]}}"""
        for (body in listOf(
            """{"data":[${valid.replace("\"123\"","\"456\"")}]}""",
            """{"data":[${valid.replace("\"songs\"","\"albums\"")}]}""",
            """{"data":[$valid,$valid]}""",
            """{"data":[$valid],"errors":[]}""",
            """{"data":[]}""","{}"
        )) {
            try {
                AppleMusicWebCatalog(FakeTransport { _, _, _ -> body })
                    .songPreview("apple_music_web:br:song:123")
                fail("Invalid identity or envelope must not return a preview")
            } catch (_: AppleMusicWebProtocolException) {
                // Fail closed.
            }
        }
        val unavailable = """{"data":[{"id":"123","type":"songs","attributes":{"name":"Sample",
            "previews":[{"url":"https://example.org/sample.m4a"}]}}]}"""
        assertNull(AppleMusicWebCatalog(FakeTransport { _, _, _ -> unavailable })
            .songPreview("apple_music_web:br:song:123"))
    }

    @Test fun searchContinuesExactIssuedCursorWithoutReplayingLanding() = runBlocking {
        val fake = FakeTransport { _, _, args ->
            if (args["offset"] == "9") {
                assertEquals("3", args["limit"])
                """{"results":{"songs":{"data":[{"id":"3","type":"songs","attributes":{"name":"Third"}}]}}}"""
            } else {
                """{"results":{"songs":{"data":[{"id":"1","type":"songs","attributes":{"name":"First"}}],
                    "next":"/v1/catalog/br/search?offset=9&limit=3"}}}"""
            }
        }
        val catalog = AppleMusicWebCatalog(fake)
        val first = catalog.search(AppleMusicWebKind.SONG, "Rock", "br", limit = 24)
        val second = catalog.search(AppleMusicWebKind.SONG, "Rock", "br", page = 2,
            limit = 24, continuation = requireNotNull(first.nextCursor))
        assertEquals(2, fake.calls.size)
        assertEquals("9", fake.calls.last().third["offset"])
        assertEquals(listOf("3"), second.items.map { it.id })
        assertFalse(second.hasMore)
        assertNull(second.nextCursor)
    }

    @Test fun explicitEmptyResultsAreValidButMalformedEnvelopesAreNot() = runBlocking {
        for (body in listOf("""{"results":{}}""", """{"results":{"artists":{"data":[]}}}""")) {
            val page = AppleMusicWebCatalog(FakeTransport { _, _, _ -> body })
                .search(AppleMusicWebKind.ARTIST, "Unknown", "br")
            assertTrue(page.items.isEmpty())
            assertFalse(page.hasMore)
        }
        for (body in listOf("{}", """{"results":null}""",
            """{"results":{"artists":{}}}""", """{"results":{"songs":{"data":[]}}}""",
            """{"results":{},"errors":[{"status":"403"}]}""")) {
            try {
                AppleMusicWebCatalog(FakeTransport { _, _, _ -> body })
                    .search(AppleMusicWebKind.ARTIST, "Unknown", "br")
                fail("Malformed search envelope must not be treated as empty")
            } catch (_: AppleMusicWebProtocolException) {
                // Fail closed.
            }
        }
    }

    @Test fun searchFailsClosedOnForeignOrCrossStorefrontNextCursor() = runBlocking {
        for (cursor in listOf(
            "https://evil.example/v1/catalog/br/search?offset=9",
            "/v1/catalog/us/search?offset=9",
            "/v1/catalog/br/albums/123?offset=9",
        )) {
            val transport = FakeTransport { _, _, _ ->
                """{"results":{"songs":{"data":[{"id":"1","type":"songs","attributes":{"name":"A"}}],"next":"$cursor"}}}"""
            }
            try {
                AppleMusicWebCatalog(transport).search(
                    AppleMusicWebKind.SONG, "Rock", "br", page = 2, limit = 2,
                )
                fail("Untrusted cursor should be rejected: $cursor")
            } catch (_: AppleMusicWebProtocolException) {
                assertEquals(1, transport.calls.size)
            }
        }
    }

    @Test fun artistOverviewPreservesAppleShelvesAndStorefront() = runBlocking {
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"55","type":"artists","attributes":{"name":"Real Artist"},
                "views":{"top-songs":{"data":[{"id":"1","type":"songs","attributes":{"name":"Hit"}}]},
                "full-albums":{"data":[{"id":"100","type":"albums","attributes":{"name":"Album"}}]},
                "singles":{"data":[{"id":"101","type":"albums","attributes":{"name":"Single"}}]},
                "similar-artists":{"data":[{"id":"56","type":"artists","attributes":{"name":"Similar"}}]}
            }}]}"""
        }
        val artist = AppleMusicWebCatalog(fake).artist("55", "br")
        assertEquals("apple_music_web:br:artist:55", artist.identity.scopedId)
        assertEquals(listOf("1"), artist.topSongs.map { it.id })
        assertEquals(listOf("100"), artist.albums.map { it.id })
        assertEquals(listOf("101"), artist.singlesAndEps.map { it.id })
        assertEquals(listOf("56"), artist.similarArtists.map { it.id })
        assertEquals(ProviderCapability.EDITORIAL_DISCOGRAPHY, fake.calls.single().first)
    }

    @Test fun albumAndTrackAttributesPreserveSourceEvidenceWithoutInventingPositions() = runBlocking {
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record",
                "releaseDate":"2020-01-01","trackCount":2,"isSingle":false,"isCompilation":true},
                "relationships":{"tracks":{"data":[
                    {"id":"1","type":"songs","attributes":{"name":"One","durationInMillis":123456,
                        "discNumber":2,"trackNumber":1}},
                    {"id":"2","type":"songs","attributes":{"name":"Two","durationInMillis":-1,
                        "discNumber":0}}
                ]}}}]}"""
        }
        val result = AppleMusicWebCatalog(fake).album("10", "br")
        assertEquals("2020-01-01", result.identity.releaseDate)
        assertEquals(2, result.identity.trackCount)
        assertEquals(false, result.identity.isSingle)
        assertEquals(true, result.identity.isCompilation)
        assertEquals(123456L, result.tracks.first().durationMillis)
        assertEquals(2, result.tracks.first().discNumber)
        assertEquals(1, result.tracks.first().trackNumber)
        assertNull(result.tracks.last().durationMillis)
        assertNull(result.tracks.last().discNumber)
        assertNull(result.tracks.last().trackNumber)
    }

    @Test fun resourceDetailDoesNotAcceptAnotherAppleIdentity() = runBlocking {
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"999","type":"albums","attributes":{"name":"Wrong"}}]}"""
        }
        try {
            AppleMusicWebCatalog(fake).album("100", "br")
            fail("Other album ID must not be mapped onto the requested album")
        } catch (_: AppleMusicWebProtocolException) {
            // Fail closed.
        }
    }

    @Test fun tokenParserChoosesOnlyLiveAmpWebPlayJwt() {
        fun token(issuer: String, expiry: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"ES256"}""".toByteArray())
            val claims = encoder.encodeToString(
                """{"iss":"$issuer","exp":$expiry}""".toByteArray(),
            )
            return "$header.$claims." + "x".repeat(45)
        }
        val wrong = token("someone-else", 9_000_000_000)
        val expired = token("AMPWebPlay", 10)
        val correct = token("AMPWebPlay", 9_000_000_000)
        val selected = AppleMusicWebTokenParser.pick(
            "bundle $wrong unrelated $expired live $correct", nowEpochSeconds = 1_900_000_000,
        )
        assertEquals(correct, selected?.value)
        assertEquals(9_000_000_000L, selected?.expiresAtEpochSeconds)
        assertNull(AppleMusicWebTokenParser.pick(wrong, nowEpochSeconds = 1_900_000_000))
    }

    @Test fun providerIsRegisteredButNeverImplicitlyEnabled() {
        val inventory = ProviderCapabilityRegistry.inventory()
        val registered = inventory.filter { it.key == BuiltInProviderKeys.APPLE_MUSIC_WEB }
        assertEquals(
            setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY, ProviderCapability.CHART_MOST_PLAYED),
            registered.map { it.capability }.toSet(),
        )
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory)
        val empty = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))
        assertNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        val withChoice = ProviderCapabilityPolicy(
            descriptors, choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, enabled = true, order = 0),
                ),
            ),
        )
        empty.replace(withChoice)
        assertNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        // A standalone explicitly READY descriptor models a future validated
        // opt-in, without unblocking the production inventory prematurely.
        val ready = descriptors.map { descriptor ->
            if (descriptor.key == BuiltInProviderKeys.APPLE_MUSIC_WEB) {
                descriptor.copy(
                    capabilityReadiness = descriptor.capabilityReadiness.mapValues {
                        com.music.bitchord.data.providers.ProviderReadiness.READY
                    },
                )
            } else descriptor
        }
        empty.replace(ProviderCapabilityPolicy(ready, choices = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, enabled = true, order = 0),
            ),
        )))
        assertNotNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(empty.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(empty.open(ProviderCapability.CHART_MOST_PLAYED, BuiltInProviderKeys.APPLE_MUSIC_WEB))
    }
}
