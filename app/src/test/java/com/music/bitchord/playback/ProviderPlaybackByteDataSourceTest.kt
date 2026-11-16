package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import java.io.IOException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/** Byte delivery, not merely URL resolution, remains scoped to the producing extension. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderPlaybackByteDataSourceTest {
    private val addon = ProviderKey("addon", "music-one")
    private val module = ProviderKey("custom_module", "music-two")

    private fun policy(addonEnabled: Boolean, moduleEnabled: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(addon, module).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            choices = mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, addonEnabled, 0),
                ProviderChoice(module, moduleEnabled, 1),
            )),
        )

    private fun spec(
        lease: ProviderPlaybackByteLease?,
        uri: String = "https://cdn.example/audio.flac",
    ): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(uri))
            .setCustomData(lease)
            .build()

    private class Bytes : DataSource {
        var opens = 0
        var closes = 0
        var cursor = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            cursor = 0
            return 3L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor == 3) return -1
            val count = minOf(length, 3 - cursor)
            repeat(count) { buffer[offset + it] = (cursor + it + 1).toByte() }
            cursor += count
            return count
        }

        override fun getUri(): Uri? = Uri.parse("https://cdn.example/audio.flac")
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() { closes++ }
    }

    @Test fun revocationCancelsPhysicalOkHttpCallBeforeServerResponds() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val calls = ProviderPlaybackCallFactory(
            OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
        )
        val source = ProviderPlaybackByteDataSource(
            OkHttpDataSource.Factory(calls).createDataSource(),
            cancelNetwork = calls::revoke,
        )
        val finished = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                source.open(spec(ProviderPlaybackByteLease(gate, ticket),
                    server.url("/never-respond").toString()))
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                runCatching { source.close() }
                finished.set(true)
            }
        }
        try {
            worker.start()
            assertTrue("HTTP request must reach the local fixture",
                server.takeRequest(5, TimeUnit.SECONDS) != null)
            gate.replace(policy(false))
            worker.join(5_000L)
            assertFalse("Revocation must cancel the pending HTTP Call, not just future reads",
                worker.isAlive)
            assertTrue(finished.get())
            assertTrue("Cancelled open must fail", error.get() is IOException)
        } finally {
            gate.replace(policy(false))
            worker.interrupt()
            server.shutdown()
        }
    }

    @Test fun sameDataSourceCanOpenAgainAfterCleanCloseWithFreshAuthorizedLease() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("again"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            repeat(2) { index ->
                val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
                val path = if (index == 0) "/first" else "/again"
                assertEquals(5L, source.open(spec(
                    ProviderPlaybackByteLease(gate, ticket), server.url(path).toString(),
                )))
                val bytes = ByteArray(5)
                assertEquals(5, source.read(bytes, 0, bytes.size))
                assertEquals(if (index == 0) "first" else "again", String(bytes))
                source.close()
            }
            assertEquals("/first", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/again", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun sameDataSourceCanRecoverAfterRevocationWithNewUserAuthorization() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("new"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            val firstTicket = requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
            // With the ticket revoked before open, the next attempt must not
            // touch the network, but the reused DataSource is not poisoned.
            gate.replace(policy(false))
            try {
                source.open(spec(ProviderPlaybackByteLease(gate, firstTicket),
                    server.url("/denied").toString()))
                fail("Stale provider ticket must be rejected")
            } catch (_: IOException) {}
            source.close()
            gate.replace(policy(true))
            val newTicket = requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
            assertEquals(3L, source.open(spec(
                ProviderPlaybackByteLease(gate, newTicket), server.url("/new").toString(),
            )))
            assertEquals(3, source.read(ByteArray(3), 0, 3))
            assertEquals("/new", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun disablingWinningAddonClosesTransportAndPreventsFurtherBytes() {
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
        val bytes = ByteArray(3)
        assertEquals(1, dataSource.read(bytes, 0, 1))
        gate.replace(policy(false))
        assertTrue("Underlying HTTP must close on capability revocation", backend.closes > 0)
        try {
            dataSource.read(bytes, 1, 2)
            fail("Revoked provider published more playback bytes")
        } catch (_: IOException) {
            // Expected: stale source bytes are not returned to Media3.
        } finally { dataSource.close() }
    }

    @Test fun revocationBeforeOpenCannotSendAnHttpRequest() {
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        gate.replace(policy(false))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
            fail("Stale ticket started a media request")
        } catch (_: IOException) {
            assertEquals(0, backend.opens)
        } finally { dataSource.close() }
    }

    @Test fun unrelatedModuleRevocationKeepsAuthorizedAddonBytesReadable() {
        val gate = ProviderPolicyGate(policy(true, true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
            gate.replace(policy(true, false))
            assertEquals(3, dataSource.read(ByteArray(3), 0, 3))
            assertEquals(0, backend.closes)
        } finally { dataSource.close() }
    }

    @Test fun unrelatedHttpWithoutLeaseKeepsExistingBehavior() {
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(null))
            assertEquals(3, dataSource.read(ByteArray(3), 0, 3))
            assertFalse(backend.closes > 0)
        } finally { dataSource.close() }
    }
}
