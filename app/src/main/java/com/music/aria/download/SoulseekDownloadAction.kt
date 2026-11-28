package com.music.aria.download

import android.content.Context
import com.music.aria.BuildConfig
import com.music.aria.data.model.Song
import com.music.aria.data.settings.AppSettings

/** Shared explicit download action. UI and instrumentation enter the same queue. */
internal object SoulseekDownloadAction {
    enum class Result { UNSUPPORTED, NETWORK_RESTRICTED, REFUSED, QUEUED }

    fun submit(context:Context,requested:List<Song>,from:DownloadTarget?,playing:Song?=null):Result {
        val pilot = BuildConfig.FLAVOR == "dev" && BuildConfig.DEBUG
        val validIds = requested.all {
            it.videoId.isNotBlank() && it.videoId.length <= 180 &&
                !it.videoId.any(Char::isISOControl)
        }
        val plan = if (!validIds || !pilot) null
        else if (requested.size > 1) {
            SoulseekBatchPlan.prepare(
                requested.map { track ->
                    SoulseekBatchPlan.SourceTrack(
                        trackId = track.videoId,
                        artist = track.artist,
                        title = track.title,
                        creditedArtists = track.artists.map { it.name },
                        album = track.albumName,
                        durationSeconds = SoulseekDownloadRouting.durationSeconds(track.durationText),
                    )
                },
                releaseTitle = from?.title,
                isRelease = from != null && !from.playlist,
                pilotAvailable = pilot,
            )
        } else requested.singleOrNull()?.let { song ->
            val routed = SoulseekDownloadRouting.route(
                artist = song.artist, title = song.title, count = 1,
                pilotAvailable = pilot, creditedArtists = song.artists.map { it.name },
            )
            (routed as? SoulseekDownloadRouting.Decision.ManualSearch)?.let {
                listOf(SoulseekBatchPlan.Entry(
                    trackId = song.videoId,
                    query = it.query,
                    album = SoulseekDownloadRouting.albumHint(
                        songAlbum = song.albumName,
                        playingAlbum = playing?.albumName,
                        targetAlbum = from?.title,
                        samePlayingTrack = playing?.videoId == song.videoId,
                        fromAlbum = from != null && !from.playlist,
                    ),
                    durationSeconds = SoulseekDownloadRouting.durationSeconds(song.durationText),
                ))
            }
        }
        return when {
            plan == null -> Result.UNSUPPORTED
            !AppSettings.downloadsAllowedNow -> Result.NETWORK_RESTRICTED
            !Downloads.enqueueSoulseekAuto(context, plan, requested) ->
                Result.REFUSED
            else -> Result.QUEUED
        }
    }
}
