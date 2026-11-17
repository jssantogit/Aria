package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey

/**
 * Headless dispatch for existing Home. Keep this out of BitChordApp's huge
 * Compose method so new provider conditions do not inflate JVM bytecode.
 * No provider ID or data is ever converted to another provider's identity.
 */
internal object CanonicalHomeEditorialDispatch {
    fun preferred(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        force: Boolean = false,
    ) {
        when (ordered.firstOrNull()) {
            BuiltInProviderKeys.MUSICBRAINZ ->
                viewModel.loadCanonicalWorldwideReleases(force)
            BuiltInProviderKeys.ITUNES ->
                viewModel.loadAppleHomeEditorial(force)
        }
    }

    fun maybeStartApple(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        mbLoading: Boolean,
        mbHasProducts: Boolean,
    ) {
        if (BuiltInProviderKeys.ITUNES !in ordered) return
        val first = ordered.firstOrNull()
        if (first == BuiltInProviderKeys.ITUNES ||
            (first == BuiltInProviderKeys.MUSICBRAINZ &&
                !mbLoading && !mbHasProducts)
        ) viewModel.loadAppleHomeEditorial()
    }

    fun maybeStartMusicBrainz(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        appleState: UiState<List<ItunesEditorialRelease>>,
    ) {
        if (ordered.firstOrNull() == BuiltInProviderKeys.ITUNES &&
            BuiltInProviderKeys.MUSICBRAINZ in ordered &&
            editorialPrimaryMiss(appleState)
        ) viewModel.loadCanonicalWorldwideReleases()
    }
}
