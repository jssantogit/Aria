package com.music.bitchord.data.catalog.provider.applemusicweb

import android.database.sqlite.SQLiteException
import com.music.bitchord.data.catalog.AppleMusicWebLandingCacheEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI

/** The landing is an aggregate, not a cached HTTP response. Its own versioned
 * identity must not collide with raw curator or room operation keys.
 */
internal interface AppleMusicWebLandingCache {
    suspend fun read(storefront: String, tag: String): AppleMusicWebCuratorLanding?
    suspend fun writeComplete(storefront: String, tag: String, value: AppleMusicWebCuratorLanding)
}

internal class RoomAppleMusicWebLandingCache(
    private val database: CanonicalCatalogDatabase,
    private val access: ProviderCachedReadAccess = ProviderCachedReadAccess(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : AppleMusicWebLandingCache {
    private val capability = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val version = 1
    private val maxAge = 15 * 60_000L
    private val maxPayload = 1_000_000
    private val json = Json { ignoreUnknownKeys = false }
    private fun scope(storefront: String, tag: String) =
        storefront.matches(Regex("[a-z]{2}")) && amsSupportsEditorialTag(tag)

    // Store only known public URL shapes, never userinfo, queries, fragments
    // or signed playback locations. Artwork templates may contain braces.
    private fun publicUrl(raw: String?, artwork: Boolean): String? {
        if (raw == null || raw.length > 2048 || raw.any(Char::isISOControl)) return null
        val url = try { URI(raw.replace("{w}", "100").replace("{h}", "100").replace("{f}", "jpg")) }
            catch (_: IllegalArgumentException) { return null }
            catch (_: java.net.URISyntaxException) { return null }
        val host = url.host ?: return null
        if (url.scheme != "https" || url.userInfo != null || url.rawQuery != null ||
            url.rawFragment != null || url.port != -1) return null
        if (artwork && !host.endsWith(".mzstatic.com")) return null
        if (!artwork && host != "music.apple.com") return null
        return raw
    }

    private fun validated(
        storefront: String, tag: String, value: AppleMusicWebCuratorLanding,
    ): AppleMusicWebCuratorLanding? {
        if (!scope(storefront, tag) || value.tag != tag || value.roomFailures != 0 ||
            value.albums.isEmpty() || value.albums.size > 1500 || value.curator.rooms.size > 100 ||
            value.sourceRows < value.distinctCandidates || value.distinctCandidates < value.albums.size ||
            value.rejectedDistinct != value.distinctCandidates - value.albums.size ||
            value.rejectedObserved !in value.rejectedDistinct..value.distinctCandidates ||
            value.roomReads !in 1..15 || value.searchReads !in 0..4 ||
            value.searchCandidates !in 0..100 ||
            value.provenance !in setOf("search", "public_reference") ||
            value.searchReason !in setOf("success", "denied", "stale", "failed", "not_attempted")) return null
        val identity = value.curator.identity
        if (!identity.id.matches(Regex("[0-9]{1,12}")) ||
            !appleMusicWebCuratorMatches(identity, storefront, tag) ||
            value.shared != appleMusicWebCuratorIsSharedForTag(identity, tag) ||
            value.curator.rooms.any { it.curatorId != identity.id || it.storefront != storefront ||
                it.genreId != null || !it.id.matches(Regex("[0-9]{1,12}")) }) return null
        val ids = hashSetOf<String>()
        val albums = value.albums.map { album ->
            val item = album.item
            if (album.room !in value.curator.rooms || !item.id.matches(Regex("[0-9]{1,12}")) ||
                !ids.add(item.scopedId) || album.evidence == AppleMusicWebCuratedAlbumEvidence.REJECTED ||
                appleMusicWebCuratedAlbumEvidence(identity, tag, item, album.room) != album.evidence)
                return null
            album.copy(item = item.copy(
                artworkUrl = publicUrl(item.artworkUrl, true),
                storeUrl = publicUrl(item.storeUrl, false),
            ))
        }
        return value.copy(albums = albums)
    }

    override suspend fun read(storefront: String, tag: String): AppleMusicWebCuratorLanding? {
        if (!scope(storefront, tag)) return null
        return access.read<AppleMusicWebCuratorLanding?>(capability, provider, null) {
            try {
                val entry = database.catalogDao().amsLandingCache(storefront, tag) ?: return@read null
                val now = clockMs()
                if (entry.storefront != storefront || entry.tag != tag || entry.schemaVersion != version ||
                    entry.fetchedAtMs < 0 || entry.fetchedAtMs > now || entry.expiresAtMs <= now ||
                    entry.expiresAtMs <= entry.fetchedAtMs || entry.expiresAtMs - entry.fetchedAtMs > maxAge ||
                    entry.payload.length > maxPayload) return@read null
                validated(storefront, tag, json.decodeFromString(AppleMusicWebCuratorLanding.serializer(), entry.payload))
            } catch (_: SerializationException) { null }
              catch (_: SQLiteException) { null }
        }
    }

    override suspend fun writeComplete(storefront: String, tag: String, value: AppleMusicWebCuratorLanding) {
        if (!scope(storefront, tag)) return
        access.read(capability, provider, Unit) {
            try {
                val safe = validated(storefront, tag, value) ?: return@read
                val now = clockMs()
                if (now < 0 || now > Long.MAX_VALUE - maxAge) return@read
                val payload = json.encodeToString(AppleMusicWebCuratorLanding.serializer(), safe)
                if (payload.length > maxPayload) return@read
                database.catalogDao().persistAmsLandingCache(AppleMusicWebLandingCacheEntity(
                    storefront, tag, version, now, now + maxAge, payload,
                ))
            } catch (_: SerializationException) { /* Cache miss, not a failed live read. */ }
              catch (_: SQLiteException) { /* Cache is optional, not editorial truth. */ }
        }
    }
}
