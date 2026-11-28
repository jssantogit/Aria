package com.music.aria.acquisition.live

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.aria.acquisition.a0.A0AutoCandidateSelectionPolicy
import com.music.aria.acquisition.a0.A0AutoDiscoveryCoordinator
import com.music.aria.acquisition.a0.A0CanonicalAcquisitionIntent
import com.music.aria.acquisition.a0.A0FreshAutoSearchTransport
import com.music.aria.acquisition.a0.A0MultiSearchCandidateEvidence
import com.music.aria.acquisition.a0.SoulseekActiveSession
import com.music.aria.acquisition.a0.SoulseekCredentialVault
import com.music.aria.download.SoulseekBatchPlan
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** First bounded calibration corpus. No P/F, publication, or transfer port. */
@RunWith(AndroidJUnit4::class)
class SoulseekLiveSelectionTest {
    private data class Target(val id:String,val query:String,val album:String,val seconds:Int)
    // Bootstrap reference metadata; a later frozen corpus must carry verified
    // provider IDs and exact editions/durations. No case is tuned to a peer.
    private val targets=listOf(
        Target("magnetic","ILLIT - Magnetic","SUPER REAL ME",160),
        Target("super_shy","NewJeans - Super Shy","Get Up",154),
        Target("luther","Kendrick Lamar - luther","GNX",177),
        Target("numb","Linkin Park - Numb","Meteora",187),
        Target("bohemian","Queen - Bohemian Rhapsody","A Night at the Opera",355),
    )
    @Test(timeout=600000)
    fun discoverAndEvaluateWithoutTransfer() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val secretFile=File(context.filesDir,"aria-live-credentials")
        // Ordinary instrumentation suites must never contact Soulseek.
        assumeTrue("Dedicated live account not provisioned",secretFile.isFile)
        val vault=SoulseekCredentialVault(context)
        val bytes=secretFile.readBytes()
        try {
            assertTrue("Cannot remove temporary account input",secretFile.delete())
            val separator=bytes.indexOf(0.toByte())
            assertTrue("Invalid CI credential envelope",separator>0 && separator<bytes.lastIndex)
            val username=String(bytes,0,separator,Charsets.UTF_8)
            val password=String(bytes,separator+1,bytes.size-separator-1,Charsets.UTF_8).toCharArray()
            try { vault.save(username,password) }
            finally { password.fill('\u0000') }
        } finally { bytes.fill(0) }
        try {
            val generation=SoulseekActiveSession.generation()
            val profile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,strict=true)
            var completed=0
            var observations=0
            for(target in targets) {
                val entry=SoulseekBatchPlan.Entry("ci-"+target.id,target.query,target.album,target.seconds)
                val entries=listOf(entry)
                val transport=A0FreshAutoSearchTransport(context,generation,profile)
                val start=SystemClock.elapsedRealtime()
                val result=try { transport.search(entry,entries){Thread.currentThread().isInterrupted} }
                    finally { transport.close() }
                val pool=A0MultiSearchCandidateEvidence(entries,profile)
                val search=when(result) {
                    is A0AutoDiscoveryCoordinator.SearchResult.Found -> {
                        pool.add(result.choices,result.truncatedSlots)
                        "FOUND"
                    }
                    A0AutoDiscoveryCoordinator.SearchResult.NoReply -> "NO_REPLY"
                    A0AutoDiscoveryCoordinator.SearchResult.ScreenedOut -> "SCREENED_OUT"
                    A0AutoDiscoveryCoordinator.SearchResult.QueryRejected -> "QUERY_REJECTED"
                    A0AutoDiscoveryCoordinator.SearchResult.TeardownIncomplete -> "TEARDOWN_INCOMPLETE"
                    A0AutoDiscoveryCoordinator.SearchResult.Stop -> "STOP"
                }
                val (choices,truncated)=checkNotNull(pool.slotEvidence(0))
                val decision=A0AutoCandidateSelectionPolicy.preview(
                    entry,choices,profile,sampleTruncated=truncated)
                val row=listOf(target.id,search,decision.status.name,decision.reason?.name?:"NONE",
                    decision.examined,decision.eligible,decision.funnel.rankRejected,
                    decision.funnel.preflightRejected,decision.funnel.albumRejected,
                    decision.funnel.qualityRejected,if(truncated)1 else 0,
                    SystemClock.elapsedRealtime()-start).joinToString("\t")
                instrument.sendStatus(0,Bundle().apply { putString("aria_live",row) })
                completed++
                if(search=="FOUND" || search=="SCREENED_OUT")observations++
                if(search=="STOP")break // no automatic reconnect on account/protocol failure
            }
            assertTrue("Live corpus stopped early",completed==targets.size)
            assertTrue("No real peer observations",observations>0)
            // Success means telemetry collected, NOT proof of selection accuracy.
        } finally { vault.forget() }
    }
}
