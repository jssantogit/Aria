package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.playback.AmsPreviewResolver
import com.music.bitchord.playback.AmsPreviewRoute
import com.music.bitchord.playback.ProviderPlaybackCallFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnostic only: public sample files, no complete tracks, no audio retained,
 * no metadata values, sample URLs or authentication material in artifacts. */
class AmsPreviewContainerTimingLiveTest {
    @Test fun inspectPublicPreviewTimeline(): Unit = runBlocking {
        assumeTrue(System.getenv("AMS_PREVIEW_CONTAINER_LIVE") == "1")
        val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.AUDIO_PLAYBACK)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(provider,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(provider,true,0)) }
        ))
        val catalog = AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission=RateLimitedProviderTransport { gate }))
        val gateway = AppleMusicWebEditorialGateway(catalog,gate)
        val reader = CatalogSearchAdapterReader(gate,listOf(AppleMusicWebSearchAdapter(gateway)))
        val resolver = AmsPreviewResolver({gate}) { gateway }
        val cases = listOf(
            Triple("IVE I AM","I AM","IVE"),
            Triple("IVE LOVE DIVE","LOVE DIVE","IVE"),
            Triple("SAY MY NAME ShaLala","ShaLala","SAY MY NAME"),
            Triple("SAY MY NAME iLy","iLy","SAY MY NAME")
        )
        var observed = 0
        for ((index, case) in cases.withIndex()) {
            val (query,title,credit) = case
            val result = withTimeoutOrNull(60_000L) {
                reader.read(provider,CatalogSearchRequest(CatalogSearchKind.TRACK,query,"br",limit=24))
            }
            val item = (result as? ProviderCallResult.Success)?.value?.items?.firstOrNull {
                it.title.equals(title,true) && it.subtitle?.equals(credit,true) == true && it.hasAudioPreview
            }
            if (item == null) {
                println("AMS_PREVIEW_CONTAINER|case=${index+1}|status=no_exact_sample")
                continue
            }
            val route = requireNotNull(AmsPreviewRoute.fromScopedId("apple_music_web:br:song:${item.identity.nativeId}"))
            val selection = try { withTimeoutOrNull(60_000L) { resolver.resolve(route) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (selection == null) {
                println("AMS_PREVIEW_CONTAINER|case=${index+1}|status=resolution_unavailable")
                continue
            }
            val file = Files.createTempFile("ams-preview-probe-", ".m4a")
            val calls = ProviderPlaybackCallFactory(OkHttpClient.Builder().followRedirects(false)
                .followSslRedirects(false).callTimeout(25,TimeUnit.SECONDS).build())
            calls.beginOpen(true)
            val unregister = selection.lease.onRevoke { calls.revoke() }
            try {
                check(selection.lease.allowed())
                // The existing resolver has validated exact song identity, audio
                // permission and the Apple HTTPS preview host. No full stream.
                val call = calls.newCall(Request.Builder().url(selection.url).build())
                call.execute().use { response ->
                    check(response.isSuccessful && selection.lease.allowed())
                    val body = requireNotNull(response.body)
                    check(body.contentLength() <= MAX_BYTES)
                    body.byteStream().use { input ->
                        Files.newOutputStream(file).use { output ->
                            val buf = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                check(selection.lease.allowed())
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                check(total <= MAX_BYTES)
                                output.write(buf,0,n)
                            }
                        }
                    }
                }
                check(selection.lease.allowed())
                val process = ProcessBuilder("ffprobe","-v","error","-show_format","-show_streams",
                    "-of","json",file.toString()).redirectErrorStream(true).start()
                try {
                    val raw = process.inputStream.bufferedReader().use { it.readText() }
                    check(process.waitFor(15,TimeUnit.SECONDS) && process.exitValue() == 0)
                    val root = Json.parseToJsonElement(raw).jsonObject
                    val format = root["format"]?.jsonObject ?: error("No format")
                    val audio = root["streams"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
                        it["codec_type"]?.jsonPrimitive?.content == "audio"
                    } ?: error("No audio")
                    fun keys(obj: JsonObject) = (obj["tags"] as? JsonObject)?.keys.orEmpty()
                        .filter { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }.sorted()
                    fun number(obj: JsonObject,key: String) = obj[key]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() }?.toString() ?: "absent"
                    check(selection.lease.allowed())
                    println("AMS_PREVIEW_CONTAINER|case=${index+1}|status=observed|fullMs=${item.durationMs}|formatStart=${number(format,"start_time")}|formatDuration=${number(format,"duration")}|audioStart=${number(audio,"start_time")}|audioDuration=${number(audio,"duration")}|formatTags=${keys(format).joinToString(",")}|audioTags=${keys(audio).joinToString(",")}")
                    observed++
                } finally { if (process.isAlive) process.destroyForcibly() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                println("AMS_PREVIEW_CONTAINER|case=${index+1}|status=probe_unavailable")
            } finally {
                unregister()
                calls.revoke()
                Files.deleteIfExists(file)
            }
        }
        assertTrue("No public sample container could be observed",observed > 0)
    }

    companion object { private const val MAX_BYTES = 8L * 1024L * 1024L }
}
