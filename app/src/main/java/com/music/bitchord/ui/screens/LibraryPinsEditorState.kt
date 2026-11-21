package com.music.bitchord.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * The Apple Music pin editor is a mode of the existing Library, not another
 * destination. Keep its draft separate from the three canonical pin stores.
 *
 * Only typed keys are ordered. No title, artwork URL, source name or missing
 * MediaAsset is ever used as a pin identity.
 */
internal fun libraryCanonicalPinKey(pin: CanonicalLibraryPin): String =
    "canonical:${pin.selection.type.name}:${pin.selection.entityId}"

internal fun libraryPlaylistPinKey(pin: LibraryPlaylistPin): String =
    "playlist:${pin.id}"

internal fun libraryCatalogPinKey(pin: CatalogMetadataPin): String =
    "catalog:${pin.browseId}"

internal fun canonicalLibraryPinKeys(
    canonical: List<CanonicalLibraryPin>,
    playlists: List<LibraryPlaylistPin>,
    catalog: List<CatalogMetadataPin>,
): List<String> = buildList {
    canonical.forEach { add(libraryCanonicalPinKey(it)) }
    playlists.forEach { add(libraryPlaylistPinKey(it)) }
    catalog.forEach { add(libraryCatalogPinKey(it)) }
}.distinct()

/** Old installations keep their original order; newly pinned items append. */
internal fun orderedLibraryPinKeys(
    persisted: List<String>,
    available: List<String>,
): List<String> {
    val live = available.toSet()
    val old = persisted.filter { it in live }.distinct()
    return old + available.filter { it !in old }.distinct()
}

internal fun encodeLibraryPinOrder(ids: List<String>): String =
    JSONArray().apply {
        ids.asSequence().filter { it.length in 1..600 }.distinct().take(300)
            .forEach { put(it) }
    }.toString()

internal fun decodeLibraryPinOrder(raw: String?): List<String> {
    val array = runCatching { JSONArray(raw ?: "[]") }.getOrNull()
        ?: return emptyList()
    return (0 until minOf(array.length(), 900)).mapNotNull { i ->
        array.optString(i).takeIf { it.length in 1..600 && (
            it.startsWith("canonical:") || it.startsWith("playlist:") ||
                it.startsWith("catalog:")
            ) }
    }.distinct().take(300)
}

internal enum class LibraryPinEditResult {
    SAVED, STALE, WRITE_FAILED,
}

/**
 * Commit order + three pin families in a SINGLE SharedPreferences transaction
 * so removal and move are never half-applied on disk. Source repositories
 * remain the source of truth for typed identity and playback eligibility.
 */
internal class LibraryPinsEditorState(
    context: Context,
    // Production uses the shared repositories; tests may supply repositories
    // backed by an isolated preferences file to avoid Robolectric's static
    // singletons leaking across distinct Application test sandboxes.
    private val canonical: CanonicalLibraryPinRepository =
        CanonicalLibraryPinRepository.get(context.applicationContext),
    private val playlists: LibraryPlaylistPinRepository =
        LibraryPlaylistPinRepository.get(context.applicationContext),
    private val catalog: CatalogMetadataPinRepository =
        CatalogMetadataPinRepository.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)

    var editing by mutableStateOf(false)
        private set
    var persistedOrder by mutableStateOf(
        decodeLibraryPinOrder(prefs.getString(ORDER_KEY, null)),
    )
        private set

    val draft = mutableStateListOf<String>()
    private var startingKeys: Set<String> = emptySet()

    fun allPins(): List<String> =
        canonicalLibraryPinKeys(canonical.pins.value, playlists.pins.value, catalog.pins.value)

    fun hasPins(): Boolean = allPins().isNotEmpty()

    fun displayOrder(available: List<String>): List<String> =
        if (editing) draft.filter { it in available.toSet() }
        else orderedLibraryPinKeys(persistedOrder, available)

    fun start() {
        val live = allPins()
        if (live.isEmpty()) return
        persistedOrder = decodeLibraryPinOrder(prefs.getString(ORDER_KEY, null))
        startingKeys = live.toSet()
        draft.clear()
        draft.addAll(orderedLibraryPinKeys(persistedOrder, live))
        editing = true
    }

    fun remove(id: String) {
        if (editing) draft.remove(id)
    }

    fun move(id: String, targetIndex: Int) {
        if (!editing) return
        val from = draft.indexOf(id)
        if (from < 0) return
        val to = targetIndex.coerceIn(0, draft.lastIndex)
        if (from == to) return
        draft.removeAt(from)
        draft.add(to, id)
    }

    /** A system Back/navigation exit discards the draft; only ✓ persists it. */
    fun cancel() {
        editing = false
        startingKeys = emptySet()
        draft.clear()
    }

    fun save(): LibraryPinEditResult {
        if (!editing) return LibraryPinEditResult.STALE
        // Do not silently discard pins added or removed from another screen.
        if (allPins().toSet() != startingKeys) return LibraryPinEditResult.STALE
        val selected = draft.toSet()
        val nextCanonical = canonical.pins.value.filter {
            libraryCanonicalPinKey(it) in selected
        }
        val nextPlaylists = playlists.pins.value.filter {
            libraryPlaylistPinKey(it) in selected
        }
        val nextCatalog = catalog.pins.value.filter {
            libraryCatalogPinKey(it) in selected
        }
        val nextOrder = draft.toList()
        val saved = prefs.edit()
            .putString(CanonicalLibraryPinRepository.KEY, encodeCanonicalLibraryPins(nextCanonical))
            .putString(LibraryPlaylistPinRepository.KEY, encodeLibraryPlaylistPins(nextPlaylists))
            .putString(CatalogMetadataPinRepository.KEY, encodeCatalogMetadataPins(nextCatalog))
            .putString(ORDER_KEY, encodeLibraryPinOrder(nextOrder))
            .commit()
        if (!saved) return LibraryPinEditResult.WRITE_FAILED
        canonical.reloadFromDisk()
        playlists.reloadFromDisk()
        catalog.reloadFromDisk()
        persistedOrder = nextOrder
        cancel()
        return LibraryPinEditResult.SAVED
    }

    companion object {
        internal const val ORDER_KEY = "pinned_library_global_order_v1"
    }
}
