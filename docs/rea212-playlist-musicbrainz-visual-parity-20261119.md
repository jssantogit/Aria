# REA #212 — parity visual de playlists com MusicBrainz (19/11/2026)

## Evidência e problema

Captura física da playlist normal `kk` com 19 entradas: faixas locais de LE SSERAFIM/SAY MY NAME com capas reais seguidas de entradas de metadados MusicBrainz (“Sem áudio”), antes escurecidas e exibidas em quadrantes de capa sem mídia. Entrada metadata-only é **gravação identificada**, não MediaAsset nem autorização de fila.

Na auditoria de código:
- `CanonicalPlaylistTrackRow` herdava conteúdo visual, mas não o mínimo de **68 dp** das listas normais de Biblioteca/Navidrome; álbuns numerados mantêm o mínimo próprio de 54 dp.
- Gravações sem áudio tinham opacidade reduzida e não abriam menu contextual; já corrigido antes desta rodada.
- O mosaico tratava `remoteMediaAssetId` sem artwork como se fosse imagem e, para 2–3 capas distintas, descartava capas úteis em favor da primeira.
- O placeholder sem capa era dimensionado rigidamente a **180 dp**, inclusive ao renderizar capa da playlist em miniaturas compactas.
- As previews de playlists na Biblioteca consideravam somente quatro IDs e descartavam metadata-only **antes** da possível resolução de capa MusicBrainz.
- O fluxo de capas MusicBrainz verificado do catálogo (Recording MBID → Track de edição Official → ReleaseGroup → Front-v5) existia, mas não era reutilizado nas playlists.

## Contrato único aplicado

1. **Geometria das faixas:** `NativeDetailTrackRowLayout` comum; mínimo 68 dp para linhas normais de playlist local/canônica/pública, artwork 52 dp e raio 8 dp; mesmo `NativePlaylistMissingArtwork` com nota neutra para dado sem capa.
2. **Conteúdo/ratings:** artista e título normais (sem alpha 0,45); "Sem áudio" explícito e sem tap/queue/play. Não reservar rating vazio; manter estrelas de Recording caso a faixa metadata-only tenha rating real. Ao tocar no item destacado sem áudio, não disparar reprodução fictícia.
3. **Capa dos álbuns/playlist:** colagem ocupa totalmente a área com 1, 2, 3 ou 4 capas **distintas e comprovadas** (1 = inteira; 2 = metades; 3 = metade + pilha; 4 = 2x2). Não completar quadrantes com notas genéricas. Capa escolhida pelo usuário preserva prioridade; vazio escala à dimensão do host (miniatura ou hero).
4. **MusicBrainz metadata-only:** nova leitura Room em lote dos MBIDs visíveis. Somente relacionamento Official indexado e cache Front-v5 não expirado (TTL 30 dias) com host HTTPS de arquivo confiável podem produzir URL visual. Nenhuma heurística por nome/artista, nenhum HTTP por linha, nenhuma URI inventada e **nenhuma promoção a MediaAsset**. Sem prova, usar nota neutra. A mesma seleção alimenta linhas, hero e cartões da Biblioteca.
5. **Cartões/sheets:** `CanonicalStaticPlaylistCoverArt` compartilha a mesma implementação do detalhe e do picker. Busca prefixo limitado de 24 entradas para alcançar arte válida depois de itens sem capa, sem varrer indefinidamente playlists grandes.
6. **Interações:** seleção, menus de long press, swipe/fila e remoção por RecordingId mantêm limites de proveniência. `NativePlaylistDetailFrame`/header continuam compartilhados com o contexto público.

## Verificação automatizada

- `CanonicalPlaylistMixedMusicBrainzTest`: disponibilidade em lista mista, exclusão de arte sem prova, mapeamento Official/verificado por MBID, URL maliciosa recusada, prioridade do palette art e remoção exata sem alterar playlist.
- `CanonicalPlaylistArtworkParityComposeTest`: nota neutra, arte 52dp, remoção do espaço de rating vazio, composição com 2 imagens e placeholder dimensionado a 44dp.
- `PlaylistLibraryVisualReuseTest`: seleção estável, deduplicação de artes, varredura limitada de 24 membros.
- `CanonicalPlaylistTrackMenuComposeTest`: remoção de entry sem áudio sem oferecer play ou queue falsos.

## Gates restantes

- Concluir Android CI e gates Desktop/TV/Wear no **HEAD final** da branch.
- Produzir APK devDebug do HEAD aprovado e validar fisicamente: miniatura na Biblioteca, playlist aberta, MusicBrainz com cache vazio/expirado/positivo, local+Navidrome, dark palette, fontScale/densidade, long press e reprodução parcial.
- Não declarar equivalência pixel-perfect ou sucesso em aparelho sem imagens e smoke do APK. PR #212 permanece Draft, sem merge; IDM/AMS não são alteradas.
