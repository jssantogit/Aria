package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.discogsEditorialQueriesForTag
import com.music.bitchord.ui.discogsHomeDiscoveryTags
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional network observation, never a parity or render gate.
 * Reuses verified source evidence without additional Discogs hydration.
 * No IDs, UPCs, names, URLs, payloads or credentials leave the test.
 */
class DiscogsEditorialArtworkLiveObservationTest {
    @Test fun observeExactAppleCoverageOfBoundedSourceSample() = runBlocking {
        assumeTrue("Explicit isolated live observation only",
            System.getenv("IDM_DISCOGS_ARTWORK_LIVE") == "1")
        val dir = File(System.getenv("IDM_CATALOG_METRICS_DIR")
            ?: "build/idm-discogs-artwork-live")
        check(dir.exists() || dir.mkdirs())
        val token = System.getenv("IDM_CATALOG_DISCOGS_TOKEN")
            ?.trim()?.takeIf(::isValidDiscogsSearchToken)
        val counts = linkedMapOf<String, Int>()
        fun bump(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        fun write(status: String) {
            File(dir, "discogs-artwork-live.txt").writeText(buildString {
                appendLine("status=$status")
                appendLine("scope=bounded_source_sample_exact_apple_product_not_render")
                counts.toSortedMap().forEach { (key, count) -> appendLine("$key=$count") }
            })
        }
        if (token == null) {
            write("token_not_configured")
            return@runBlocking
        }
        val discogs = BuiltInProviderKeys.DISCOGS
        val apple = BuiltInProviderKeys.ITUNES
        val search = ProviderCapability.CATALOG_SEARCH
        val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
        val artwork = ProviderCapability.RELEASE_ARTWORK
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(discogs, setOf(search, editorial), ProviderReadiness.READY),
                ProviderDescriptor(apple, setOf(artwork), ProviderReadiness.READY),
            ),
            choices = mapOf(
                search to listOf(ProviderChoice(discogs, true, 0)),
                editorial to listOf(ProviderChoice(discogs, true, 0)),
                artwork to listOf(ProviderChoice(apple, true, 0)),
            ),
        ))
        val admission = RateLimitedProviderTransport { gate }
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { token }, admission = admission,
            editions = DiscogsNativeEditionClient(DiscogsClient(
                enabled = true, capability = editorial,
                admission = admission, tokenProvider = { token },
            )),
        )
        val client = ItunesCatalogClient(transport = KtorItunesCatalogTransport(
            admission = admission,
        ))
        val resolver = DiscogsAppleArtworkResolver(
            lookup = AppleArtworkUpcSource { upc, country ->
                bump("apple_lookup_calls")
                client.lookupUpcForArtwork(upc, country)
            },
            gateProvider = { gate },
        )
        val masters = mutableSetOf<Long>()
        val releases = mutableSetOf<Long>()
        val pending = Channel<Long>(capacity = 6)
        val started = System.nanoTime()
        val completed = withTimeoutOrNull(180_000L) {
            val consumer = launch {
                for (id in pending) {
                    val record = source.cachedArtworkEvidenceForRelease(id) {
                        bump("queued_" + it.name.lowercase(java.util.Locale.ROOT))
                    } ?: continue
                    bump("queued_eligible")
                    var reason = DiscogsArtworkOutcome.NO_EXACT_APPLE_PRODUCT
                    val resolved = try {
                        withTimeoutOrNull(12_000L) {
                            Unit to resolver.resolve(record, onRejected = { reason = it })
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        bump("lookup_error")
                        continue
                    }
                    when {
                        resolved == null -> bump("lookup_timeout")
                        source.cachedArtworkEvidenceForRelease(id) != record ->
                            bump("lookup_evidence_changed")
                        resolved.second == null -> bump("lookup_" +
                            reason.name.lowercase(java.util.Locale.ROOT))
                        else -> bump("exact_apple_product_verified")
                    }
                }
            }
            try {
                // Small cross-genre sample; intentionally not a full catalog scan.
                for (tag in discogsHomeDiscoveryTags.take(3)) {
                    val query = discogsEditorialQueriesForTag(tag).firstOrNull() ?: continue
                    bump("source_queries")
                    val result = source.discoverDistinct(
                        query, maxReleases = 2, knownMasterIds = masters.toSet(),
                        onVerified = { candidate ->
                            masters += candidate.masterId
                            val id = candidate.browseId.substringAfterLast(':').toLongOrNull()
                            if (id != null && releases.add(id)) {
                                bump("distinct_verified_releases")
                                val evidence = source.cachedArtworkEvidenceForRelease(id) {
                                    bump("arrival_" + it.name.lowercase(java.util.Locale.ROOT))
                                }
                                if (evidence != null) {
                                    bump("arrival_eligible")
                                    pending.send(id)
                                }
                            }
                        },
                    )
                    when (result) {
                        is DiscogsEditorialResult.Verified -> bump("source_verified_queries")
                        is DiscogsEditorialResult.Throttled -> {
                            bump("source_throttled")
                            break
                        }
                        DiscogsEditorialResult.Unauthorized, DiscogsEditorialResult.Disabled -> {
                            bump("source_denied")
                            break
                        }
                        else -> bump("source_interruption")
                    }
                }
            } finally {
                pending.close()
            }
            consumer.join()
            true
        }
        for (id in releases) {
            val evidence = source.cachedArtworkEvidenceForRelease(id) {
                bump("end_" + it.name.lowercase(java.util.Locale.ROOT))
            }
            if (evidence != null) bump("end_eligible")
        }
        counts["elapsed_ms"] = ((System.nanoTime() - started) / 1_000_000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        assertTrue(releases.size <= 6)
        assertTrue((counts["apple_lookup_calls"] ?: 0) <= releases.size)
        assertTrue((counts["exact_apple_product_verified"] ?: 0) <=
            (counts["apple_lookup_calls"] ?: 0))
        write(if (completed == true) "sample_completed" else "timeout_partial")
    }
}
