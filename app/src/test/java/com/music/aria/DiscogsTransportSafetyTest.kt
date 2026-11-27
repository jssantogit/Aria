package com.music.aria

import com.music.aria.data.catalog.metadata.discogs.DISCOGS_MASTER_BODY_BYTES
import com.music.aria.data.catalog.metadata.discogs.DISCOGS_RELEASE_BODY_BYTES
import com.music.aria.data.catalog.metadata.discogs.DISCOGS_STANDARD_BODY_BYTES
import com.music.aria.data.catalog.metadata.discogs.DiscogsHttpResponse
import com.music.aria.data.catalog.metadata.discogs.KtorDiscogsTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscogsTransportSafetyTest {
    @Test fun readOnlyTransportSendsIdentifiedRequestWithoutAuthenticationOrMarketplaceCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":17,"title":"Album"}"""))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/17", "BitChord/Test")
            assertEquals(200, response.status)
            val request = server.takeRequest()
            assertEquals("/releases/17", request.path)
            assertEquals("BitChord/Test", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.getHeader("Accept")!!.contains("discogs"))
        }
    }

    @Test fun rejectsArbitraryUserURLsAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "/users/private"))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(302, transport.get("releases/17", "BitChord/Test").status)
            assertEquals(1, server.requestCount)
            assertTrue(runCatching { transport.get("../users/private", "BitChord/Test") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedBodyOrServerAnnouncedLengthIsRejectedWithoutParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("a".repeat(DISCOGS_RELEASE_BODY_BYTES + 1)))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/42", "BitChord/Test")
            assertEquals(413, response.status)
            assertTrue(response.localBodyLimitExceeded)
            assertTrue(response.body.isEmpty())
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setChunkedBody("a".repeat(DISCOGS_RELEASE_BODY_BYTES + 1), 8_192))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/42", "BitChord/Test")
            assertEquals(413, response.status)
            assertTrue(response.localBodyLimitExceeded)
            assertTrue(response.body.isEmpty())
        }
    }

    @Test fun exactDetailBudgetsRemainBoundedAndVersionBudgetIsUnchanged() = runBlocking {
        MockWebServer().use { server ->
            val payload = "a".repeat(DISCOGS_STANDARD_BODY_BYTES + 1)
            server.enqueue(MockResponse().setChunkedBody(payload, 8_192))
            server.enqueue(MockResponse().setBody(payload))
            server.enqueue(MockResponse().setChunkedBody(payload, 8_192))
            server.enqueue(MockResponse().setBody("a".repeat(DISCOGS_RELEASE_BODY_BYTES)))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val release = transport.get("releases/42", "BitChord/Test")
            assertEquals(200, release.status)
            assertEquals(payload, release.body)
            assertFalse(release.localBodyLimitExceeded)
            val master = transport.get("masters/42", "BitChord/Test")
            assertEquals(200, master.status)
            assertEquals(payload, master.body)
            assertFalse(master.localBodyLimitExceeded)
            for (path in listOf("masters/42/versions?page=1&per_page=8")) {
                val result = transport.get(path, "BitChord/Test")
                assertEquals(413, result.status)
                assertTrue(result.localBodyLimitExceeded)
                assertTrue(result.body.isEmpty())
            }
            val exactBoundary = transport.get("releases/42", "BitChord/Test")
            assertEquals(200, exactBoundary.status)
            assertEquals(DISCOGS_RELEASE_BODY_BYTES, exactBoundary.body.length)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun masterRejectsDeclaredAndChunkedPayloadBeyondDetailCeiling() = runBlocking {
        MockWebServer().use { server ->
            val oversized = "a".repeat(DISCOGS_MASTER_BODY_BYTES + 1)
            server.enqueue(MockResponse().setBody(oversized))
            server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
            server.enqueue(MockResponse().setBody("a".repeat(DISCOGS_MASTER_BODY_BYTES)))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            repeat(2) {
                val response = transport.get("masters/42", "BitChord/Test")
                assertEquals(413, response.status)
                assertTrue(response.localBodyLimitExceeded)
                assertTrue(response.body.isEmpty())
            }
            val exact = transport.get("masters/42", "BitChord/Test")
            assertEquals(200, exact.status)
            assertEquals(DISCOGS_MASTER_BODY_BYTES, exact.body.length)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun upstream413IsNotReportedAsLocalBodyLimit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(413).setBody(""))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/42", "BitChord/Test")
            assertEquals(413, response.status)
            assertFalse(response.localBodyLimitExceeded)
        }
    }

    @Test fun chunkedUtf8PayloadIsDecodedAfterFullByteAssembly() = runBlocking {
        MockWebServer().use { server ->
            val payload = """{"id":17,"title":"日本語 한국어 中文"}"""
            // Individual chunks may split multi-byte Unicode code points.
            server.enqueue(MockResponse().setChunkedBody(payload, 1))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/17", "BitChord/Test")
            assertEquals(200, response.status)
            assertEquals(payload, response.body)
        }
    }

    @Test fun acceptsBoundedNumericRetryAfterWithoutRetryLoop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "15"))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(DiscogsHttpResponse(429, "", 15),
                transport.get("releases/42", "BitChord/Test"))
            assertEquals(1, server.requestCount)
        }
    }
}
