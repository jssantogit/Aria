# REA #212 — Auditoria de destaque do pressionamento longo (19/11/2026)

**Escopo:** somente REA/UI do fork BitChord; PR #212 permanece **OPEN/DRAFT**, sem merge e sem alterações no IDM. Evidência primária: quatro capturas físicas enviadas pelo proprietário. A referência correta é o destaque de &Our Vibe (card opaco) e Aespa (arte/identidade); a faixa «00:00 (Zero O’Clock)» na página do artista exibia um retângulo claro indevido quando o menu estava aberto.

## Invariante visual confirmado

> **O restante da tela escurece; somente o conteúdo que o item selecionado realmente desenha preserva sua cor original.** Não clarear sua caixa transparente, não adicionar filme branco/cinza, fundo novo, borda, sombra ou cópia/bitmap da página. Preservar capa, título, texto secundário, rating, cor já legítima do item e geometria original.

Ao medir a captura da faixa, o fundo fora do item tinha cor aproximada RGB (40,45,45), enquanto uma área **vazia** dentro do retângulo selecionado tinha (57,65,64). A razão é cerca de **1/0,70**, consistente com a máscara preta do próprio HeldContextMenu (alpha 0,30) sendo **removida de todo o Retângulo**, inclusive pixels sem conteúdo. Isso explica a faixa que parece ter recebido uma película branca: na verdade o fundo da página ficou indevidamente **sem escurecimento** dentro do retângulo de bounds. O problema é do recorte da máscara, não necessariamente de `Color.White` ou do tema da página.

## Causa raiz e correção estrutural

1. `HeldContextMenu` usava `PathFillType.EvenOdd` com buraco `RoundRect(heldItem.bounds)`, uma região **retangular**. Isso funcionava nas capas opacas, mas em `NativeDetailTrackRowFrame` (faixas de artista, Search, Library e listas) e `NativeShelfCard` (arte/textos separados) revela também o fundo **transparente** da linha e faz um retângulo claro.
2. `Modifier.longPressMenuClickable` agora cria um `rememberGraphicsLayer()` **somente se a fonte aceita onLongClick**, e só registra as instruções de desenho da própria fonte **quando segurada** (não faz screenshot, não relocaliza o composable nem registra uma página opaca). `HeldItem.paintLayer` transporta a camada nativa, sem alterar bounds/RecordingId/MediaAsset ID.
3. O backdrop do `HeldContextMenu` se isola num `graphicsLayer(CompositingStrategy.Offscreen)`. Desenha a máscara preta sobre a tela inteira, depois reproduz a **transparência** da camada do item em uma segunda display-list cujo `blendMode = DstOut`. Só os **pixels efetivamente pintados** pelo card/texto/capa removem a máscara. Os vazios da linha continuam escurecidos. A fonte original permanece no lugar; não se desenha um segundo item sobre a tela.
4. Na primeira frame, se a fonte tem display-list mas ainda não gravou, o overlay aplica escurecimento completo em vez de exibir um retângulo claro por 1 frame. Para `HeldItem` realmente legado sem `paintLayer` mantém-se o fallback geométrico original; o fluxo real registrado por `LongPressOrigin.record` fornece `paintLayer`.
5. O display-list temporário é liberado em `DisposableEffect(heldItem)` quando o overlay sai (inclusive se um item substitui outro durante animações). As fontes tap-only não alocam esta layer e os desenhos ociosos permanecem sem captura.
6. `NativeShelfCard` (Home/Explore) usava um `collectIsPressedAsState` que pintava `Color.Black` sobre a arte enquanto o dedo permanecia pressionado. O callback de long press agora suspende especificamente esse **feedback de toque**, sem suspender a ação normal de tap; uma interação normal ainda dá o feedback esperado. Isso protege a cor da capa durante a seleção.

A cor de card original `NativeArtistTopReleaseCard` (que possui uma superfície branca translúcida como design de conteúdo) é preservada: a correção NÃO remove a superfície legítima do item, somente evita acrescentar falsas áreas claras nos vazios.

## Inventário da aplicação atendido por componentes compartilhados

| Contextos | Caminho da origem | Caminho do overlay/mascara | Resultado pretendido |
| --- | --- | --- | --- |
| Biblioteca por fonte MediaStore/Navidrome: artista, álbum, track, grid/lista | `CanonicalRemoteLibraryScreen` / `NativeLibraryEntityCard` / `CanonicalTrackRow` / `longPressMenuClickable` | `HeldContextMenu` local | somente a capa, textos e ações originais sem fundo de bounds clareado |
| Página do artista da Biblioteca, carrossel de «Faixas» (reprodução/fila) | `NativeArtistTracksShelf` + `NativeDetailTrackRowLayout` | `HeldContextMenu` local | faixa 00:00 sem retângulo mais claro; conteúdo com cores originais |
| Artista online + discografia/shelf de lançamentos | `DetailScreen` / `NativeArtistTopReleaseCard` / `NativeShelfCard` | `HeldContextMenu` principal | artwork não é reescalado, clip original preservado, sem pixels de fundo realçados |
| Álbuns/lançamentos nas grades e carrosséis | `NativeLibraryEntityCard`, `NativeArtistReleaseCard`, `NativeShelfCard` | overlay de item original | manter capa + legenda e card nativo, sem backing extra |
| Search catálogo MusicBrainz, playlists públicas ListenBrainz | `NativeSearchResultRow` / `NativeDetailTrackRowFrame` | `CanonicalSearchHeldMenus` → `HeldContextMenu` | textos/arte destacados; sem retângulo claro, sem playback fictício |
| Search servidor Navidrome | `CanonicalServerSearchRow` → ações de item | `HeldContextMenu` principal | fonte/tipagem e cores preservadas |
| Playlists próprias, smart e listas de faixas reproduzíveis | `CanonicalPlaylistTrackRow`, `SongRow`, `longPressMenuClickable` | `HeldContextMenu` principal | impedir halo de fundo sem suprimir tint ativo/reprodução legítimos |
| Recentes/Home/Explorar | `NativeShelfCard`, `CanonicalRecentHeldActions` | `HeldContextMenu` principal | capa não escurece por resíduo de `pressed`, texto/art preservados |
| Overflow ⋯ e sheet aberto por tap | caminho separado, sem `LongPressOrigin.consume()` | `FrostedMenuPanel`, `SongActionsSheet` etc. | **não** aplicar spotlight de long-press (não é um item pressionado) |

## Testes e aceite

- `HeldContextMenuPlateTest`: exige preferência pelo stencil alpha quando a layer está pronta; sem layer/sem gravação usa fallback.
- `HeldItemLiftStateTest`: fonte conserva bounds, e seu registro é liberado ao fechar.
- `NativeShelfHeldColorTest`: tap tem feedback normal, long press deixa arte original intacta.
- `HeldContextMenuGeometryTest`: bounds, clipping, posição e card/viewport continuam sob contrato anterior.
- **Build não equivale a prova visual.** Executar CI Android, TV/Wear e Desktop no HEAD exato. Smoke físico: catálogo artista/álbum, Library artista/álbum/faixa, Navidrome, Search e Home/Explore; repetir em tema claro/escuro, scroll (inclusive LazyRow) e com mini-player/toolbar. Verificar sem retângulo claro/duplicação, sem travamento gráfico, sem flicker de primeiro frame e sem custo excessivo de GPU. Se pixel masking falhar em hardware, manter PR Draft até evidência e ajuste.

O desenho e as ações disponíveis de cada entidade continuam regulados pela matriz de capacidades REA anterior. Nenhuma operação de playback ou IDM foi adicionada.
