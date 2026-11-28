package com.music.aria.acquisition.live

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.aria.MainActivity
import com.music.aria.acquisition.a0.SoulseekCredentialVault
import com.music.aria.data.model.Song
import com.music.aria.data.settings.AppSettings
import com.music.aria.data.settings.DownloadQualityProfile
import com.music.aria.download.A3AcquisitionLedger
import com.music.aria.download.A3AndroidAcquisitionLedger
import com.music.aria.download.DownloadProgress
import com.music.aria.download.DownloadSession
import com.music.aria.download.DownloadTarget
import com.music.aria.download.Downloads
import com.music.aria.download.SoulseekDownloadAction
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Full application path, only for explicitly authorized, hash-pinned audio fixtures. */
@RunWith(AndroidJUnit4::class)
class SoulseekLiveAcquisitionTest {
    private data class Fixture(val song:Song,val sha256:String)
    @Test(timeout=1200000)
    fun explicitDownloadPublishesAndRejectsDuplicate() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val input=File(context.filesDir,"aria-live-fixtures")
        val credentialFile=File(context.filesDir,"aria-live-credentials")
        assumeTrue("Dedicated acquisition fixture not provisioned",input.isFile && credentialFile.isFile)
        val groups=JSONArray(input.readText())
        assertTrue("Cannot remove fixture input",input.delete())
        assertTrue("Invalid fixture group count",groups.length() in 1..5)
        val vault=SoulseekCredentialVault(context)
        val bytes=credentialFile.readBytes()
        try {
            assertTrue("Cannot remove temporary account input",credentialFile.delete())
            val separator=bytes.indexOf(0.toByte())
            assertTrue("Invalid account envelope",separator>0 && separator<bytes.lastIndex)
            val password=String(bytes,separator+1,bytes.size-separator-1,Charsets.UTF_8).toCharArray()
            try { vault.save(String(bytes,0,separator,Charsets.UTF_8),password) }
            finally { password.fill('\u0000') }
        } finally { bytes.fill(0) }
        val ledger=A3AndroidAcquisitionLedger.get(context)
        val before=ledger.all().map { it.id }.toSet()
        assertTrue("Full acquisition requires clean emulator",before.isEmpty())
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            scenario=ActivityScenario.launch(MainActivity::class.java)
            val activeScenario=checkNotNull(scenario)
            activeScenario.onActivity { AppSettings.setDownloadQualityProfile(DownloadQualityProfile.LOSSLESS) }
            for(index in 0 until groups.length()) {
                val group=groups.getJSONObject(index)
                val tracks=group.getJSONArray("tracks")
                assertTrue("Fixture size",tracks.length() in 1..8)
                val fixtures=(0 until tracks.length()).map { j ->
                    val t=tracks.getJSONObject(j)
                    val hash=t.getString("sha256").lowercase()
                    assertTrue("Fixture requires pinned audio hash",hash.matches(Regex("[0-9a-f]{64}")))
                    Fixture(Song(videoId=t.getString("id"),title=t.getString("title"),
                        artist=t.getString("artist"),thumbnailUrl=null,
                        durationText=t.getString("duration"),albumName=t.getString("album")),hash)
                }
                val songs=fixtures.map { it.song }
                val target=if(songs.size==1)null else DownloadTarget(
                    id=group.getString("id"),title=group.getString("album"))
                val prior=DownloadSession.state.value.items.map { it.videoId }.toSet()
                val start=SystemClock.elapsedRealtime()
                activeScenario.onActivity { activity ->
                    assertEquals(SoulseekDownloadAction.Result.QUEUED,
                        SoulseekDownloadAction.submit(activity,songs,target))
                }
                val deadline=start+180000L* songs.size
                var settled:DownloadSession.Item?=null
                while(SystemClock.elapsedRealtime()<deadline) {
                    settled=DownloadSession.state.value.items.firstOrNull {
                        it.videoId !in prior && it.progress.settled
                    }
                    if(settled!=null && !Downloads.busy())break
                    Thread.sleep(250)
                }
                val saved=songs.count { Downloads.saved.value[it.videoId]!=null }
                val elapsed=SystemClock.elapsedRealtime()-start
                instrument.sendStatus(0,Bundle().apply { putString("aria_live","$index\t${songs.size}\t$saved\t$elapsed") })
                assertTrue("Queue did not settle",settled!=null && !Downloads.busy())
                assertTrue("Download session not completed",settled?.progress is DownloadProgress.Done)
                for(fixture in fixtures) {
                    val uri=checkNotNull(Downloads.saved.value[fixture.song.videoId])
                    val row=ledger.all().single { it.contentUri==uri }
                    assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,row.phase)
                    val digest=MessageDigest.getInstance("SHA-256")
                    var count=0L
                    checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { stream ->
                        val buffer=ByteArray(65536)
                        while(true) {
                            val n=stream.read(buffer)
                            if(n<0)break
                            digest.update(buffer,0,n)
                            count+=n
                        }
                    }
                    assertEquals(row.byteCount,count)
                    assertTrue("Published audio differs from authorized fixture",
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==fixture.sha256)
                }
                val recorded=ledger.all()
                activeScenario.onActivity { activity ->
                    assertEquals(SoulseekDownloadAction.Result.REFUSED,
                        SoulseekDownloadAction.submit(activity,songs,target))
                }
                assertEquals("Duplicate request changed durable ledger",recorded,ledger.all())
            }
        } finally {
            vault.forget()
            DownloadSession.state.value.items.filterNot { it.progress.settled }
                .forEach { Downloads.cancel(it.videoId) }
            scenario?.close()
            ledger.all().filter { it.id !in before }.mapNotNull { it.contentUri }.forEach {
                runCatching { context.contentResolver.delete(Uri.parse(it),null,null) }
            }
        }
    }
}
