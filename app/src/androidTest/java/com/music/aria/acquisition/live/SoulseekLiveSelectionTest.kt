package com.music.aria.acquisition.live

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.aria.acquisition.a0.A0AutoCandidateSelectionPolicy
import com.music.aria.acquisition.a0.A0AutoDiscoveryCoordinator
import com.music.aria.acquisition.a0.A0CanonicalAcquisitionIntent
import com.music.aria.acquisition.a0.A0FreshAutoSearchTransport
import com.music.aria.acquisition.a0.A0AutoBatchExecutor
import com.music.aria.acquisition.a0.A0AutoAcquisitionExecutor
import com.music.aria.acquisition.a0.A0ObservedCandidateHandoff
import com.music.aria.acquisition.a0.A0VerifiedByteStager
import com.music.aria.acquisition.a0.SoulseekActiveSession
import com.music.aria.acquisition.a0.SoulseekCredentialVault
import com.music.aria.download.SoulseekBatchPlan
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Frozen single-track calibration through the real coordinator and executor, stopping before P/F. */
@RunWith(AndroidJUnit4::class)
class SoulseekLiveSelectionTest {
    private data class Target(val id:String,val query:String,val album:String,val seconds:Int)
    // Frozen K-pop v1. Durations are reference seconds pending independent
    // catalog verification; do not adjust targets to match peer claims.
    private val targets=listOf(
        Target("illit_magnetic","ILLIT - Magnetic","SUPER REAL ME",160),
        Target("illit_lucky_girl","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",140),
        Target("illit_cherish","ILLIT - Cherish (My Love)","I'LL LIKE YOU",176),
        Target("newjeans_hype_boy","NewJeans - Hype Boy","New Jeans",179),
        Target("newjeans_ditto","NewJeans - Ditto","OMG",185),
        Target("newjeans_super_shy","NewJeans - Super Shy","Get Up",154),
        Target("bts_dynamite","BTS - Dynamite","BE",199),
        Target("bts_butter","BTS - Butter","Butter",164),
        Target("bts_boy_with_luv","BTS - Boy With Luv (feat. Halsey)","MAP OF THE SOUL : PERSONA",229),
        Target("blackpink_ddududdudu","BLACKPINK - DDU-DU DDU-DU","SQUARE UP",209),
        Target("blackpink_how_you_like_that","BLACKPINK - How You Like That","THE ALBUM",181),
        Target("blackpink_pink_venom","BLACKPINK - Pink Venom","BORN PINK",186),
        Target("twice_fancy","TWICE - FANCY","FANCY YOU",215),
        Target("twice_feel_special","TWICE - Feel Special","Feel Special",206),
        Target("twice_what_is_love","TWICE - What is Love?","What is Love?",208),
        Target("aespa_next_level","aespa - Next Level","Next Level",221),
        Target("aespa_supernova","aespa - Supernova","Armageddon",178),
        Target("aespa_drama","aespa - Drama","Drama",214),
        Target("ive_love_dive","IVE - LOVE DIVE","LOVE DIVE",177),
        Target("ive_after_like","IVE - After LIKE","After LIKE",176),
        Target("ive_i_am","IVE - I AM","I've IVE",183),
        Target("lesserafim_antifragile","LE SSERAFIM - ANTIFRAGILE","ANTIFRAGILE",184),
        Target("lesserafim_unforgiven","LE SSERAFIM - UNFORGIVEN (feat. Nile Rodgers)","UNFORGIVEN",182),
        Target("lesserafim_perfect_night","LE SSERAFIM - Perfect Night","Perfect Night",159),
        Target("straykids_gods_menu","Stray Kids - God's Menu","GO LIVE",167),
        Target("straykids_maniac","Stray Kids - MANIAC","ODDINARY",182),
        Target("straykids_s_class","Stray Kids - S-Class","5-STAR",195),
        Target("seventeen_super","SEVENTEEN - Super","FML",200),
        Target("seventeen_hot","SEVENTEEN - HOT","Face the Sun",197),
        Target("seventeen_very_nice","SEVENTEEN - VERY NICE","Love & Letter Repackage Album",192),
    )
    // Signal only the actual executor's first selected candidate. This Error
    // passes its Exception handlers; finally blocks still close all readers.
    // Never fabricate a transfer result or touch audio bytes.
    private class SelectionBoundary:Error()
    @Test(timeout=3600000)
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
                var search="NOT_STARTED"
                var decision:A0AutoCandidateSelectionPolicy.Decision?=null
                var reachedBoundary=false
                // Observe the real discovery result without changing it.
                val observed=object:A0AutoDiscoveryCoordinator.SearchPort {
                    override fun search(entry:SoulseekBatchPlan.Entry,
                        releaseSlots:List<SoulseekBatchPlan.Entry>,isCancelled:()->Boolean
                    ):A0AutoDiscoveryCoordinator.SearchResult {
                        val result=transport.search(entry,releaseSlots,isCancelled)
                        search=when(result) {
                            is A0AutoDiscoveryCoordinator.SearchResult.Found -> "FOUND"
                            A0AutoDiscoveryCoordinator.SearchResult.NoReply -> "NO_REPLY"
                            A0AutoDiscoveryCoordinator.SearchResult.ScreenedOut -> "SCREENED_OUT"
                            A0AutoDiscoveryCoordinator.SearchResult.QueryRejected -> "QUERY_REJECTED"
                            A0AutoDiscoveryCoordinator.SearchResult.TeardownIncomplete -> "TEARDOWN_INCOMPLETE"
                            A0AutoDiscoveryCoordinator.SearchResult.Stop -> "STOP"
                        }
                        return result
                    }
                    override fun close()=transport.close()
                }
                val executor=A0AutoBatchExecutor {
                    object:A0AutoAcquisitionExecutor.Port {
                        override fun fetch(candidate:A0ObservedCandidateHandoff.Choice,
                            isCancelled:()->Boolean):A0AutoAcquisitionExecutor.FetchResult {
                            reachedBoundary=true
                            throw SelectionBoundary()
                        }
                        override fun inspect(receipt:A0VerifiedByteStager.Receipt,
                            expectedExtension:String,isCancelled:()->Boolean):Nothing =
                            error("Selection live cannot inspect bytes")
                        override fun publish(receipt:A0VerifiedByteStager.Receipt,
                            safeName:String,isCancelled:()->Boolean):Nothing =
                            error("Selection live cannot publish")
                        override fun cleanup(receipt:A0VerifiedByteStager.Receipt):Unit =
                            error("Selection live cannot stage bytes")
                    }
                }
                try {
                    A0AutoDiscoveryCoordinator(observed,executor).run(
                        entries,profile,userRequested=true,optedIn=true,
                        isCancelled={Thread.currentThread().isInterrupted},
                        stillAuthorized={SoulseekActiveSession.isCurrent(generation)},
                        onCandidateDecision={_,value -> decision=value})
                } catch(_:SelectionBoundary) {
                    check(reachedBoundary)
                } finally { transport.close() }
                val selected=decision
                val truncated=selected?.reason==A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE
                val row=listOf(target.id,search,
                    if(reachedBoundary)"SELECTED" else if(selected==null)"NO_DECISION" else "ABSTAINED",
                    selected?.reason?.name?:"NONE",
                    selected?.examined?:0,selected?.eligible?:0,
                    selected?.funnel?.rankRejected?:0,selected?.funnel?.preflightRejected?:0,
                    selected?.funnel?.albumRejected?:0,selected?.funnel?.qualityRejected?:0,
                    if(truncated)1 else 0,SystemClock.elapsedRealtime()-start).joinToString("\t")
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
