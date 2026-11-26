package com.music.bitchord

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.music.bitchord.data.catalog.*
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.lang.reflect.Proxy

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class AppleMusicWebLandingPersistenceTest {
    private val capability = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key,setOf(capability),ProviderReadiness.READY)),
        mapOf(capability to listOf(ProviderChoice(key,enabled,0))),
    )
    private fun landing(): AppleMusicWebCuratorLanding {
        val identity = AppleMusicWebCuratorRef("976439585","br","Apple Music Soul e funk")
        val room = AppleMusicWebRoomRef("br",null,"456","Lançamentos recentes",identity.id)
        val item = AppleMusicWebItem("1",AppleMusicWebKind.ALBUM,"br","Combined",
            genreNames=listOf("R&B/Soul"),artworkUrl="https://is1-ssl.mzstatic.com/image/{w}x{h}.jpg",
            storeUrl="https://music.apple.com/br/album/1?token=private")
        return AppleMusicWebCuratorLanding("soul",AppleMusicWebCurator(identity,listOf(room)),
            listOf(AppleMusicWebCuratedAlbum(item,AppleMusicWebCuratedAlbumEvidence.SOUL_COMBINED_LABEL,room)),
            true,"public_reference",0,1,"denied",1,1,0,0,1,0,false)
    }

    @Test fun persistentRoundtripEnforcesExpiryScopeAndRevocation(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "ams-landing-cache-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context,CanonicalCatalogDatabase::class.java,name).allowMainThreadQueries().build()
        var now = 1000L
        val gate = ProviderPolicyGate(policy(true))
        fun cache() = RoomAppleMusicWebLandingCache(db,ProviderCachedReadAccess { gate }) { now }
        try {
            cache().writeComplete("br","soul",landing())
            val entry = db.catalogDao().amsLandingCache("br","soul")!!
            assertFalse(entry.payload.contains("private"))
            assertEquals(901000L,entry.expiresAtMs)
            db.close()
            db = Room.databaseBuilder(context,CanonicalCatalogDatabase::class.java,name).allowMainThreadQueries().build()
            val result = cache().read("br","soul")!!
            assertEquals(1,result.combinedLabels)
            assertNull(result.albums.single().item.storeUrl)
            assertEquals(landing().albums.single().item.artworkUrl,result.albums.single().item.artworkUrl)
            assertNull(cache().read("us","soul"))
            assertNull(cache().read("br","rnb"))
            gate.replace(policy(false))
            assertNull(cache().read("br","soul"))
            now = 2000
            cache().writeComplete("br","soul",landing())
            assertEquals(1000L,db.catalogDao().amsLandingCache("br","soul")!!.fetchedAtMs)
            gate.replace(policy(true))
            assertNotNull(cache().read("br","soul"))
            cache().writeComplete("br","soul",landing().copy(roomFailures=1))
            assertEquals(1000L,db.catalogDao().amsLandingCache("br","soul")!!.fetchedAtMs)
            now = 901000L
            assertNull(cache().read("br","soul"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun corruptOrForeignEvidenceCannotBecomeCacheHit(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val gate = ProviderPolicyGate(policy(true))
            val cache = RoomAppleMusicWebLandingCache(db,ProviderCachedReadAccess { gate }) { 1000L }
            val value = landing()
            cache.writeComplete("br","soul",value.copy(albums=listOf(value.albums.single().copy(
                evidence=AppleMusicWebCuratedAlbumEvidence.EXACT_GENRE_LABEL))))
            assertNull(db.catalogDao().amsLandingCache("br","soul"))
            cache.writeComplete("br","soul",value)
            val entry = db.catalogDao().amsLandingCache("br","soul")!!
            db.catalogDao().upsertAmsLandingCache(entry.copy(payload="{broken"))
            assertNull(cache.read("br","soul"))
            db.catalogDao().upsertAmsLandingCache(entry.copy(schemaVersion=99))
            assertNull(cache.read("br","soul"))
        } finally { db.close() }
    }

    @Test fun migrationAddsOnlySourceOwnedCache() {
        val raw = SQLiteDatabase.create(null)
        try {
            raw.execSQL("CREATE TABLE catalog_recording (id TEXT PRIMARY KEY, title TEXT)")
            raw.execSQL("INSERT INTO catalog_recording VALUES ('local','Keep me')")
            val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                check(method.name == "execSQL")
                raw.execSQL(args!![0] as String)
                null
            } as SupportSQLiteDatabase
            CanonicalCatalogDatabase.MIGRATION_17_18.migrate(db)
            raw.rawQuery("SELECT title FROM catalog_recording WHERE id='local'",null).use {
                assertTrue(it.moveToFirst()); assertEquals("Keep me",it.getString(0))
            }
            raw.rawQuery("SELECT COUNT(*) FROM catalog_ams_landing_cache",null).use {
                assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
            }
        } finally { raw.close() }
    }
}
