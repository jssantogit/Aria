package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.appleChartTerritories
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Focused follow-up to the three failed album markets. Exercise the full
 * ten-market album class without rerunning approved Home or song tests. */
class AppleMusicWebAlbumChartLiveTest {
    @Test fun albumChartsInAllCanonicalTerritories(): Unit = runBlocking {
        assumeTrue(System.getenv("AMS_ALBUM_CHART_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val cap = ProviderCapability.CHART_MOST_PLAYED
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,setOf(cap),ProviderReadiness.READY)),
            mapOf(cap to listOf(ProviderChoice(key,true,0))),
        ))
        val native = AppleMusicWebNativeCatalogSession({gate}) { owner ->
            val real = AppleMusicWebPolicyTransport(admission=RateLimitedProviderTransport { gate })
            val observed = AppleMusicWebTransport { capability,path,args ->
                // Only market, operation and cursor presence; no payload/token.
                val market = path.split('/').getOrNull(3)
                println("AMS_CHART_LIVE|scope=request|market=$market|limit=${args["limit"]}|continued=${args.containsKey("offset")}")
                real.get(capability,path,args)
            }
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(observed),owner)
        }
        fun reason(read: ProviderCallResult<*>?): String = when(read) {
            null -> "timeout"
            ProviderCallResult.Denied -> "denied"
            ProviderCallResult.Stale -> "stale"
            is ProviderCallResult.Success -> "success"
            is ProviderCallResult.Failed -> when(val cause=read.cause) {
                is AppleMusicWebHttpException -> "http_${cause.status}"
                is AppleMusicWebProtocolException -> when(cause.message) {
                    "Partial chart page","Invalid chart membership","Overlapping chart pages",
                    "Invalid chart cursor","Incomplete chart","No verified chart rows",
                    "Duplicate chart resource","Wrong chart resource type","Invalid chart resource",
                    "Invalid chart envelope","Missing or ambiguous most-played chart",
                    "Changed chart query","Cyclic chart cursor","Untrusted Apple cursor",
                    "Chart error envelope","Missing chart results","Missing attributes",
                    "Missing title","Missing ID","Oversized response","Invalid catalog JSON" ->
                        cause.message.orEmpty().replace(' ','_')
                    else -> "protocol_other"
                }
                is IllegalArgumentException -> "invalid_argument"
                is java.io.IOException -> "io"
                else -> "unexpected"
            }
        }
        val failures = mutableListOf<String>()
        for (territory in appleChartTerritories) {
            val read = withTimeoutOrNull(180_000) { native.countryChart(territory.code,true) }
            val chart = ((read as? ProviderCallResult.Success)?.value as? AmsCountryChartResult.Albums)?.chart
            val rows = chart?.albums.orEmpty()
            val positions = rows.map { it.position }
            val valid = chart?.territory == territory && rows.size == 100 &&
                positions == (1..100).toList() &&
                rows.map { it.albumBrowseId }.distinct().size == rows.size &&
                rows.all { it.albumBrowseId.startsWith("apple_music_web:${territory.code}:album:") }
            println("AMS_CHART_LIVE|scope=result|market=${territory.code}|rows=${rows.size}|reason=${reason(read)}|ok=$valid")
            if (!valid) failures += territory.code + ":" + reason(read)
        }
        assertEquals(10,appleChartTerritories.size)
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
