package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebCuratorLandingTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val search = ProviderCapability.CATALOG_SEARCH
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun policy(vararg caps: ProviderCapability) = ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key,setOf(search,editorial),ProviderReadiness.READY)),
        caps.associateWith { listOf(ProviderChoice(key,true,0)) },
    )
    private val detail = """{"data":[{"id":"976439585","type":"apple-curators","attributes":{"name":"Apple Music Soul e funk"},
      "relationships":{"grouping":{"data":[{"relationships":{"tabs":{"data":[{"relationships":{"children":{"data":[
      {"attributes":{"name":"Lançamentos recentes"},"relationships":{"room":{"data":[{"id":"456","type":"rooms"}]}}},
      {"attributes":{"name":"Playlists"},"relationships":{"room":{"data":[{"id":"789","type":"rooms"}]}}}
      ]}}}]}}}]}}}]}"""
    private val first = """{"data":[
        {"id":"1","type":"albums","attributes":{"name":"Combined","genreNames":["R&B/Soul"]}},
        {"id":"2","type":"albums","attributes":{"name":"Funk","genreNames":["Funk"]}}
        ],"next":"/v1/editorial/br/rooms/456/contents?offset=25"}"""
    private val second = """{"data":[
        {"id":"1","type":"albums","attributes":{"name":"Combined","genreNames":["Soul"]}},
        {"id":"3","type":"albums","attributes":{"name":"Another","genreNames":["R&B/Soul"]}}
        ]}"""

    @Test fun boundedLandingUsesEditorialProofAndDeduplicatesAcrossPages() = runBlocking {
        val requests = mutableListOf<String>()
        val gate = ProviderPolicyGate(policy(editorial))
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { cap,path,args ->
            assertEquals(editorial,cap)
            requests += path
            when (path) {
                "/v1/catalog/br/apple-curators/976439585" -> detail
                "/v1/editorial/br/rooms/456/contents" -> if (args["offset"] == "25") second else first
                else -> error("Unexpected path")
            }
        })
        val gateway = AppleMusicWebEditorialGateway(catalog,gate)
        val result = (gateway.curatedGenreLanding("soul","br") as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Complete
        val landing = result.value
        assertEquals("public_reference",landing.provenance)
        assertEquals("denied",landing.searchReason)
        assertEquals(listOf("1","3"),landing.albums.map { it.item.id })
        assertEquals(1,landing.exactLabels)
        assertEquals(1,landing.combinedLabels)
        assertEquals(3,landing.distinctCandidates)
        assertEquals(1,landing.rejectedDistinct)
        assertEquals(4,landing.sourceRows)
        assertEquals(2,landing.roomReads)
        assertFalse(landing.bounded)
        assertEquals(3,requests.size)
        assertTrue(landing.albums.all { it.room.curatorId == "976439585" })
    }

    @Test fun searchOnlyCannotReadEditorialAndRevocationSuppressesInFlightLanding() = runBlocking {
        var calls = 0
        val gate = ProviderPolicyGate(policy(search))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _,path,_ ->
            calls++
            when (path) {
                "/v1/catalog/br/apple-curators/976439585" -> detail
                "/v1/editorial/br/rooms/456/contents" -> {
                    entered.complete(Unit)
                    release.await()
                    second
                }
                else -> error("Unexpected path")
            }
        })
        val gateway = AppleMusicWebEditorialGateway(catalog,gate)
        assertEquals(ProviderCallResult.Denied,gateway.curatedGenreLanding("soul","br"))
        assertEquals(0,calls)
        gate.replace(policy(editorial))
        val reading = async { gateway.curatedGenreLanding("soul","br") }
        entered.await()
        gate.replace(policy(search))
        release.complete(Unit)
        assertEquals(ProviderCallResult.Stale,reading.await())
    }

    @Test fun unresolvedRoomIsPartialAndNeverPretendsToBeComplete() = runBlocking {
        val gate = ProviderPolicyGate(policy(editorial))
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebTransport { _,path,_ ->
            if (path.endsWith("/976439585")) detail else """{"data":[{"id":"1","type":"albums"}]}"""
        }),gate)
        val result = (gateway.curatedGenreLanding("soul","br") as ProviderCallResult.Success).value.outcome
        assertTrue(result is AppleMusicWebOutcome.Partial)
        assertEquals(1,(result as AppleMusicWebOutcome.Partial).value.roomFailures)
        assertTrue(result.value.albums.isEmpty())
    }
}
