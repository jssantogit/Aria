package com.music.bitchord.ui.screens

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderInventoryEntry
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderTabRowState

/**
 * Settings shows one provider INSTANCE per tab, not one row per capability.
 * This grouping never mutates the runtime's exact (capability, key) contract:
 * MusicBrainz Search/Discography/Identity remain different authorizations.
 */
internal data class ProviderDisplayGroup(
    val key: ProviderKey,
    val tab: ProviderSettingsTab,
    val title: String,
    val rows: List<ProviderTabRowState>,
)

/** Do not use suffix stripping for user-owned addons (their names are arbitrary). */
internal fun providerSettingsDisplayName(entry: ProviderInventoryEntry): String =
    when (entry.key.providerId) {
        "musicbrainz" -> "MusicBrainz"
        "itunes" -> "Apple / iTunes"
        "discogs" -> "Discogs"
        "lastfm" -> "Last.fm"
        "listenbrainz" -> "ListenBrainz"
        "apple_artist_art" -> "Apple Music"
        "opensubsonic" -> "Navidrome / OpenSubsonic"
        "local" -> "Biblioteca local"
        else -> entry.displayName
    }

internal fun groupProviderRows(rows: List<ProviderTabRowState>): List<ProviderDisplayGroup> =
    rows.groupBy { it.entry.tab to it.entry.key }.map { (_, members) ->
        val first = members.first().entry
        ProviderDisplayGroup(first.key, first.tab, providerSettingsDisplayName(first), members)
    }

internal fun searchProviderGroups(
    groups: List<ProviderDisplayGroup>, query: String,
): List<ProviderDisplayGroup> {
    val needle = query.trim()
    if (needle.isEmpty()) return groups
    return groups.filter { group ->
        group.title.contains(needle, ignoreCase = true) ||
            group.rows.any { it.entry.displayName.contains(needle, ignoreCase = true) }
    }
}

private fun savedGroupChoice(
    row: ProviderTabRowState,
    choices: Map<ProviderCapability, List<ProviderChoice>>,
): Boolean = choices[row.entry.capability].orEmpty().any {
    it.key == row.entry.key && it.enabled
}

/**
 * A single switch is checked only when ALL ready functions are authorized.
 * A mixed group is explicitly partial, never silently collapsed to "enabled".
 */
internal fun providerGroupAllEnabled(
    group: ProviderDisplayGroup,
    choices: Map<ProviderCapability, List<ProviderChoice>>,
): Boolean {
    val ready = group.rows.filter { it.entry.readiness == ProviderReadiness.READY }
    return ready.isNotEmpty() && ready.all { savedGroupChoice(it, choices) }
}

internal fun providerGroupWarning(
    group: ProviderDisplayGroup,
    choices: Map<ProviderCapability, List<ProviderChoice>>,
): String? {
    val ready = group.rows.filter { it.entry.readiness == ProviderReadiness.READY }
    if (ready.isEmpty()) return when {
        group.rows.any { it.entry.readiness == ProviderReadiness.NEEDS_CONFIGURATION } ->
            "Configuração necessária"
        group.rows.any { it.entry.readiness == ProviderReadiness.BLOCKED } -> "Bloqueado"
        else -> "Não implementado"
    }
    if (ready.any { savedGroupChoice(it, choices) } &&
        ready.any { !savedGroupChoice(it, choices) }) return "Parcialmente ativado"
    return null
}

/**
 * One transaction for one provider group. Enabling touches READY functions
 * only, never invents authorization for unavailable or unimplemented adapters.
 * Disabling revokes every existing authorization within the tab, including
 * previously enabled but now unconfigured functions. All other providers,
 * capability orders and flags are copied untouched.
 *
 * No-op batches stay empty to avoid unnecessary preference revisions.
 */
internal fun planProviderGroupToggle(
    group: ProviderDisplayGroup,
    configured: Map<ProviderCapability, List<ProviderChoice>>,
    enabled: Boolean,
): Map<ProviderCapability, List<ProviderChoice>> =
    group.rows
        .filter { !enabled || it.entry.readiness == ProviderReadiness.READY }
        .groupBy { it.entry.capability }
        .mapNotNull { (capability, rows) ->
            val previous = configured[capability].orEmpty()
            val keys = rows.mapTo(HashSet()) { it.entry.key }
            val next = previous.map { choice ->
                if (choice.key in keys) choice.copy(enabled = enabled) else choice
            }.toMutableList()
            if (enabled) {
                val existing = previous.mapTo(HashSet()) { it.key }
                rows.filter { it.entry.key !in existing }.forEach { row ->
                    next += ProviderChoice(
                        row.entry.key, enabled = true,
                        order = (next.maxOfOrNull(ProviderChoice::order) ?: -1) + 1,
                    )
                }
            }
            if (next == previous) null else capability to next.toList()
        }.toMap()

/**
 * The sole drag vector reorders visible provider identities, but persists
 * priorities separately in each capability. A provider absent from a given
 * capability is never added to that priority list. Non-draggable slots are
 * still protected by reorderCapabilityChoices at commit time.
 */
internal fun providerGroupDraftOrders(
    orderedKeys: List<ProviderKey>,
    drafts: Map<ProviderCapability, List<ProviderKey>>,
): Map<ProviderCapability, List<ProviderKey>> = drafts.mapValues { (_, keys) ->
    val selected = orderedKeys.filter { it in keys }
    if (selected.size < 2) keys else {
        val selectedSet = selected.toSet()
        var i = 0
        keys.map { if (it in selectedSet) selected[i++] else it }
    }
}

internal fun orderProviderGroups(
    groups: List<ProviderDisplayGroup>, order: List<ProviderKey>,
): List<ProviderDisplayGroup> {
    if (order.isEmpty()) return groups
    val rank = order.withIndex().associate { it.value to it.index }
    return groups.withIndex().sortedWith(compareBy({ rank[it.value.key] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}
