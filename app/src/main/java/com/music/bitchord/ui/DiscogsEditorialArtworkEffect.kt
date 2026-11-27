package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.ui.screens.EditorialShelfArtwork
import com.music.bitchord.ui.screens.appleProminentArtworkOrNull
import kotlinx.coroutines.flow.collect

/** Artwork is scoped to the visible native surface, never persisted in its items.
 * Incremental editorial emissions and pagination must not restart in-flight
 * lookup or consume the budget again for the same edition. */
@Composable
internal fun rememberDiscogsEditorialArtwork(
    session: DiscogsNativeCatalogSession,
    active: Boolean,
    surfaceKey: String,
    state: UiState<List<HomeShelf>>,
): Map<String, EditorialShelfArtwork> {
    val policy = ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val gate = ProviderRuntime.gateOrNull()
    val ready = active && state is UiState.Success && policy != null
    val ticket = if (ready)
        gate?.open(ProviderCapability.RELEASE_ARTWORK, BuiltInProviderKeys.ITUNES)
    else null
    val search = if (ready)
        gate?.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS)
    else null
    val editorial = if (ready)
        gate?.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS)
    else null
    val ids = remember(state) {
        (state as? UiState.Success)?.data.orEmpty().asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { discogsNativeDetailRouteKey(DiscogsNativeRoute.RELEASE, it.browseId) }
            .distinct().toList()
    }
    val currentIds by rememberUpdatedState(ids)
    val currentActive by rememberUpdatedState(active)
    var artwork by remember(session, surfaceKey, gate, ticket, search, editorial, ready) {
        mutableStateOf<Map<String, EditorialShelfArtwork>>(emptyMap())
    }
    LaunchedEffect(session, surfaceKey, gate, ticket, search, editorial, ready) {
        if (gate == null || ticket == null || search == null || editorial == null)
            return@LaunchedEffect
        fun current(): Boolean = currentActive && ProviderRuntime.gateOrNull() === gate &&
            gate.accept(ticket) && gate.accept(search) && gate.accept(editorial)
        val attempted = mutableSetOf<String>()
        fun reportCoverage() {
            val visible = currentIds
            com.music.bitchord.data.catalog.metadata.discogs.recordDiscogsArtworkCoverage(
                home = surfaceKey == "home",
                visibleReleaseCount = visible.size,
                promotedReleaseCount = visible.count { it in artwork },
                attemptedReleaseCount = attempted.size,
                budgetLimited = attempted.size >= 80 && visible.any { it !in attempted },
            )
        }
        try {
        snapshotFlow { currentIds }.collect { incoming ->
            // Recheck misses on a later editorial emission (fresh evidence
            // may have arrived), but never rescan them in every batch of
            // this same emission.
            val examined = mutableSetOf<String>()
            // Proofs and attempts remain bounded within this surface epoch;
            // the returned projection hides absent IDs without spending a
            // second request if a subsequent editorial emission restores one.
            // A batch never spends more than sixteen lookups. Continue with
            // later eligible items without waiting for another recomposition;
            // stop on exhaustion, navigation, or the bounded surface budget.
            while (current() && attempted.size < 80) {
                val before = attempted.size
                session.resolveEditorialArtworkBatch(
                    browseIds = incoming.filterNot { it in attempted || it in examined },
                    onExamined = { examined += it },
                    maximumAttempts = 80 - before,
                    stillCurrent = { current() },
                    onAttempt = { attempted += it },
                    onVerified = { id, proof ->
                        val badge = appleApprovedBadgeForStoreUrl(proof.storeUrl)
                        if (id in currentIds && current() && badge != null &&
                            appleProminentArtworkOrNull(proof.artworkUrl) != null
                        ) artwork = artwork + (id to EditorialShelfArtwork(
                            proof.artworkUrl, proof.storeUrl, badge,
                        ))
                    },
                )
                if (attempted.size == before) break
            }
            reportCoverage()
        }
        } finally {
            reportCoverage()
        }
    }
    return if (ready && ticket != null && search != null && editorial != null &&
        gate != null && gate.accept(ticket) && gate.accept(search) && gate.accept(editorial)
    ) artwork.filterKeys { it in ids } else emptyMap()
}
