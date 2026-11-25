package com.music.aria.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.aria.R
import com.music.aria.ui.components.PAGE_GUTTER
import com.music.aria.ui.components.DetailActionLayoutMetrics
import com.music.aria.ui.components.SourceQuality
import com.music.aria.ui.components.SourceQualityBadge
import com.music.aria.ui.components.thumbnailBorder
import com.music.aria.ui.components.topBarContentPadding
import com.music.aria.ui.haptics.Haptic
import com.music.aria.ui.icons.AriaIcons
import com.music.aria.ui.theme.ArtworkPalette

/**
 * Source-independent native release UI. The old DetailPage remains only in a
 * legacy adapter; albums from Library, online catalog and playlists share
 * these original controls, artwork slots, quality/rating and contextual plus.
 */
internal val HEADER_GUTTER = PAGE_GUTTER + 8.dp
internal val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val SLEEVE_SHADOW = 18.dp
private val RELEASE_SIDE_BUTTON = 50.dp
internal const val ALBUM_SLEEVE_FRACTION = 0.66f
/** Identical album/playlist artwork framing, regardless of provider or route.
 * Limit height in landscape, never derive a thumbnail size from its source. */
internal fun nativeReleaseSleeveSize(viewportWidth: Dp, viewportHeight: Dp): Dp =
    (viewportWidth * ALBUM_SLEEVE_FRACTION).coerceAtMost(viewportHeight * 0.6f)

internal fun nativeReleaseSideButtonSize(compactForAlbum: Boolean): Dp =
    if (compactForAlbum) DetailActionLayoutMetrics.albumSide else RELEASE_SIDE_BUTTON
private val RELEASE_ACTION_GAP = 14.dp
private val RELEASE_ACTIONS_MAX_WIDTH = 420.dp

@Composable
internal fun NativeReleaseActionRow(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    isLocal: Boolean,
    canPlay: Boolean = true,
    canShuffle: Boolean = true,
    isSaved: Boolean? = null,
    onToggleLibrary: (() -> Unit)? = null,
    // Playlist pages share release geometry, but the trailing action can queue.
    onAddToQueue: (() -> Unit)? = null,
    // Reserved for a future pin on the unified Library landing page.
    // Never attach playlist membership to the hero +.
    reservePinAction: Boolean = false,
    pinTargetLabel: String = "álbum",
    isPinnedInLibrary: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    compactForAlbum: Boolean = false,
) {
    // Album and playlist details follow one three-control layout when the
    // source owns the add/pin action. Local albums retain their genuine
    // Play/Shuffle two-pill actions, at Library > Songs dimensions.
    val compactRelease = compactForAlbum && !isLocal
    val localCompactAlbum = compactForAlbum && isLocal
    val sideButton = nativeReleaseSideButtonSize(compactForAlbum)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactPlayWidth = DetailActionLayoutMetrics.albumPlayWidth(maxWidth)
        Row(
        modifier = Modifier
            .widthIn(max = RELEASE_ACTIONS_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = if (localCompactAlbum) PAGE_GUTTER else HEADER_GUTTER),
        horizontalArrangement = when {
            compactRelease -> Arrangement.spacedBy(
                DetailActionLayoutMetrics.albumGap, Alignment.CenterHorizontally,
            )
            localCompactAlbum -> Arrangement.spacedBy(
                DetailActionLayoutMetrics.libraryCategoryGap, Alignment.CenterHorizontally,
            )
            else -> Arrangement.spacedBy(RELEASE_ACTION_GAP)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLocal) {
            ActionPill(
                icon = AriaIcons.Play,
                label = stringResource(R.string.play),
                onClick = onPlay,
                filled = true,
                enabled = canPlay,
                size = sideButton,
                modifier = Modifier.weight(1f)
                    .let { if (localCompactAlbum) it.testTag("native-local-album-play-pill") else it },
            )
            ActionPill(
                icon = AriaIcons.Shuffle,
                label = stringResource(R.string.shuffle),
                onClick = onShuffle,
                filled = false,
                enabled = canShuffle,
                size = sideButton,
                modifier = Modifier.weight(1f),
            )
        } else {
            CircleIconButton(
                icon = AriaIcons.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
                onClick = onShuffle,
                haptic = Haptic.Resume,
                size = sideButton,
                enabled = canShuffle,
            )
            ActionPill(
                icon = AriaIcons.Play,
                label = stringResource(R.string.play),
                onClick = onPlay,
                filled = true,
                enabled = canPlay,
                size = sideButton,
                // One responsive play width for album and playlist heroes
                // across online, offline, Navidrome and public discovery.
                // Matches the Library > Songs pill when it fits beside circles.
                modifier = if (compactRelease) {
                    Modifier.width(compactPlayWidth).testTag(
                        if (pinTargetLabel == "playlist") "native-compact-playlist-play-pill"
                        else "native-compact-album-play-pill",
                    )
                } else Modifier.weight(1f),
            )
            CircleIconButton(
                icon = if (reservePinAction && isPinnedInLibrary ||
                    !reservePinAction && onAddToQueue == null && isSaved == true
                ) AriaIcons.Check else AriaIcons.Plus,
                contentDescription = if (onAddToQueue != null) {
                    "Adicionar à fila"
                } else if (reservePinAction) {
                    if (isPinnedInLibrary) "Desafixar $pinTargetLabel da Biblioteca"
                    else if (onTogglePin == null) "Fixar $pinTargetLabel na Biblioteca (em breve)"
                    else "Fixar $pinTargetLabel na Biblioteca"
                } else stringResource(
                    if (isSaved == true) R.string.remove_from_library
                    else R.string.add_to_library,
                ),
                onClick = {
                    when {
                        onAddToQueue != null -> onAddToQueue()
                        reservePinAction -> onTogglePin?.invoke()
                        else -> onToggleLibrary?.invoke()
                    }
                },
                haptic = when {
                    onAddToQueue != null -> Haptic.Resume
                    reservePinAction && isPinnedInLibrary -> Haptic.ToggleOff
                    !reservePinAction && isSaved == true -> Haptic.ToggleOff
                    else -> Haptic.ToggleOn
                },
                size = sideButton,
                enabled = when {
                    onAddToQueue != null -> true
                    reservePinAction -> onTogglePin != null
                    else -> onToggleLibrary != null
                },
            )
        }
        }
    }
}

/**
 * A saved release may have metadata but no playable MediaAssets. A real pin
 * capability must remain accessible without implying a Play/Shuffle action.
 * Album and playlist details share the same first-party circular control.
 */
@Composable
internal fun NativeReleasePinOnlyAction(
    targetLabel: String,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    tag: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        CircleIconButton(
            icon = if (isPinned) AriaIcons.Check else AriaIcons.Plus,
            contentDescription = if (isPinned)
                "Desafixar $targetLabel da Biblioteca" else "Fixar $targetLabel na Biblioteca",
            onClick = onTogglePin,
            haptic = if (isPinned) Haptic.ToggleOff else Haptic.ToggleOn,
            size = if (targetLabel == "álbum") nativeReleaseSideButtonSize(true)
                else RELEASE_SIDE_BUTTON,
        )
    }
}

/** A playlist with no playable recordings is still pinnable. */
@Composable
internal fun NativePlaylistHeroActions(
    canPlay: Boolean,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    if (canPlay) {
        Box(Modifier.fillMaxWidth().testTag("playlist-hero-playback-actions")) {
            NativeReleaseActionRow(
                onPlay = onPlay,
                onShuffle = onShuffle,
                isLocal = false,
                compactForAlbum = true,
                reservePinAction = true,
                pinTargetLabel = "playlist",
                isPinnedInLibrary = isPinned,
                onTogglePin = onTogglePin,
            )
        }
    } else {
        NativeReleasePinOnlyAction(
            targetLabel = "playlist",
            isPinned = isPinned,
            onTogglePin = onTogglePin,
            tag = "playlist-hero-pin-only",
        )
    }
}

/**
 * The exact same rule for album details. Optional onTogglePin expresses a real
 * capability; a metadata-only album without one must not fabricate an action.
 */
@Composable
internal fun NativeAlbumHeroActions(
    canPlay: Boolean,
    isPinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    if (canPlay) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().testTag("album-hero-playback-actions")) {
            NativeReleaseActionRow(
                onPlay = onPlay,
                onShuffle = onShuffle,
                isLocal = false,
                reservePinAction = true,
                pinTargetLabel = "álbum",
                isPinnedInLibrary = isPinned,
                onTogglePin = onTogglePin,
                compactForAlbum = true,
            )
        }
    } else if (onTogglePin != null) {
        Spacer(Modifier.height(18.dp))
        NativeReleasePinOnlyAction(
            targetLabel = "álbum",
            isPinned = isPinned,
            onTogglePin = onTogglePin,
            tag = "album-hero-pin-only",
        )
    }
}

@Suppress("UNUSED_PARAMETER") // Rating/editing are owned by the top toolbar, kept for existing caller API.
@Composable
internal fun NativeReleaseHeaderLayout(
    title: String,
    credit: String,
    meta: String,
    palette: ArtworkPalette,
    rating: Double? = null,
    quality: SourceQuality? = null,
    onRate: (() -> Unit)? = null,
    sleeveSize: Dp,
    artwork: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    topInset: Dp? = null,
    onCreditClick: (() -> Unit)? = null,
    inlineAlbumMetadata: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(top = topInset ?: topBarContentPadding(), bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(sleeveSize)
                .shadow(SLEEVE_SHADOW, SLEEVE_SHAPE)
                .background(palette.elevated)
                .thumbnailBorder(SLEEVE_SHAPE),
            content = artwork,
        )
        Spacer(Modifier.height(20.dp))
        // Ratings are shown by the shared top-right circular toolbar action.
        // Keep album/playlist identity centred and free of inline badges.
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HEADER_GUTTER)
                .testTag("native-release-title"),
        )
        if (credit.isNotBlank()) {
            // Album titles and artist credits are distinct hierarchy levels.
            // Keep the existing compact gap for non-album release/playlist
            // surfaces, but give the Apple-style album header more breathing room.
            Spacer(Modifier.height(if (inlineAlbumMetadata) 7.dp else 2.dp))
            Text(
                text = credit,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal, fontSize = 19.sp,
                ),
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER)
                    .testTag("native-release-credit")
                    .let { m -> if (onCreditClick == null) m
                        else m.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCreditClick) },
            )
        }
        if (inlineAlbumMetadata) {
            // Single album-caption line with only verified metadata and
            // source-advertised quality (not a decoded-stream guarantee).
            if (meta.isNotBlank() || quality != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = HEADER_GUTTER)
                        .testTag("native-album-caption"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (meta.isNotBlank()) Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (quality != null) Modifier.weight(1f, fill = false)
                            else Modifier,
                    )
                    if (quality != null) {
                        if (meta.isNotBlank()) Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.onBackgroundVariant,
                        )
                        SourceQualityBadge(
                            quality = quality, color = palette.onBackgroundVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("native-album-source-quality"),
                        )
                    }
                }
            }
        } else {
            if (quality != null) {
                Spacer(Modifier.height(8.dp))
                SourceQualityBadge(
                    quality = quality, color = palette.onBackgroundVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("native-album-source-quality"),
                )
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta, style = MaterialTheme.typography.labelMedium,
                    color = palette.onBackgroundVariant, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
            }
        }
        actions()
    }
}
