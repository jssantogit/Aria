package com.music.bitchord.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.Request
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestTicket
import java.io.IOException

/**
 * A provider-neutral, in-memory authorization proof for source-owned HTTP bytes.
 * DataSpec.customData does not become an HTTP header, URI, cache key or log.
 * The lease names the exact provider *instance* that won stream resolution.
 */
internal class ProviderPlaybackByteLease(
    private val gate: ProviderPolicyGate,
    private val ticket: ProviderRequestTicket,
) {
    fun allowed(): Boolean = gate.accept(ticket)
    fun onRevoke(callback: () -> Unit): () -> Unit = gate.onRevoke(ticket, callback)
}

/**
 * The Media3 OkHttpDataSource.close() path cannot cancel a Call whose HTTP
 * headers have not arrived yet: there is no established response stream to
 * close. Hold the actual OkHttp Call per Media3 data source and cancel it even
 * during the initial pending open. A revoked session cannot create a later
 * network Call; sibling data sources have separate factories and are untouched.
 */
internal class ProviderPlaybackCallFactory(
    private val upstream: Call.Factory,
) : Call.Factory {
    private val lock = Any()
    private var revoked = false
    private var latest: Call? = null

    override fun newCall(request: Request): Call {
        val call = upstream.newCall(request)
        synchronized(lock) {
            if (revoked) call.cancel()
            else latest = call
        }
        return call
    }

    fun revoke() {
        val pending = synchronized(lock) {
            revoked = true
            latest.also { latest = null }
        }
        pending?.cancel()
    }
}

/**
 * Sits AFTER ResolvingDataSource and BEFORE the HTTP/ChunkedDataSource chain.
 * Stream URL discovery alone is not permission to continue downloading bytes:
 * disabling the source must cancel an already open HTTP call and suppress
 * any bytes that race in immediately after revocation.
 *
 * Non-extension requests are passed through untouched (local media and
 * OpenSubsonic use their own playback transports).
 */
@OptIn(UnstableApi::class)
internal class ProviderPlaybackByteDataSource(
    private val upstream: DataSource,
    private val cancelNetwork: () -> Unit = {},
) : DataSource {
    @Volatile private var revoked = false
    private var lease: ProviderPlaybackByteLease? = null
    private var unsubscribe: (() -> Unit)? = null
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("Playback byte transport already open")
        revoked = false
        val permission = dataSpec.customData as? ProviderPlaybackByteLease
        lease = permission
        if (permission != null) {
            if (!permission.allowed()) {
                lease = null
                throw IOException("Playback source capability revoked")
            }
            unsubscribe = permission.onRevoke {
                revoked = true
                // Cancel the actual OkHttp Call first; Media3.close() alone
                // cannot abort a pending open before response headers arrive.
                cancelNetwork()
                runCatching { upstream.close() }
            }
            if (revoked || !permission.allowed()) {
                close()
                throw IOException("Playback source capability revoked")
            }
        }
        opened = true
        return try {
            val count = upstream.open(dataSpec)
            checkAuthorized()
            count
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Playback byte transport is not open")
        checkAuthorized()
        val count = upstream.read(buffer, offset, length)
        checkAuthorized()
        return count
    }

    private fun checkAuthorized() {
        if (revoked || lease?.allowed() == false)
            throw IOException("Playback source capability revoked")
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    override fun close() {
        opened = false
        unsubscribe?.invoke()
        unsubscribe = null
        lease = null
        cancelNetwork()
        upstream.close()
    }

    internal class Factory(
        private val calls: Call.Factory,
        private val chunkBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val scopedCalls = ProviderPlaybackCallFactory(calls)
            return ProviderPlaybackByteDataSource(
                ChunkedDataSource(
                    OkHttpDataSource.Factory(scopedCalls).createDataSource(), chunkBytes,
                ),
                cancelNetwork = scopedCalls::revoke,
            )
        }
    }
}
