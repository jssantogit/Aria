package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialArtistDetailPage
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleNativeDetailKind
import com.music.bitchord.ui.screens.AppleSharedDetail
import com.music.bitchord.ui.screens.appleAlbumAsSharedDetail
import com.music.bitchord.ui.screens.appleArtistAsSharedDetail
import com.music.bitchord.ui.screens.appleNativeDetailDisplayAllowed
import com.music.bitchord.ui.screens.appleNativeDetailKind
import com.music.bitchord.ui.screens.appleNativeDetailRouteKey
import com.music.bitchord.ui.screens.appleNativePendingDetail

/**
 * Collect source-native route state in one small composable outside
 * BitChordApp's already near-64-KiB Kotlin/JVM method. This is DATA/consent
 * projection, not an additional artist screen, navigator, or compositor.
 */
internal data class NativeCatalogProviderState(
    val appleAlbumDetail: UiState<AppleEditorialAlbumDetailPage>?,
    val appleArtistDetail: UiState<AppleEditorialArtistDetailPage>?,
    val appleAlbumNavigation: AppleEditorialSearchRow?,
    val appleArtistNavigation: AppleEditorialSearchRow?,
    val discogsRoute: DiscogsNativeRoute?,
    val discogsBrowseId: String?,
    val discogsDetail: UiState<DiscogsNativePage>?,
    val discogsReadAllowed: Boolean,
    val discogsNativeVisual: DiscogsNativePage?,
    val appleReadAllowed: Boolean,
    val appleSearchAllowed: Boolean,
) {
    fun appleDetail(canOpen: Boolean): NativeAppleCatalogDetail {
        val kind = if (canOpen && discogsRoute == null)
            appleNativeDetailKind(appleAlbumDetail, appleArtistDetail)
        else AppleNativeDetailKind.NONE
        val browseId = appleNativeDetailRouteKey(
            kind, appleArtistNavigation, appleAlbumNavigation,
        )
        val displayed = (when (kind) {
            AppleNativeDetailKind.ALBUM ->
                (appleAlbumDetail as? UiState.Success)?.data?.let(::appleAlbumAsSharedDetail)
                    ?: appleAlbumNavigation?.let { row ->
                        appleNativePendingDetail(
                            row, AppleNativeDetailKind.ALBUM,
                            (appleAlbumDetail as? UiState.Error)?.message,
                        )
                    }
            AppleNativeDetailKind.ARTIST ->
                (appleArtistDetail as? UiState.Success)?.data?.let(::appleArtistAsSharedDetail)
                    ?: appleArtistNavigation?.let { row ->
                        appleNativePendingDetail(
                            row, AppleNativeDetailKind.ARTIST,
                            (appleArtistDetail as? UiState.Error)?.message,
                        )
                    }
            AppleNativeDetailKind.NONE -> null
        })?.takeIf { candidate ->
            candidate.page.browseId == browseId &&
                appleNativeDetailDisplayAllowed(
                    candidate, appleReadAllowed, appleSearchAllowed,
                )
        }
        return NativeAppleCatalogDetail(kind, browseId, displayed)
    }
}

internal data class NativeAppleCatalogDetail(
    val kind: AppleNativeDetailKind,
    val browseId: String?,
    val visual: AppleSharedDetail?,
)

@Composable
internal fun rememberNativeCatalogProviderState(
    viewModel: MainViewModel,
): NativeCatalogProviderState {
    val appleAlbumDetail by viewModel.appleEditorialDetail.collectAsStateWithLifecycle()
    val appleArtistDetail by viewModel.appleEditorialArtistDetail.collectAsStateWithLifecycle()
    val appleAlbumNavigation by viewModel.appleAlbumNavigation.collectAsStateWithLifecycle()
    val appleArtistNavigation by viewModel.appleArtistNavigation.collectAsStateWithLifecycle()
    val discogsRoute by viewModel.discogsNativeCatalog.route.collectAsStateWithLifecycle()
    val discogsBrowseId by viewModel.discogsNativeCatalog.browseId.collectAsStateWithLifecycle()
    val discogsDetail by viewModel.discogsNativeCatalog.detail.collectAsStateWithLifecycle()
    // Collect the policy itself: changing a provider switch must invalidate
    // the visible detail even when none of the catalog result flows emit.
    val policySnapshot = ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val gate = ProviderRuntime.gateOrNull().takeIf { policySnapshot != null }
    val discogsReadAllowed = gate?.open(
        ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
    ) != null
    val discogsSearchAllowed = gate?.open(
        ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
    ) != null
    val discogsNativeVisual = (discogsDetail as? UiState.Success)?.data?.takeIf {
        discogsNativePageDisplayAllowed(
            it, discogsReadAllowed, discogsSearchAllowed,
        )
    }
    val appleReadAllowed = gate?.open(
        ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.ITUNES,
    ) != null
    val appleSearchAllowed = gate?.open(
        ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.ITUNES,
    ) != null
    return NativeCatalogProviderState(
        appleAlbumDetail, appleArtistDetail, appleAlbumNavigation,
        appleArtistNavigation, discogsRoute, discogsBrowseId, discogsDetail,
        discogsReadAllowed, discogsNativeVisual, appleReadAllowed, appleSearchAllowed,
    )
}
