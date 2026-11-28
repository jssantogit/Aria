package com.music.aria.acquisition.a0

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** A slow inbound P peer must not delay parsing its 11 faster neighbours. */
class A0DirectPeerSearchCollectorTest {
    @Test(timeout = 5000)
    fun twelveInboundPeersAreDispatchedBeforeTheFirstOneResponds() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(12)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val collector = A0DirectPeerSearchCollector { _ ->
            val n = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, n) }
            started.countDown()
            unblock.await(2, TimeUnit.SECONDS)
            active.decrementAndGet()
        }
        try {
            repeat(12) { assertTrue(collector.offer(Socket())) }
            assertTrue("All peers should begin while the first is stalled",
                started.await(2, TimeUnit.SECONDS))
            assertTrue(peak.get() >= 12)
        } finally {
            unblock.countDown()
            collector.closeSockets()
            assertTrue(collector.stop(2000))
        }
    }

    @Test(timeout = 5000)
    fun saturatedCollectorRejectsAndClosesSocketsWithoutLeaking() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(12)
        val collector = A0DirectPeerSearchCollector { _ ->
            started.countDown()
            unblock.await(2, TimeUnit.SECONDS)
        }
        try {
            repeat(12) { assertTrue(collector.offer(Socket())) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(128) { assertTrue(collector.offer(Socket())) }
            val rejected = Socket()
            assertFalse(collector.offer(rejected))
            assertTrue("Rejected TCP sockets must be released", rejected.isClosed)
        } finally {
            unblock.countDown()
            collector.closeSockets()
            assertTrue(collector.stop(2000))
        }
        val afterStop = Socket()
        assertFalse(collector.offer(afterStop))
        assertTrue(afterStop.isClosed)
    }

    @Test(timeout = 5000)
    fun socketRegisteredAfterShutdownSnapshotIsRejectedAndClosed() {
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val firstHash = java.util.concurrent.atomic.AtomicBoolean(true)
        val processed = AtomicInteger(0)
        val accepted = java.util.concurrent.atomic.AtomicReference<Boolean>()
        // Hashing occurs at registration, after the initial closed check.
        // Force the socket to miss the shutdown snapshot deterministically.
        val socket = object : Socket() {
            override fun hashCode(): Int {
                if (firstHash.compareAndSet(true, false)) {
                    registrationEntered.countDown()
                    check(releaseRegistration.await(3, TimeUnit.SECONDS))
                }
                return System.identityHashCode(this)
            }
        }
        val collector = A0DirectPeerSearchCollector { processed.incrementAndGet() }
        val submitter = Thread { accepted.set(collector.offer(socket)) }
        try {
            submitter.start()
            assertTrue(registrationEntered.await(2, TimeUnit.SECONDS))
            collector.closeSockets()
            releaseRegistration.countDown()
            submitter.join(2000)
            assertFalse(submitter.isAlive)
            assertEquals(false, accepted.get())
            assertTrue(socket.isClosed)
            assertEquals(0, processed.get())
        } finally {
            releaseRegistration.countDown()
            submitter.join(2000)
            collector.cancel()
            socket.close()
        }
    }
}
