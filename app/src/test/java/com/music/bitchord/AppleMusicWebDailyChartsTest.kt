package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebDailyChartsTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val cap = ProviderCapability.CHART_MOST_PLAYED
    private fun gate(enabled: Boolean = true) = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key, setOf(cap), ProviderReadiness.READY)),
        mapOf(cap to listOf(ProviderChoice(key, enabled, 0))),
    ))

    @Test fun directoryResolvesOnlyExactRegionAndRejectsAmbiguity() {
        val br = AppleMusicWebDailyChartRef("pl.abcdef", "Top 100: Brazil")
        val other = AppleMusicWebDailyChartRef("pl.ghijkl", "Top 25: Brazil")
        assertEquals(br, resolveAppleMusicWebDailyChart(listOf(br,other),"br"))
        assertNull(resolveAppleMusicWebDailyChart(listOf(br,br.copy(id="pl.mnopqr")),"br"))
        assertNull(resolveAppleMusicWebDailyChart(listOf(other),"br"))
        assertNull(resolveAppleMusicWebDailyChart(listOf(br),"us"))
    }

    @Test fun dailyChartRequiresPermissionAndDoesNotPublishShortChartAsComplete() = runBlocking {
        val ref = AppleMusicWebDailyChartRef("pl.abcdef","Top 100: Brazil")
        var calls = 0
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { cap, path, args ->
            calls++
            assertEquals(this@AppleMusicWebDailyChartsTest.cap, cap)
            assertEquals("/v1/catalog/br/playlists/pl.abcdef",path)
            assertEquals("tracks",args["include"])
            """{"data":[{"id":"pl.abcdef","type":"playlists","attributes":{"name":"Top 100: Brasil"},
                "relationships":{"tracks":{"data":[{"id":"5","type":"songs","attributes":{"name":"First"}}]}}}]}"""
        })
        assertEquals(ProviderCallResult.Denied, AppleMusicWebEditorialGateway(catalog,gate(false)).dailyChart("br",ref))
        assertEquals(0,calls)
        val result = (AppleMusicWebEditorialGateway(catalog,gate()).dailyChart("br",ref)
            as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Partial
        assertEquals(listOf("5"),result.value.songs.map { it.id })
        assertFalse(AppleMusicWebOperationRegistry.allows(ProviderCapability.CATALOG_SEARCH,
            "/v1/catalog/br/playlists/pl.abcdef"))
    }

    @Test fun liveDailyCharts() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AMS_DAILY_LIVE") == "1")
        val gate = gate()
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        )),gate)
        val read = gateway.dailyChartDirectory()
        val directory = ((read as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
        println("AMS_DAILY_DIRECTORY|rows=" + (directory?.size ?: 0))
        assertNotNull(directory)
        var failures = 0
        for (region in appleMusicWebDailyRegions) {
            val ref = resolveAppleMusicWebDailyChart(directory.orEmpty(),region)
            if (ref == null) {
                failures++
                println("AMS_DAILY|region=$region|status=unmapped")
                continue
            }
            val response = gateway.dailyChart(region,ref)
            val outcome = (response as? ProviderCallResult.Success)?.value?.outcome
            val count = when (outcome) {
                is AppleMusicWebOutcome.Complete -> outcome.value.songs.size
                is AppleMusicWebOutcome.Partial -> outcome.value.songs.size
                else -> 0
            }
            val status = when (outcome) {
                is AppleMusicWebOutcome.Complete -> "complete"
                is AppleMusicWebOutcome.Partial -> "partial"
                else -> when (val cause = (response as? ProviderCallResult.Failed)?.cause) {
                    is AppleMusicWebHttpException -> "http_" + cause.status
                    is AppleMusicWebProtocolException -> "protocol"
                    else -> "interrupted"
                }
            }
            if (status != "complete" || count != 100) failures++
            println("AMS_DAILY|region=$region|status=$status|songs=$count")
        }
        assertEquals(0,failures)
    }
}
