package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.model.UiState
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.ui.screens.AppleEditorialAlbumDetailPage
import com.music.aria.ui.screens.AppleEditorialArtistDetailPage
import com.music.aria.ui.screens.AppleEditorialSearchRow
import com.music.aria.ui.screens.AppleNativeDetailKind
import com.music.aria.ui.screens.AppleSharedDetail
import com.music.aria.ui.screens.appleAlbumAsSharedDetail
import com.music.aria.ui.screens.appleArtistAsSharedDetail
import com.music.aria.ui.screens.appleNativeDetailDisplayAllowed
import com.music.aria.ui.screens.appleNativeDetailKind
import com.music.aria.ui.screens.appleNativeDetailRouteKey
import com.music.aria.ui.screens.appleNativePendingDetail

/**
 * Collect source-native route state in one small composable outside
 * AriaApp's already near-64-KiB Kotlin/JVM method. This is DATA/consent
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
    val gate = ProviderRuntime.gateOrNull()
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
