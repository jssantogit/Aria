package com.music.bitchord.playback

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AmsPreviewResolverTest {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val audio = ProviderCapability.AUDIO_PLAYBACK
    private val route = requireNotNull(AmsPreviewRoute.fromScopedId("apple_music_web:br:song:123"))
    private val url = "https://audio-ssl.itunes.apple.com/sample.m4a"
    private fun policy(vararg caps: ProviderCapability) = ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(provider,setOf(editorial,audio),ProviderReadiness.READY)),
        caps.associateWith { listOf(ProviderChoice(provider,true,0)) })
    private fun response() =
        """{"data":[{"id":"123","type":"songs","attributes":{"name":"Sample","previews":[{"url":"$url"}]}}]}"""
    private fun resolver(gate: ProviderPolicyGate, transport: AppleMusicWebTransport) =
        AmsPreviewResolver({ gate }, { AppleMusicWebEditorialGateway(AppleMusicWebCatalog(transport),it) })

    @Test fun logicalRouteContainsOnlyExactSourceIdentity() {
        assertEquals(route,AmsPreviewRoute.decode(route.uri))
        assertEquals("bitchord://preview/br/123",route.uri)
        assertNull(AmsPreviewRoute.decode(route.uri+"?url=https://example.org"))
        assertNull(AmsPreviewRoute.decode("bitchord://preview/br/0123"))
        assertNull(AmsPreviewRoute.fromScopedId("itunes:br:song:123"))
        assertTrue(AmsPreviewRoute.owns(route.uri+"?invalid=1"))
        assertFalse(AmsPreviewRoute.owns("https://audio-ssl.itunes.apple.com/sample.m4a"))
    }

    @Test fun bothCapabilitiesAreRequiredBeforeLookup() = runBlocking {
        for (caps in listOf(emptyArray<ProviderCapability>(),arrayOf(editorial),arrayOf(audio))) {
            var calls = 0
            val resolver = resolver(ProviderPolicyGate(policy(*caps)), AppleMusicWebTransport { _,_,_ ->
                calls++; response()
            })
            try { resolver.resolve(route); fail("Missing permission") }
            catch (_: IOException) { assertEquals(0,calls) }
        }
    }

    @Test fun verifiedSelectionLosesByteAuthorityOnEitherRevocation() = runBlocking {
        for (remaining in listOf(editorial,audio)) {
            val gate = ProviderPolicyGate(policy(editorial,audio))
            val selected = resolver(gate,AppleMusicWebTransport { cap,path,args ->
                assertEquals(editorial,cap)
                assertEquals("/v1/catalog/br/songs/123",path)
                assertTrue(args.isEmpty())
                response()
            }).resolve(route)
            assertEquals(url,selected.url)
            assertTrue(selected.lease.allowed())
            var revoked = false
            val unregister = selected.lease.onRevoke { revoked = true }
            gate.replace(policy(remaining))
            assertFalse(selected.lease.allowed())
            assertTrue(revoked)
            unregister()
        }
    }

    @Test fun audioRevocationDuringLookupCannotReturnSelection() = runBlocking {
        val gate = ProviderPolicyGate(policy(editorial,audio))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = resolver(gate,AppleMusicWebTransport { _,_,_ ->
            entered.complete(Unit); release.await(); response()
        })
        val request = async {
            try { resolver.resolve(route); false } catch (_: IOException) { true }
        }
        entered.await()
        gate.replace(policy(editorial))
        release.complete(Unit)
        assertTrue(request.await())
    }
}
