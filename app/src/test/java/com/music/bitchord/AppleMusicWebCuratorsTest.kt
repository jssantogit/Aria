package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebCuratorsTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val search = ProviderCapability.CATALOG_SEARCH
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun gate(vararg caps: ProviderCapability) = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key, setOf(search,editorial), ProviderReadiness.READY)),
        caps.associateWith { listOf(ProviderChoice(key,true,0)) },
    ))

    private val curator = """{"data":[{"id":"123","type":"apple-curators","attributes":{"name":"Apple Music Metal"},
      "relationships":{"grouping":{"data":[{"relationships":{"tabs":{"data":[{"relationships":{"children":{"data":[
      {"attributes":{"name":"Lançamentos recentes"},"relationships":{"room":{"data":[{"id":"456","type":"rooms"}]}}},
      {"attributes":{"name":"Playlists"},"relationships":{"room":{"data":[{"id":"789","type":"rooms"}]}}}
      ]}}}]}}}]}}}]}"""

    @Test fun curatorKeepsNativeIdentityAndRoomProvenance() = runBlocking {
        var requests = 0
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { cap,path,args ->
            requests++
            assertEquals(editorial,cap)
            assertEquals("/v1/catalog/br/apple-curators/123",path)
            assertEquals("grouping,playlists",args["include"])
            curator
        })
        assertEquals(ProviderCallResult.Denied, AppleMusicWebEditorialGateway(catalog,gate(search)).appleCurator("123","br"))
        assertEquals(0,requests)
        val outcome = (AppleMusicWebEditorialGateway(catalog,gate(editorial)).appleCurator("123","br")
            as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Complete
        val value = outcome.value
        assertEquals("123",value.identity.id)
        assertEquals(listOf("456"),value.rooms.filter(::isAppleMusicWebCuratorReleaseRoom).map { it.id })
        assertTrue(value.rooms.all { it.curatorId == "123" && it.genreId == null })
        assertNull(resolveAppleMusicWebHomeRoom(value.rooms,"br",AppleMusicWebHomeShelf.RECENT_RELEASES))
    }

    @Test fun curatorSearchCannotSelectParentExternalOrAmbiguousIdentity() = runBlocking {
        val ref = AppleMusicWebCuratorRef("123","br","Apple Music Metal")
        assertEquals(ref,resolveAppleMusicWebGenreCurator(listOf(ref),"br","metal"))
        assertNull(resolveAppleMusicWebGenreCurator(listOf(ref),"br","hard rock"))
        assertNull(resolveAppleMusicWebGenreCurator(listOf(ref),"fr","metal"))
        assertNull(resolveAppleMusicWebGenreCurator(listOf(ref,ref.copy(id="456")),"br","metal"))
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _,_,_ ->
            """{"results":{"apple-curators":{"data":[{"id":"123","type":"curators","attributes":{"name":"Apple Music Metal"}}]}}}"""
        })
        assertTrue(AppleMusicWebEditorialGateway(catalog,gate(search)).searchAppleCurators("Metal","br") is ProviderCallResult.Failed)
    }

    @Test fun localizedCuratorNamesAndPublicReferenceRemainScoped() {
        val rnb = AppleMusicWebCuratorRef("123","br","Apple Music: R&B")
        val electronic = AppleMusicWebCuratorRef("124","br","Apple Music: eletrônica")
        assertEquals(rnb,resolveAppleMusicWebGenreCurator(listOf(rnb),"br","rnb"))
        assertEquals(electronic,resolveAppleMusicWebGenreCurator(listOf(electronic),"br","electronic"))
        assertNull(resolveAppleMusicWebGenreCurator(listOf(electronic),"br","house"))
        assertEquals("976439543",appleMusicWebPublicCuratorReference("br","metal"))
        assertNull(appleMusicWebPublicCuratorReference("us","metal"))
        assertNull(appleMusicWebPublicCuratorReference("br","folk"))
        assertTrue("R&B" in appleMusicWebCuratorQueries("rnb"))
        assertTrue("eletrônica" in appleMusicWebCuratorQueries("electronic"))
    }

    @Test fun sharedCuratorDoesNotMakeEveryAlbumBelongToBothGenres() {
        val shared = AppleMusicWebCuratorRef("982356287","br","Apple Music: pagode e samba")
        val samba = AppleMusicWebItem("1",AppleMusicWebKind.ALBUM,"br","A",genreNames=listOf("Samba"))
        val pagode = samba.copy(id="2",genreNames=listOf("Pagode"))
        assertTrue(appleMusicWebCuratorMatches(shared,"br","samba"))
        assertTrue(appleMusicWebCuratorMatches(shared,"br","pagode"))
        assertTrue(appleMusicWebCuratedAlbumMatches(shared,"samba",samba))
        assertFalse(appleMusicWebCuratedAlbumMatches(shared,"pagode",samba))
        assertTrue(appleMusicWebCuratedAlbumMatches(shared,"pagode",pagode))
        assertFalse(appleMusicWebCuratedAlbumMatches(shared,"samba",pagode))
        assertFalse(appleMusicWebCuratedAlbumMatches(shared,"pagode",samba.copy(genreNames=emptyList())))
        assertFalse(appleMusicWebCuratorMatches(shared.copy(id="999"),"br","pagode"))
        assertEquals(15,amsEditorialGenreCorpus.distinct().size)
        assertTrue(amsEditorialGenreCorpus.containsAll(listOf("soul","samba","blues","pagode","rap","trap","indie")))
    }

    @Test fun wrongCuratorIdentityOrTypeCannotPublishRooms() = runBlocking {
        for (body in listOf(curator.replace("\"id\":\"123\"","\"id\":\"124\""),
            curator.replace("\"apple-curators\"","\"curators\""))) {
            val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _,_,_ -> body })
            assertTrue(AppleMusicWebEditorialGateway(catalog,gate(editorial)).appleCurator("123","br") is ProviderCallResult.Failed)
        }
        assertFalse(AppleMusicWebOperationRegistry.allows(search,"/v1/catalog/br/apple-curators/123"))
        assertFalse(AppleMusicWebOperationRegistry.allows(editorial,"/v1/catalog/br/apple-curators/123/playlists"))
    }

    @Test fun liveKnownMetalPage() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AMS_CURATOR_LIVE") == "1")
        val gate = gate(search,editorial)
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        )),gate)
        // Exact public reference supplied by the user. Test anchor only;
        // production discovery does not hardcode this ID.
        val response = gateway.appleCurator("976439543","br")
        val page = ((response as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
        println("AMS_CURATOR|tag=metal|stage=detail|rooms=" + (page?.rooms?.size ?: 0) +
            "|reason=" + when (val cause = (response as? ProviderCallResult.Failed)?.cause) {
                is AppleMusicWebHttpException -> "http_" + cause.status
                is AppleMusicWebProtocolException -> "protocol"
                else -> if (page != null) "success" else "unavailable"
            })
        assertNotNull(page)
        assertNotNull(resolveAppleMusicWebGenreCurator(listOf(page!!.identity),"br","metal"))
        val releases = page.rooms.filter(::isAppleMusicWebCuratorReleaseRoom)
        assertTrue(releases.isNotEmpty())
        val albums = linkedSetOf<String>()
        for (room in releases.take(3)) {
            val result = gateway.roomContents(room,AppleMusicWebKind.ALBUM)
            val rows = ((result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
            println("AMS_CURATOR|tag=metal|stage=room|albums=" + (rows?.items?.size ?: 0))
            assertNotNull(rows)
            albums += rows!!.items.map { it.scopedId }
        }
        assertTrue(albums.size >= 6)
    }
}
