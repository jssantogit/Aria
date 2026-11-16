package com.music.bitchord.data.scrobbling

import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
    private val policyGate: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
    private val networkCall: suspend (suspend () -> String) -> String = { request -> request() },
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    private fun isLastFmListeningPermitted(): Boolean {
        val snapshot = ProviderRuntime.state?.value
        if (snapshot?.status == ProviderSessionStatus.STORAGE_ERROR) return false
        val gate = policyGate() ?: return false
        return gate.open(ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM) != null
    }

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null || !isLastFmListeningPermitted()) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song)
        }
    }

    fun onSongResume(song: Song) {
        if (!isLastFmListeningPermitted()) return
        resumeScrobbleTimer(song)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDurationSeconds = durationMs?.toInt()?.div(1000)
            ?: song.durationText?.let { parseDurationSeconds(it) }
            ?: return

        if (resolvedDurationSeconds <= minSongDuration) return

        val thresholdMs = (resolvedDurationSeconds * 1000L * scrobbleDelayPercent).roundToLong()
        scrobbleRemainingMillis = min(thresholdMs, scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(song, resolvedDurationSeconds)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: Song) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSeconds = song.durationText?.let { parseDurationSeconds(it) } ?: 0
                scrobbleSong(song, durationSeconds)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: Song, durationSeconds: Int) {
        val gate = policyGate() ?: return
        val scrobbleArtist = song.artist.forScrobble()
        val startedAt = songStartedAt
        scope.launch {
            when (val outcome = ProviderQueryExecutor(gate).execute(
                ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM,
            ) {
                networkCall { LastFM.scrobble(
                    artist = scrobbleArtist,
                    track = song.title,
                    duration = durationSeconds,
                    timestamp = startedAt,
                    album = song.albumName,
                ).getOrThrow() }
            }) {
                is ProviderCallResult.Success -> Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
                is ProviderCallResult.Failed -> Log.e(TAG, "Failed to scrobble: ${song.title}", outcome.cause)
                ProviderCallResult.Denied, ProviderCallResult.Stale -> Unit
            }
        }
    }

    private fun updateNowPlaying(song: Song) {
        val gate = policyGate() ?: return
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            when (val outcome = ProviderQueryExecutor(gate).execute(
                ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LASTFM,
            ) {
                networkCall { LastFM.updateNowPlaying(
                    artist = scrobbleArtist,
                    track = song.title,
                    album = song.albumName,
                    duration = song.durationText?.let { parseDurationSeconds(it) },
                ).getOrThrow()
            }) {
                is ProviderCallResult.Success -> Log.d(TAG, "Updated now playing: ${song.title}")
                is ProviderCallResult.Failed -> Log.e(TAG, "Failed to update now playing: ${song.title}", outcome.cause)
                ProviderCallResult.Denied, ProviderCallResult.Stale -> Unit
            }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(song, durationMs)
            } else {
                onSongResume(song)
            }
        } else {
            onSongPause()
        }
    }

    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":")
        if (parts.size != 2) return 0
        val minutes = parts[0].toIntOrNull() ?: return 0
        val seconds = parts[1].toIntOrNull() ?: return 0
        return minutes * 60 + seconds
    }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    companion object {
        private const val TAG = "ScrobbleManager"
    }
}
