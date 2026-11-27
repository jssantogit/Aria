package com.music.aria.data.catalog.metadata.discogs

import com.music.aria.diagnostics.DiagnosticCategory
import com.music.aria.diagnostics.Diagnostics

/** Terminal stage per candidate examination, not a count of unique albums.
 * Never export source IDs, barcodes, names, credentials, URLs or query text. */
internal enum class DiscogsArtworkOutcome {
    NO_CURRENT_EVIDENCE,
    EXPIRED_EVIDENCE,
    AMBIGUOUS_EVIDENCE,
    INELIGIBLE_EDITION,
    POLICY_DENIED,
    EVIDENCE_CHANGED,
    LOOKUP_TIMEOUT,
    LOOKUP_UNAVAILABLE,
    INCOMPLETE_APPLE_RESPONSE,
    NO_EXACT_APPLE_PRODUCT,
    NO_PROMINENT_IMAGE,
    VERIFIED_PROMINENT,
    VERIFIED_REUSED,
    ERROR,
}

internal class DiscogsArtworkBatchReport {
    private val counts = mutableMapOf<DiscogsArtworkOutcome, Int>()

    fun record(outcome: DiscogsArtworkOutcome) {
        counts[outcome] = (counts[outcome] ?: 0) + 1
    }

    fun metadata(): Map<String, String> = buildMap {
        put("scope", "candidate_examinations_not_unique_albums")
        for (outcome in DiscogsArtworkOutcome.entries)
            put(outcome.name.lowercase(java.util.Locale.ROOT),
                (counts[outcome] ?: 0).toString())
    }

    fun emit(durationNanos: Long) {
        if (counts.isEmpty()) return
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "discogs_editorial_artwork_batch_v1",
                metadata = metadata(),
                durationNanos = durationNanos.coerceAtLeast(0),
            )
        }
    }
}

/** A snapshot of exact visible release keys, deduplicated within one surface.
 * Publication is not a Coil load or GPU render and must not be called either. */
internal fun discogsArtworkCoverageMetadata(
    home: Boolean,
    visibleReleaseCount: Int,
    promotedReleaseCount: Int,
    attemptedReleaseCount: Int,
    budgetLimited: Boolean,
): Map<String, String> = mapOf(
    "surface" to if (home) "home" else "genre",
    "distinctVisibleReleases" to visibleReleaseCount.coerceAtLeast(0).toString(),
    "distinctPromotedReleases" to promotedReleaseCount
        .coerceIn(0, visibleReleaseCount.coerceAtLeast(0)).toString(),
    "attemptedInEpoch" to attemptedReleaseCount.coerceIn(0, 80).toString(),
    "budgetLimited" to budgetLimited.toString(),
    "scope" to "published_promotions_not_rendered_images",
)

internal fun recordDiscogsArtworkCoverage(
    home: Boolean,
    visibleReleaseCount: Int,
    promotedReleaseCount: Int,
    attemptedReleaseCount: Int,
    budgetLimited: Boolean,
) {
    runCatching {
        Diagnostics.event(
            category = DiagnosticCategory.CATALOG,
            name = "discogs_editorial_artwork_coverage_v1",
            metadata = discogsArtworkCoverageMetadata(
                home, visibleReleaseCount, promotedReleaseCount,
                attemptedReleaseCount, budgetLimited,
            ),
        )
    }
}
