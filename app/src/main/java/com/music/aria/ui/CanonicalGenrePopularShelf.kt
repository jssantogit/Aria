package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.ui.screens.CanonicalCatalogSearchRow

/** Verified tag matches and independently observed ListenBrainz popularity. */
internal data class CanonicalGenrePopularSnapshot(
    val tag: String,
    val rows: List<CanonicalCatalogSearchRow>,
    val observations: List<ListenBrainzPopularityEvidence>,
)

internal fun canonicalGenrePopularShelf(snapshot: CanonicalGenrePopularSnapshot): HomeShelf? =
    canonicalPublicPopularityShelf(
        rows = snapshot.rows,
        evidence = snapshot.observations,
        heading = "Músicas populares",
    )

/** React to capability opt-out; a cached ranking must disappear immediately. */
@Composable
internal fun rememberGenrePopularityAccess(): Boolean {
    val session = ProviderRuntime.state
    session?.collectAsStateWithLifecycle()
    return ProviderRuntime.gateOrNull()?.open(
        ProviderCapability.CHART_MOST_PLAYED,
        BuiltInProviderKeys.LISTENBRAINZ,
    ) != null
}
