package com.music.bitchord.ui.screens

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderReadiness

/**
 * Human-facing descriptions for the advanced provider settings page.
 * Distinct from the runtime capability and its durable authorization.
 * No provider-specific copies: every source uses the same vocabulary.
 */
internal data class ProviderFunctionText(val title: String, val description: String)

internal fun ProviderCapability.functionSettingsText(): ProviderFunctionText = when (this) {
    ProviderCapability.CATALOG_SEARCH -> ProviderFunctionText(
        "Busca", "Pesquisa artistas, álbuns e músicas nesta fonte.")
    ProviderCapability.EDITORIAL_DISCOGRAPHY -> ProviderFunctionText(
        "Discografia", "Mostra os álbuns, singles e outros lançamentos dos artistas.")
    ProviderCapability.IDENTITY -> ProviderFunctionText(
        "Identidade e ISRC", "Identifica artistas e gravações por identificadores e códigos ISRC.")
    ProviderCapability.EDITION_METADATA -> ProviderFunctionText(
        "Edições", "Consulta versões e edições diferentes de um lançamento.")
    ProviderCapability.ACOUSTIC_IDENTIFICATION -> ProviderFunctionText(
        "Identificação acústica", "Identifica gravações por sua impressão digital de áudio.")
    ProviderCapability.RELEASE_ARTWORK -> ProviderFunctionText(
        "Capas de álbuns", "Busca imagens de capa dos lançamentos.")
    ProviderCapability.ARTIST_ARTWORK -> ProviderFunctionText(
        "Fotos de artistas", "Obtém retratos e imagens dos artistas.")
    ProviderCapability.LYRICS_LOOKUP -> ProviderFunctionText(
        "Letras", "Consulta as letras disponíveis nesta fonte.")
    ProviderCapability.LYRICS_SYNCED -> ProviderFunctionText(
        "Letras sincronizadas", "Consulta letras com marcações de tempo da música.")
    ProviderCapability.LYRICS_PLAIN -> ProviderFunctionText(
        "Letras simples", "Consulta letras sem sincronização temporal.")
    ProviderCapability.CHART_MOST_PLAYED -> ProviderFunctionText(
        "Rankings de reprodução", "Mostra músicas e artistas mais reproduzidos.")
    ProviderCapability.CHART_SALES -> ProviderFunctionText(
        "Rankings de vendas", "Consulta classificações de vendas de músicas e lançamentos.")
    ProviderCapability.LISTENING_STATISTICS -> ProviderFunctionText(
        "Scrobbling", "Registra na sua conta as músicas que você escuta.")
    ProviderCapability.PERSONAL_RECOMMENDATIONS -> ProviderFunctionText(
        "Recomendações", "Sugere músicas com base no seu histórico de escuta.")
    ProviderCapability.PUBLIC_PLAYLISTS -> ProviderFunctionText(
        "Playlists públicas", "Consulta playlists públicas disponibilizadas pela fonte.")
    ProviderCapability.PERSONAL_LIBRARY -> ProviderFunctionText(
        "Biblioteca", "Consulta os itens da sua biblioteca conectada.")
    ProviderCapability.AUDIO_PLAYBACK -> ProviderFunctionText(
        "Reprodução", "Reproduz áudio utilizando esta fonte.")
    ProviderCapability.EXTENSION_PLAYBACK -> ProviderFunctionText(
        "Extensões", "Reproduz áudio fornecido por extensões instaladas.")
    ProviderCapability.DOWNLOAD -> ProviderFunctionText(
        "Downloads", "Baixa músicas por meio desta fonte.")
}

internal fun ProviderReadiness.functionSettingsWarning(): String? = when (this) {
    ProviderReadiness.READY -> null
    ProviderReadiness.NEEDS_CONFIGURATION -> "Configuração necessária"
    ProviderReadiness.NOT_IMPLEMENTED -> "Ainda não implementado"
    ProviderReadiness.BLOCKED -> "Bloqueado"
}
