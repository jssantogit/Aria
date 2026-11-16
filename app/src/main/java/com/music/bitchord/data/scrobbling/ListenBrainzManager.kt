package com.music.bitchord.data.scrobbling

import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ListenBrainzManager {
    private const val TAG = "ListenBrainzManager"
    private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"

    @Volatile
    internal var policyGateForTests: (() -> ProviderPolicyGate?)? = null

    /** Test hook replaces only transport, never policy authorization. */
    @Volatile internal var submitForTests: (suspend (Request) -> Boolean)? = null

    private suspend fun sendWithPolicy(request: Request): Boolean {
        val gate = policyGateForTests?.invoke() ?: ProviderRuntime.gateOrNull() ?: return false
        val result = ProviderQueryExecutor(gate).execute(
            ProviderCapability.LISTENING_STATISTICS, BuiltInProviderKeys.LISTENBRAINZ,
        ) {
            val testTransport = submitForTests
            if (testTransport == null) sendCancellable(request) else testTransport(request)
        }
        return when (result) {
            is ProviderCallResult.Success -> result.value
            is ProviderCallResult.Failed -> {
                Log.e(TAG, "Submission failed", result.cause)
                false
            }
            ProviderCallResult.Denied, ProviderCallResult.Stale -> false
        }
    }

    private suspend fun sendCancellable(request: Request): Boolean =
        suspendCancellableCoroutine { continuation ->
            val call = Http.client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resume(response.isSuccessful)
                    }
                }
            })
        }

    suspend fun submitPlayingNow(
        token: String,
        song: Song?,
        positionMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        if (token.isBlank() || song == null) return false
        return run {
                val durationMs = durationMsOverride?.takeIf { it > 0 } ?: parseDurationMs(song.durationText)
                val durationPart = if (durationMs > 0) "\"duration_ms\":$durationMs," else ""
                val releaseName = song.albumName.orEmpty()
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                val artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist
                val trackMetadata = """{"track_metadata":{"artist_name":"${escapeJson(artist)}","track_name":"${escapeJson(song.title)}",$releasePart"additional_info":{${durationPart}"position_ms":$positionMs,"submission_client":"BitChord"}}}"""
                val bodyJson = """{"listen_type":"playing_now","payload":[$trackMetadata]}"""
                Log.d(TAG, "submitPlayingNow: $bodyJson")
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url(API_URL)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                sendWithPolicy(request)
        }
    }

    suspend fun submitFinished(
        token: String,
        song: Song?,
        startMs: Long,
        endMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        if (token.isBlank() || song == null) return false
        return run {
                val durationMs = durationMsOverride?.takeIf { it > 0 } ?: parseDurationMs(song.durationText)
                val durationPart = if (durationMs > 0) "\"duration_ms\":$durationMs," else ""
                val releaseName = song.albumName.orEmpty()
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                var listenedAtStart = startMs / 1000L
                val MIN_LISTEN_TS = 1033430400L
                if (listenedAtStart < MIN_LISTEN_TS) {
                    listenedAtStart = System.currentTimeMillis() / 1000L
                }
                val artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist
                val trackMetadata = """{"listened_at":$listenedAtStart,"track_metadata":{"artist_name":"${escapeJson(artist)}","track_name":"${escapeJson(song.title)}",$releasePart"additional_info":{${durationPart}"start_ms":$startMs,"end_ms":$endMs,"submission_client":"BitChord"}}}"""
                val bodyJson = """{"listen_type":"single","payload":[$trackMetadata]}"""
                Log.d(TAG, "submitFinished: $bodyJson")
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url(API_URL)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                sendWithPolicy(request)
        }
    }

    private fun parseDurationMs(text: String?): Long {
        if (text == null) return 0L
        val parts = text.split(":")
        if (parts.size != 2) return 0L
        val minutes = parts[0].toLongOrNull() ?: return 0L
        val seconds = parts[1].toLongOrNull() ?: return 0L
        return (minutes * 60 + seconds) * 1000
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
