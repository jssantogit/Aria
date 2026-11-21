package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.music.bitchord.ui.components.appleSettingsSwitchColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsSearchTokenVault
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderInventoryEntry
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.providers.ProviderTabRowState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.ui.player.AppleQueueVectors
import com.music.bitchord.ui.components.ArtworkPageBackButton
import com.music.bitchord.ui.components.FloatingLibraryTopBarCircle
import com.music.bitchord.ui.components.NativeToolbarOverflowMenu
import com.music.bitchord.ui.components.NativeCompactMenuItem
import com.music.bitchord.ui.components.standaloneTopControlPadding
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * REA-native presentation for the IDM capability engine.
 *
 * Every switch is an exact (capability, provider instance) choice. Connections
 * are edited by the existing vault-backed screens, never copied into this UI.
 */
@Composable
fun ProvidersScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEditSource: (SourceConfig) -> Unit,
    onEditWebDav: () -> Unit,
    onEditSmb: () -> Unit,
    onConfirmJioSaavn: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSoulseek: () -> Unit,
) {
    val configs by SourceRegistry.configs.collectAsState()
    val providerFlow = remember { ProviderRuntime.state ?: MutableStateFlow(ProviderSessionSnapshot()) }
    val snapshot by providerFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // An unsaved reorder draft must not revive after process recreation.
    var editing by remember { mutableStateOf(false) }
    // Search-category presentation: one active origin tab, never accordion sections.
    var selectedTabName by rememberSaveable { mutableStateOf(ProviderSettingsTab.CATALOG.name) }
    var tabBeforeOrganize by remember { mutableStateOf<String?>(null) }
    var categorySearch by rememberSaveable { mutableStateOf("") }
    var connections by rememberSaveable { mutableStateOf(false) }
    var staged by remember { mutableStateOf<Map<ProviderCapability, List<ProviderKey>>>(emptyMap()) }
    var initialStaged by remember { mutableStateOf<Map<ProviderCapability, List<ProviderKey>>>(emptyMap()) }
    var groupDrafts by remember { mutableStateOf<Map<ProviderSettingsTab, List<ProviderKey>>>(emptyMap()) }
    var detailGroupKey by remember { mutableStateOf<ProviderKey?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<ProviderInventoryEntry?>(null) }
    var discogsToken by remember { mutableStateOf("") }
    var listenBrainzTokenInput by remember { mutableStateOf("") }
    var credentialSaving by remember { mutableStateOf(false) }

    fun cancelEdit() {
        editing = false
        staged = emptyMap()
        initialStaged = emptyMap()
        groupDrafts = emptyMap()
        tabBeforeOrganize?.let { selectedTabName = it }
        tabBeforeOrganize = null
        message = null
    }

    fun back() {
        when {
            saving || credentialSaving -> Unit
            connections -> {
                discogsToken = ""
                listenBrainzTokenInput = ""
                connections = false
            }
            detailGroupKey != null -> {
                discogsToken = ""
                detailGroupKey = null
            }
            editing -> cancelEdit()
            else -> onBack()
        }
    }
    BackHandler(enabled = connections || editing || detailGroupKey != null) { back() }

    // Runtime, not this UI, defines which adapters and accounts are installed.
    val inventory = ProviderRuntime.inventoryForSettings()
    val policy = ProviderCapabilityPolicy(
        descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory),
        choices = snapshot.configured,
        health = snapshot.health,
    )
    val grouped = ProviderSettingsTab.entries.associateWith { tab ->
        ProviderCapabilityRegistry.projectTab(tab, inventory, snapshot, policy)
    }

    fun displayedGroups(tab: ProviderSettingsTab): List<ProviderDisplayGroup> {
        val rows = visibleProviderRows(grouped[tab].orEmpty(), editing)
        val groups = presentableProviderGroups(groupProviderRows(rows))
        return if (editing) orderProviderGroups(groups, groupDrafts[tab].orEmpty()) else groups
    }

    fun openEntry(entry: ProviderInventoryEntry) {
        when (entry.key.providerId) {
            "addon", "custom_module" ->
                configs.firstOrNull { it.id == entry.key.instanceId }?.let(onEditSource)
                    ?: run { info = entry }
            "opensubsonic", "local" -> connections = true
            "lastfm", "listenbrainz", "discogs" -> connections = true
            "soulseek" -> connections = true
            else -> info = entry
        }
    }

    fun publishCompatibility(capability: ProviderCapability) {
        if (capability == ProviderCapability.LYRICS_LOOKUP) {
            ProviderRuntime.authorityOrNull()?.currentLyricsProjection(
                AppSettings.lyricsSourceOrder.value, AppSettings.lyricsSources.value,
            )?.let { projected ->
                AppSettings.lyricsSourceOrder.value = projected.order
                AppSettings.lyricsSources.value = projected.enabled
            }
        }
        if (capability == ProviderCapability.LISTENING_STATISTICS) {
            // Legacy scrobblers still read AppSettings; mirror ONLY after a
            // confirmed canonical preference commit, never on optimistic tap.
            val saved = ProviderRuntime.state?.value?.configured?.get(capability).orEmpty()
            val lastfm = saved.any { it.key.providerId == "lastfm" && it.enabled }
            val listenBrainz = saved.any { it.key.providerId == "listenbrainz" && it.enabled }
            AppSettings.setLastfmEnabled(lastfm)
            AppSettings.setLastfmScrobbleEnabled(lastfm)
            AppSettings.setListenBrainzEnabled(listenBrainz)
        }
        if (capability == ProviderCapability.EXTENSION_PLAYBACK) {
            ProviderRuntime.authorityOrNull()?.currentExtensionsProjection(configs)
                ?.let { projected ->
                    SourceRegistry.applyProviderProjection(projected + configs.filterNot {
                        it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE
                    })
                }
        }
    }

    fun toggle(entry: ProviderInventoryEntry, enabled: Boolean) {
        if (saving || snapshot.status == ProviderSessionStatus.BOOTING) return
        saving = true
        message = null
        scope.launch {
            try {
                val rows = snapshot.configured[entry.capability].orEmpty()
                val previous = rows.firstOrNull { it.key == entry.key }
                val next = if (previous != null) rows.map {
                    if (it.key == entry.key) it.copy(enabled = enabled) else it
                } else rows + ProviderChoice(
                    entry.key, enabled, (rows.maxOfOrNull(ProviderChoice::order) ?: -1) + 1,
                )
                if (ProviderRuntime.setChoices(entry.capability, next)) {
                    publishCompatibility(entry.capability)
                } else message = "Não foi possível salvar a preferência. Tente novamente."
            } finally {
                saving = false
            }
        }
    }

    fun toggleGroup(group: ProviderDisplayGroup, enabled: Boolean) {
        if (saving || snapshot.status == ProviderSessionStatus.BOOTING) return
        val changes = planProviderGroupToggle(group, snapshot.configured, enabled)
        if (changes.isEmpty()) return
        saving = true
        message = null
        scope.launch {
            try {
                if (ProviderRuntime.setChoicesBatch(changes)) {
                    changes.keys.forEach(::publishCompatibility)
                } else message = "Não foi possível salvar as funções do provedor."
            } finally {
                saving = false
            }
        }
    }

    fun saveDiscogsToken() {
        if (saving || credentialSaving ||
            !connections || discogsToken.isBlank()
        ) return
        credentialSaving = true
        message = null
        val secret = discogsToken.trim()
        scope.launch {
            try {
                val stored = withContext(Dispatchers.IO) {
                    DiscogsSearchTokenVault(context).store(secret)
                }
                if (stored && ProviderRuntime.notifyDiscogsCredentialChanged(true)) {
                    discogsToken = ""
                } else {
                    message = "Não foi possível salvar a credencial Discogs com segurança."
                }
            } finally {
                credentialSaving = false
            }
        }
    }

    fun saveListenBrainzToken() {
        if (!connections || saving || credentialSaving || listenBrainzTokenInput.isBlank()) return
        credentialSaving = true
        message = null
        val secret = listenBrainzTokenInput.trim()
        scope.launch {
            try {
                // Reuse the existing ListenBrainz account preference and only
                // refresh readiness; saving a credential must not enable a
                // provider capability which the user previously disabled.
                withContext(Dispatchers.IO) {
                    AppSettings.setListenBrainzToken(secret)
                    ProviderRuntime.refreshListeningCredentialsFromLegacyUi()
                }
                listenBrainzTokenInput = ""
            } catch (_: Exception) {
                message = "Não foi possível salvar o token ListenBrainz."
            } finally {
                credentialSaving = false
            }
        }
    }

    if (connections) {
        val credentialInventory = inventory.filter {
            it.readiness != ProviderReadiness.NOT_IMPLEMENTED
        }
        val available = credentialInventory.map { it.key.providerId }.toSet()
        val discogsReady = credentialInventory.any {
            it.key.providerId == "discogs" &&
                it.readiness == ProviderReadiness.READY
        }
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            ProviderHeader(
                title = "Conexões",
                editing = false,
                saving = false,
                onBack = ::back,
                onOrganize = {},
                showOrganize = false,
                onConnections = {},
                onCancel = {},
                onDone = {},
            )
            message?.let { explanation ->
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            SourcesScreen(
                contentPadding = PaddingValues(
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                modifier = Modifier.weight(1f),
                onEditSource = onEditSource,
                onEditWebDav = onEditWebDav,
                onEditSmb = onEditSmb,
                onConfirmJioSaavn = onConfirmJioSaavn,
                connectionsOnly = true,
                providerCredentials = {
                    ProviderConnectionsCredentials(
                        showDiscogs = "discogs" in available,
                        showListenBrainz = "listenbrainz" in available,
                        showLastFm = "lastfm" in available,
                        showSoulseek = "soulseek" in available,
                        discogsConfigured = discogsReady,
                        listenBrainzConfigured = AppSettings.listenBrainzToken.value.isNotBlank(),
                        discogsToken = discogsToken,
                        listenBrainzToken = listenBrainzTokenInput,
                        saving = credentialSaving || saving,
                        onDiscogsTokenChange = { discogsToken = it },
                        onListenBrainzTokenChange = { listenBrainzTokenInput = it },
                        onSaveDiscogsToken = ::saveDiscogsToken,
                        onSaveListenBrainzToken = ::saveListenBrainzToken,
                        onOpenAccounts = onOpenAccounts,
                        onOpenSoulseek = onOpenSoulseek,
                    )
                },
            )
        }
        return
    }

    fun enterEdit() {
        if (saving || snapshot.status == ProviderSessionStatus.BOOTING) return
        groupDrafts = grouped.mapValues { (_, rows) ->
            presentableProviderGroups(
                groupProviderRows(visibleProviderRows(rows, editing = true)),
            ).map { it.key }
        }
        staged = grouped.values.flatten()
            .filter { it.entry.supportsReordering }
            .groupBy { it.entry.capability }
            .mapValues { (_, rows) -> rows.map { it.entry.key } }
        initialStaged = staged
        tabBeforeOrganize = selectedTabName
        val currentTab = ProviderSettingsTab.entries.firstOrNull { it.name == selectedTabName }
        if (currentTab == null || visibleProviderRows(grouped[currentTab].orEmpty(), editing = true).isEmpty()) {
            ProviderSettingsTab.entries.firstOrNull { tab ->
                visibleProviderRows(grouped[tab].orEmpty(), editing = true).isNotEmpty()
            }?.let { selectedTabName = it.name }
        }
        editing = true
        message = null
    }

    fun move(tab: ProviderSettingsTab, key: ProviderKey, by: Int) {
        val keys = groupDrafts[tab].orEmpty().toMutableList()
        val before = keys.indexOf(key)
        val after = before + by
        if (before < 0 || after !in keys.indices) return
        keys.add(after, keys.removeAt(before))
        groupDrafts = groupDrafts + (tab to keys)
        // One visible provider order; exact per-capability orders remain separate.
        val affected = grouped[tab].orEmpty().map { it.entry.capability }.toSet()
        val updated = providerGroupDraftOrders(keys, staged.filterKeys { it in affected })
        staged = staged + updated
    }

    fun commitEdit() {
        if (!editing || saving) return
        val changes = staged.filter { (capability, draft) ->
            draft != initialStaged[capability]
        }.mapNotNull { (capability, draftOrder) ->
            val configured = snapshot.configured[capability].orEmpty()
            val reordered = reorderCapabilityChoices(configured, draftOrder)
            if (configured != reordered) capability to reordered else null
        }.toMap()
        if (changes.isEmpty()) {
            cancelEdit()
            return
        }
        saving = true
        message = null
        scope.launch {
            try {
                if (ProviderRuntime.setChoicesBatch(changes)) {
                    changes.keys.forEach(::publishCompatibility)
                    cancelEdit()
                } else message = "Não foi possível salvar a ordem. As alterações continuam em edição."
            } finally {
                saving = false
            }
        }
    }

    val selectedDetailTab = ProviderSettingsTab.entries
        .firstOrNull { it.name == selectedTabName } ?: ProviderSettingsTab.CATALOG
    val selectedDetail = detailGroupKey?.let { key ->
        presentableProviderGroups(
            groupProviderRows(grouped[selectedDetailTab].orEmpty()),
        ).firstOrNull { it.key == key }
    }
    if (selectedDetail != null) {
        ProviderCapabilitiesScreen(
            group = selectedDetail,
            configured = snapshot.configured,
            saving = saving || credentialSaving,
            loading = snapshot.status == ProviderSessionStatus.BOOTING,
            error = message,
            contentPadding = contentPadding,
            onBack = ::back,
            onToggle = ::toggle,
            onConfigure = ::openEntry,
        )
    } else {
    Column(
        modifier = Modifier.fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = contentPadding.calculateBottomPadding() + 20.dp),
    ) {
        ProviderHeader(
            title = "Provedores",
            editing = editing,
            saving = saving,
            onBack = ::back,
            onOrganize = ::enterEdit,
            showOrganize = true,
            onConnections = { connections = true },
            onCancel = ::cancelEdit,
            onDone = ::commitEdit,
        )
        message?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp))
        }
        if (snapshot.status == ProviderSessionStatus.BOOTING) {
            Text("Carregando preferências de provedores…",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) {
            Text("As preferências não puderam ser gravadas. Você pode tentar novamente.",
                color = MaterialTheme.colorScheme.error)
        }
        val tabs = ProviderSettingsTab.entries
        val selectedTab = tabs.firstOrNull { it.name == selectedTabName } ?: ProviderSettingsTab.CATALOG
        if (!editing) {
            Spacer(Modifier.height(13.dp))
            PlaylistWizardSearchEntry(
                value = categorySearch,
                onValueChange = { categorySearch = it },
                placeholder = "Buscar nesta categoria",
            )
        }
        Spacer(Modifier.height(20.dp))
        // Exact blue search tabs used inside the playlist filter categories.
        AppleSearchOriginTabs(
            options = tabs.map { it.providerTitle() },
            selectedIndex = tabs.indexOf(selectedTab),
            onSelect = { index ->
                if (!saving) tabs.getOrNull(index)?.let { next ->
                    selectedTabName = next.name
                    categorySearch = ""
                }
            },
        )
        Spacer(Modifier.height(16.dp))

        val selectedGroups = displayedGroups(selectedTab)
        val displayed = if (editing) selectedGroups else searchProviderGroups(
            selectedGroups, categorySearch,
        )
        if (displayed.isEmpty()) {
            Text(
                text = if (editing) "Nenhum provedor reordenável nesta categoria."
                    else if (categorySearch.isNotBlank()) "Nenhum provedor encontrado."
                    else "Nenhum provedor disponível nesta categoria.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
            )
        }
        for ((index, group) in displayed.withIndex()) {
            key(selectedTab, group.key) {
                ProviderSettingRow(
                    group = group,
                    number = index + 1,
                    editing = editing,
                    saving = saving || snapshot.status == ProviderSessionStatus.BOOTING,
                    savedEnabled = providerGroupAllEnabled(group, snapshot.configured),
                    warning = providerGroupWarning(group, snapshot.configured),
                    onOpen = { detailGroupKey = group.key },
                    onToggle = { enabled -> toggleGroup(group, enabled) },
                    canMoveUp = groupDrafts[selectedTab].orEmpty().indexOf(group.key) > 0,
                    canMoveDown = groupDrafts[selectedTab].orEmpty().let { order ->
                        val position = order.indexOf(group.key)
                        position >= 0 && position < order.lastIndex
                    },
                    onMove = { by -> move(selectedTab, group.key, by) },
                )
            }
            if (index < displayed.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    } // Main provider list; capability page replaces it when selected.

    info?.let { entry ->
        // Credential entry is part of Discogs' native Settings page now.
        // Other providers retain their existing informational fallback.
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(entry.displayName) },
            text = {
                Text(when (entry.readiness) {
                    ProviderReadiness.NOT_IMPLEMENTED -> "Este recurso ainda não foi implementado."
                    ProviderReadiness.NEEDS_CONFIGURATION -> "É necessário configurar este provedor."
                    ProviderReadiness.BLOCKED -> "Este provedor está bloqueado."
                    ProviderReadiness.READY -> "A autorização e a prioridade são independentes para cada função."
                })
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Fechar") } },
        )
    }
}

/**
 * Search is scoped to the selected tab, just like the filter field picker.
 * It is a presentation filter only; the canonical choices are never mutated.
 */
internal fun searchProviderRows(
    rows: List<ProviderTabRowState>,
    query: String,
): List<ProviderTabRowState> {
    val normalized = query.trim()
    if (normalized.isBlank()) return rows
    return rows.filter { it.entry.displayName.contains(normalized, ignoreCase = true) }
}

/**
 * Organizar must not display non-draggable rows as empty placeholders.
 * Only capabilities with at least two independently movable providers belong
 * to this mode; normal mode retains the full settings inventory.
 */
internal fun visibleProviderRows(
    rows: List<ProviderTabRowState>,
    editing: Boolean,
): List<ProviderTabRowState> {
    if (!editing) return rows
    val eligible = rows.groupBy { it.entry.capability }
        .filterValues { group -> group.count { it.entry.supportsReordering } > 1 }
        .keys
    return rows.filter { it.entry.supportsReordering && it.entry.capability in eligible }
}

private fun ProviderCapability.organizeScopeTitle(): String = when (this) {
    ProviderCapability.CATALOG_SEARCH -> "Busca"
    ProviderCapability.EDITORIAL_DISCOGRAPHY -> "Discografia"
    ProviderCapability.IDENTITY -> "Identidade / ISRC"
    ProviderCapability.EDITION_METADATA -> "Edições"
    ProviderCapability.ACOUSTIC_IDENTIFICATION -> "Identificação acústica"
    ProviderCapability.ARTIST_ARTWORK -> "Retratos de artistas"
    ProviderCapability.LISTENING_STATISTICS -> "Scrobbling"
    ProviderCapability.PERSONAL_RECOMMENDATIONS -> "Recomendações pessoais"
    ProviderCapability.PUBLIC_PLAYLISTS -> "Playlists públicas"
    ProviderCapability.CHART_MOST_PLAYED -> "Rankings de reprodução"
    ProviderCapability.CHART_SALES -> "Rankings de vendas"
    ProviderCapability.PERSONAL_LIBRARY -> "Biblioteca"
    ProviderCapability.AUDIO_PLAYBACK -> "Reprodução"
    ProviderCapability.LYRICS_SYNCED -> "Letras sincronizadas"
    ProviderCapability.LYRICS_PLAIN -> "Letras simples"
    ProviderCapability.RELEASE_ARTWORK -> "Capas de álbuns"
    ProviderCapability.LYRICS_LOOKUP -> "Letras"
    ProviderCapability.EXTENSION_PLAYBACK -> "Extensões"
    ProviderCapability.DOWNLOAD -> "Downloads"
    else -> name.lowercase().replace('_', ' ')
}

/**
 * Editing preview preserves fixed rows in their original slots. The UI must
 * never show a rearrangement that the durable reorderCapabilityChoices()
 * deliberately refuses to persist. Each capability remains independent.
 */
internal fun previewProviderRows(
    rows: List<ProviderTabRowState>,
    drafts: Map<ProviderCapability, List<ProviderKey>>,
): List<ProviderTabRowState> = rows.groupBy { it.entry.capability }.values.flatMap { group ->
    val requested = drafts[group.first().entry.capability]
        ?.distinct()
        ?.filter { key -> group.any { it.entry.key == key && it.entry.supportsReordering } }
        .orEmpty()
    if (requested.size < 2) group else {
        val byKey = group.associateBy { it.entry.key }
        val reordered = requested.mapNotNull(byKey::get)
        val movable = reordered.mapTo(HashSet()) { it.entry.key }
        var cursor = 0
        group.map { row ->
            if (row.entry.key in movable) reordered[cursor++] else row
        }
    }
}

/**
 * Reordering may change ONLY the slots whose keys were exposed as draggable.
 * Unknown, disconnected or non-reorderable source instances keep their slots
 * and enabled flags; all orders remain nonnegative and deterministic.
 */
internal fun reorderCapabilityChoices(
    original: List<ProviderChoice>,
    dragged: List<ProviderKey>,
): List<ProviderChoice> {
    val order = original.sortedBy(ProviderChoice::order)
    val known = order.mapTo(HashSet()) { it.key }
    val affected = dragged.distinct().filter { it in known }.toSet()
    val reordered = dragged.distinct().mapNotNull { key ->
        order.firstOrNull { it.key == key && key in affected }
    }
    var next = 0
    return order.map { choice ->
        if (choice.key in affected) reordered[next++] else choice
    }.mapIndexed { index, choice -> choice.copy(order = index) }
}

private fun ProviderSettingsTab.providerTitle(): String = when (this) {
    ProviderSettingsTab.CATALOG -> "Catálogo"
    ProviderSettingsTab.ARTWORK -> "Capas"
    ProviderSettingsTab.LYRICS -> "Letras"
    ProviderSettingsTab.STATISTICS_AND_CHARTS -> "Estatísticas"
    ProviderSettingsTab.AUDIO_AND_EXTENSIONS -> "Áudio"
    ProviderSettingsTab.DOWNLOADS -> "Downloads"
}

@Composable
internal fun ProviderHeader(
    title: String,
    editing: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onOrganize: () -> Unit,
    showOrganize: Boolean,
    onConnections: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            top = standaloneTopControlPadding(40.dp),
            bottom = 22.dp,
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Cancelar") }
            TextButton(enabled = !saving, onClick = onDone) { Text("Concluir") }
        } else {
            ArtworkPageBackButton(
                onClick = onBack,
                hazeState = null,
            )
            if (showOrganize) {
                var overflowOpen by remember { mutableStateOf(false) }
                Box {
                    FloatingLibraryTopBarCircle(hazeState = null) {
                        IconButton(
                            onClick = { overflowOpen = true },
                            enabled = !saving,
                            modifier = Modifier.size(40.dp)
                                .testTag("providers-top-overflow"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Opções de provedores",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    NativeToolbarOverflowMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        NativeCompactMenuItem(
                            label = "Organizar",
                            icon = Icons.Rounded.Sort,
                            onClick = {
                                overflowOpen = false
                                onOrganize()
                            },
                        )
                        NativeCompactMenuItem(
                            label = "Conexões",
                            icon = Icons.Rounded.Settings,
                            onClick = {
                                overflowOpen = false
                                onConnections()
                            },
                        )
                    }
                }
            }
        }
    }
    Text(title, style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ProviderSettingRow(
    group: ProviderDisplayGroup,
    number: Int,
    editing: Boolean,
    saving: Boolean,
    savedEnabled: Boolean,
    warning: String?,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 36.dp.toPx() }
    var dragY by remember(group.key, editing) { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(vertical = 6.dp, horizontal = 8.dp)
            .testTag("providers-field-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editing) {
            Box(
                modifier = Modifier.size(48.dp)
                    .semantics {
                        contentDescription = "Reorganizar " + group.title
                        customActions = buildList {
                            if (canMoveUp) add(CustomAccessibilityAction("Mover para cima") {
                                onMove(-1)
                                true
                            })
                            if (canMoveDown) add(CustomAccessibilityAction("Mover para baixo") {
                                onMove(1)
                                true
                            })
                        }
                    }
                    .pointerInput(group.tab, group.key, editing) {
                        detectDragGestures(
                            onDragEnd = { dragY = 0f },
                            onDragCancel = { dragY = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragY += amount.y
                            if (abs(dragY) >= threshold) {
                                onMove(if (dragY > 0f) 1 else -1)
                                dragY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppleQueueVectors.DragHandle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "${number}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(width = 24.dp, height = 28.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f)
                .heightIn(min = 48.dp)
                .clickable(enabled = !editing && !saving, onClick = onOpen),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (!editing) {
            val available = group.rows.any {
                it.entry.readiness == ProviderReadiness.READY
            }
            Switch(
                checked = savedEnabled,
                onCheckedChange = onToggle,
                enabled = !saving && available,
                colors = appleSettingsSwitchColors(),
                modifier = Modifier.semantics {
                    contentDescription = group.title
                    stateDescription = when {
                        warning == "Parcialmente ativado" -> "Parcialmente ativado"
                        savedEnabled -> "Ativado"
                        else -> "Desativado"
                    }
                },
            )
        }
    }
}

private fun ProviderSettingsTab.providerIcon() = when (this) {
    ProviderSettingsTab.CATALOG -> Icons.Rounded.LibraryMusic
    ProviderSettingsTab.ARTWORK -> Icons.Rounded.Image
    ProviderSettingsTab.LYRICS -> Icons.Rounded.GraphicEq
    ProviderSettingsTab.STATISTICS_AND_CHARTS -> Icons.Rounded.Tune
    ProviderSettingsTab.AUDIO_AND_EXTENSIONS -> Icons.Rounded.Headphones
    ProviderSettingsTab.DOWNLOADS -> Icons.Rounded.Download
}
