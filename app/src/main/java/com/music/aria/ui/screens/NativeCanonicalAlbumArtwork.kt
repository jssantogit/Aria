package com.music.aria.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.settings.AppSettings
import com.music.aria.data.catalog.CanonicalBrowseTrack
import com.music.aria.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.aria.ui.components.CoverPlaceholder

/**
 * One presentation policy for a verified ReleaseEntity's cover, both from the
 * root Library and from the artist's discography. A scoped track's artwork
 * loader remains authoritative for provider-authenticated artwork.
 *
 * Only a real local file/content URI or HTTP(S) release URI may be given to
 * Coil directly; an arbitrary provider key is never converted into a URL.
 * We do not submit remote source-authenticated links to the palette decoder.
 */
internal enum class NativeAlbumHeroSource { DIRECT_URI, TRACK_LOADER, PLACEHOLDER }

internal fun nativeAlbumHeroSource(
    releaseArtworkUri: String?,
    hasTrackArtwork: Boolean,
): NativeAlbumHeroSource {
    val uri = releaseArtworkUri?.trim().orEmpty()
    val local = uri.startsWith("content://", ignoreCase = true) ||
        uri.startsWith("file://", ignoreCase = true)
    val remote = uri.startsWith("https://", ignoreCase = true) ||
        uri.startsWith("http://", ignoreCase = true)
    return when {
        local -> NativeAlbumHeroSource.DIRECT_URI
        hasTrackArtwork -> NativeAlbumHeroSource.TRACK_LOADER
        remote -> NativeAlbumHeroSource.DIRECT_URI
        else -> NativeAlbumHeroSource.PLACEHOLDER
    }
}

/**
 * A track merely existing does not prove that its artwork can be rendered.
 * The canonical painter only decodes a content/file URI directly, or asks
 * the OpenSubsonic loader for an asset with a nonblank artwork revision.
 */
internal fun nativeAlbumTrackHasLoadableArtwork(track: CanonicalBrowseTrack?): Boolean {
    val revision = track?.artworkUri?.trim().orEmpty()
    if (revision.isBlank()) return false
    val localArt = revision.startsWith("content://", ignoreCase = true) ||
        revision.startsWith("file://", ignoreCase = true)
    // Match CanonicalUnifiedArtwork: local Coil content is allowed only for
    // MediaStore-backed tracks. A remote provider cannot resolve a local URI.
    // RemoteTrackArtwork additionally rejects content/file revisions.
    return if (localArt) {
        track?.availableAssets?.any { it.providerKey == "local-mediastore" } == true
    } else {
        track?.availableAssets?.any { it.providerKey.startsWith("opensubsonic:") } == true
    }
}

/** Preserve a placeholder under the remote art until it actually paints. */
@Composable
internal fun NativeCanonicalAlbumArtwork(
    releaseArtworkUri: String?,
    artworkTrack: CanonicalBrowseTrack?,
    artworkLoader: OpenSubsonicArtworkLoader,
) {
    Box(Modifier.fillMaxSize()) {
        CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
        when (nativeAlbumHeroSource(
            releaseArtworkUri = releaseArtworkUri,
            hasTrackArtwork = nativeAlbumTrackHasLoadableArtwork(artworkTrack),
        )) {
            NativeAlbumHeroSource.DIRECT_URI -> {
                val directUri = releaseArtworkUri?.trim()
                val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
                // The placeholder has the final sleeve's dimensions. Reveal
                // the decoded pixels only after Coil succeeds for THIS URI.
                var ready by remember(directUri) { mutableStateOf(false) }
                val imageAlpha by animateFloatAsState(
                    targetValue = if (ready) 1f else 0f,
                    animationSpec = tween(durationMillis = if (reduceMotion) 0 else 180),
                    label = "native_album_cover_arrival",
                )
                AsyncImage(
                    model = directUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { ready = it is AsyncImagePainter.State.Success },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                )
            }
            NativeAlbumHeroSource.TRACK_LOADER -> artworkTrack?.let { track ->
                CanonicalUnifiedArtwork(track, artworkLoader, Modifier.fillMaxSize())
            }
            NativeAlbumHeroSource.PLACEHOLDER -> Unit
        }
    }
}
