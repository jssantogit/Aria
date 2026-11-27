package com.music.aria

import com.music.aria.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.aria.data.catalog.metadata.MusicBrainzClient
import com.music.aria.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.aria.data.catalog.metadata.MusicBrainzTransport
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability.CHART_MOST_PLAYED
import com.music.aria.data.providers.ProviderCapability.EDITORIAL_DISCOGRAPHY
import com.music.aria.data.providers.ProviderCapabilityPolicy
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderDescriptor
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.data.providers.ProviderSingleFlight
import com.music.aria.ui.loadMusicBrainzGenreRecordings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalGenreRecordingsTest {
    @Test fun musicBrainzAdmitsOnlyExactTagWithListenBrainzDisabled(): Unit = runBlocking {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val lb = BuiltInProviderKeys.LISTENBRAINZ
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(mb, setOf(EDITORIAL_DISCOGRAPHY), ProviderReadiness.READY),
                ProviderDescriptor(lb, setOf(CHART_MOST_PLAYED), ProviderReadiness.READY),
            ),
            choices = mapOf(
                EDITORIAL_DISCOGRAPHY to listOf(ProviderChoice(mb, enabled = true, order = 0)),
                CHART_MOST_PLAYED to listOf(ProviderChoice(lb, enabled = false, order = 0)),
            ),
        ))
        val ticket = checkNotNull(gate.open(EDITORIAL_DISCOGRAPHY, mb))
        val exact = "11111111-1111-4111-8111-111111111111"
        val neighbor = "22222222-2222-4222-8222-222222222222"
        val search = MusicBrainzCatalogSearch(
            MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String {
                        assertEquals("recording", path)
                        assertEquals("tag:\"death metal\" AND status:official", parameters["query"])
                        return """
                            {"count":2,"offset":0,"recordings":[
                              {"id":"$exact","title":"Exact song","video":false,
                               "artist-credit":[{"name":"Test Band"}],
                               "tags":[{"name":"death metal","count":1}],
                               "releases":[{"id":"33333333-3333-4333-8333-333333333333",
                                "status":"Official","release-group":{"primary-type":"Album"}}]},
                              {"id":"$neighbor","title":"Neighbor song","video":false,
                               "artist-credit":[{"name":"Test Band"}],
                               "tags":[{"name":"melodic death metal","count":1}],
                               "releases":[{"id":"44444444-4444-4444-8444-444444444444",
                                "status":"Official","release-group":{"primary-type":"Album"}}]}
                            ]}
                        """.trimIndent()
                    }
                },
                rateLimiter = MusicBrainzRateLimiter(),
                admission = { gate },
                singleFlight = ProviderSingleFlight(),
            ),
            capability = EDITORIAL_DISCOGRAPHY,
        )

        val result = loadMusicBrainzGenreRecordings(
            tag = "death metal",
            gate = gate,
            ticket = ticket,
            searchRecordings = search::searchRecordingsForTag,
            isCurrent = { true },
            fetchPopularity = { throw AssertionError("ListenBrainz must stay disabled") },
        )

        assertEquals(listOf(exact), result?.rows.orEmpty().map { it.musicBrainzId })
        assertTrue(result?.observations.orEmpty().isEmpty())
    }
}
