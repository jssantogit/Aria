package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebSupplementalDetailTest {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun gate() = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(provider,setOf(editorial),ProviderReadiness.READY)),
        mapOf(editorial to listOf(ProviderChoice(provider,true,0))),
    ))
    private val artist = """{"data":[{"id":"10","type":"artists","attributes":{"name":"Artist"}}]}"""
    private fun album(id: String) = """{"data":[{"id":"$id","type":"albums","attributes":{"name":"Release $id","artistName":"Artist"}}]}"""

    @Test fun supplementalReadsUseOnlyExactArtistAndExplicitViews(): Unit = runBlocking {
        val g = gate()
        val calls = mutableListOf<String>()
        val native = AppleMusicWebNativeCatalogSession({ g }) { policy ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebTransport { _,path,_ ->
                calls += path
                when {
                    path.endsWith("/artists/10") -> artist
                    path.endsWith("/view/live-albums") -> album("21")
                    path.endsWith("/view/compilation-albums") -> album("22")
                    path.endsWith("/view/appears-on-albums") -> album("23")
                    else -> error("Unexpected request $path")
                }
            }),policy)
        }
        val result = native.sharedDetail("apple_music_web:br:artist:10") as ProviderCallResult.Success
        val detail = (result.value as AppleMusicWebOutcome.Complete).value
        assertEquals(listOf("Álbuns ao vivo","Coletâneas","Com participação em"),detail.page.sections.map { it.title })
        assertEquals(listOf("/v1/catalog/br/artists/10","/v1/catalog/br/artists/10/view/live-albums",
            "/v1/catalog/br/artists/10/view/compilation-albums","/v1/catalog/br/artists/10/view/appears-on-albums"),calls)
    }
}
