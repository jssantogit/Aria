package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

/**
 * Projection of existing user choices, NEVER a new provider default.
 *
 * The legacy lyrics selector is a single lookup list: a provider advertised as
 * word-synced may still return plain/line-synced lyrics. Migration therefore
 * does not invent independent enabled flags for output granularities.
 *
 * SourceRegistry describes configured playback addons, not MediaStore,
 * OpenSubsonic or other personal library transports. Import under a separate
 * capability so that future AUDIO_PLAYBACK does not accidentally exclude them.
 *
 * Called with snapshots AFTER AppSettings and SourceRegistry have initialized.
 * Does not touch either legacy setting, credentials, URLs or playback behavior.
 */
internal fun legacyProviderChoices(
    lyricsOrder: List<LyricsSource>,
    enabledLyrics: Set<LyricsSource>,
    sources: List<SourceConfig>,
): Map<ProviderCapability, List<ProviderChoice>> {
    val result = mutableMapOf<ProviderCapability, List<ProviderChoice>>()

    // Existing order is the user's preference; append only genuinely absent
    // offered entries after that order. Hidden/retired hosts are never revived.
    val saved = lyricsOrder.filter { it in LyricsSource.offered }.distinct()
    val ordered = saved + LyricsSource.offered.filterNot { it in saved }
    result[ProviderCapability.LYRICS_LOOKUP] = ordered.mapIndexed { index, source ->
        ProviderChoice(
            key = ProviderKey(source.name.lowercase(Locale.ROOT)),
            enabled = source in enabledLyrics,
            order = index,
        )
    }

    // Do not import retired JioSaavn, built-in YouTube or deprecated MODULE.
    // Configuration identity (not a private URL) is enough for an instance.
    val extensionSources = sources.filter { config ->
        config.kind == SourceKind.ADDON || config.kind == SourceKind.CUSTOM_MODULE
    }.distinctBy(SourceConfig::id)
    if (extensionSources.isNotEmpty()) {
        result[ProviderCapability.EXTENSION_PLAYBACK] = extensionSources
            .mapIndexed { index, config ->
                ProviderChoice(
                    key = ProviderKey(
                        providerId = when (config.kind) {
                            SourceKind.ADDON -> "addon"
                            else -> "custom_module"
                        },
                        instanceId = config.id,
                    ),
                    enabled = config.enabled,
                    order = index,
                )
            }
    }
    return result
}
