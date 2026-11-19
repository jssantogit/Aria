# REA #212 — Paridade de menus por entidade e capacidade (19/11/2026)

Escopo: **somente apresentação REA**, PR #212 Draft. Fontes analisadas: catálogo MusicBrainz (online/metadata-only), Biblioteca MediaStore local, Biblioteca Navidrome/OpenSubsonic, playlists públicas ListenBrainz, playlists próprias e Search/held menus. Capturas do owner mostram menus de artista com geometrias semelhantes, mas catálogo online reduzido somente a «Configurações» enquanto a Biblioteca tinha fila/playlist/avaliação/pin.

## Regra

**Uma apresentação de ações para a mesma entidade; capabilities reais determinam quais linhas existem.** Não deduzir MediaAsset ou capacidade de playback de um ArtistId, ReleaseId, RecordingId, título ou correspondência de texto. Metadados sem áudio continuam permitindo avaliar, fixar, compartilhar, abrir a fonte externa e importar gravações exatas para playlists, mas **não** Play, Shuffle, Play next, Queue ou Download sem implementação e fonte autorizada.

A geometria de `BrowseActionsSheet(presentation = Menu)` é a referência para linhas de entidade (texto primeiro, ícone à direita, altura 46dp e paddings nativos). O host `FrostedMenuPanel` mantém ancoragem, scrim, blur neutro e Back/dismiss. Long press `HeldContextMenu` continua com prévia elevada própria, mas usa o mesmo corpo de opções.

| Entidade/origem | Ações que fazem sentido | Ações proibidas/omitidas | UI / evidência |
| --- | --- | --- | --- |
| **Artista MusicBrainz online** | Editar avaliação local; fixar/desafixar metadados; compartilhar link; ver no MusicBrainz; Configurações no ⋯ do detalhe | tocar a seguir, adicionar à fila, tocar tudo, shuffle, baixar, adicionar artista a playlist de gravações | `CanonicalCatalogEntityMenuBody` chamado pelo `browseMenuBody` em `MainActivity`, validado por `canonicalCatalogEntityMenuSpec(catalog:ARTIST:<MBID>)` |
| **Artista MediaStore local** | enfileirar gravações verificadas da fonte; adicionar coleção à playlist; avaliação, pin, Configurações quando suportados | operações sem associação à fonte ou identidade falsa | `CanonicalRemoteLibraryScreen` → `BrowseActionsSheet`, `canonicalLibraryResolveRecordingIds` |
| **Artista Navidrome** | mesma apresentação local; fila e ações dirigidas à conta/fonte selecionada, sem trocar para catálogo pelo nome | inferir MediaAsset ou ArtistId compartilhado; vazamento de source/account | mesmo `CanonicalRemoteLibraryScreen` com `selectedSource`/providerKey |
| **Álbum Release / ReleaseGroup online** | avaliar, fixar, compartilhar, abrir MusicBrainz; adicionar à playlist **somente via picker que exige RecordingIds verificados** e seleção de edição quando ambígua; Configurações | reprodução, fila ou download do MusicBrainz | `CanonicalCatalogReleaseOverflowMenu` e `CanonicalCatalogEntityMenuBody`; `CanonicalSearchMetadataPlaylistPicker` |
| **Álbum Library/Navidrome** | fila/gravações por identidade/source, playlist, rating, fixação e ações específicas existentes | playback sem áudio associado à origem | `CanonicalRemoteLibraryScreen` → `BrowseActionsSheet` |
| **Playlist pública ListenBrainz** | importar somente gravações MBID verificadas, avaliar, fixar, compartilhar, visitar ListenBrainz, Configurações | Play next, Queue, Download do catálogo público | `CanonicalPublicPlaylistOverflowMenu` usa `BrowseActionsSheet` e picker com `kind = null` |
| **Playlist própria/Smart** | filas e edição quando o resolvedor da playlist conhece os itens; opções locais próprias e Configurações | usar metadata-only pública como stream | `CanonicalPlaylistMenus` e `CanonicalPlaylistDetailScreen` (host nativo ainda próprio para edition/ordering) |
| **Pesquisa MusicBrainz / long press** | Ver detalhes; adicionar gravação/edição à playlist via picker verificado; rating; pin/desafixar; compartilhar | qualquer fila, tocar e Download falso que apenas mostra toast | `CanonicalSearchHeldMenus` usa `BrowseActionsSheet` com callbacks reais |
| **Pesquisa playlist pública / long press** | Ver detalhes; importar via picker verificado; rating, pin/desafixar e share | qualquer fila, tocar ou download público sem arquivo | mesmo `CanonicalSearchHeldMenus` |
| **Pesquisa servidor Navidrome** | reprodução apenas com `CanonicalRemotePlaybackRequest(recordingId, mediaAssetId, providerKey)` comprovado | construir Song/videoId falso; carregar source diferente | `CanonicalServerSearchRow` + playback canonical gate |
| **Menu de faixa reproduzível/player** | ações reais de `SongActionsSheet`, player/queue, share, avaliação canonical e navegação com IDs comprovados | enxertar metadata-only sem resolução | `SongActionsSheet` usa `ActionRow(Menu)` igual ao `BrowseActionsSheet` |
| **Ordenação, filtros, raiz Configurações** | opções específicas da tela, sem herdar reprodução de outras entidades | ações genéricas só porque popup é o mesmo | `FrostedMenuRow` + mesmo `FrostedMenuPanel`; `NativeCompactOverflowMenu` compartilha material neutro |
| **Batch selection / held-preview chrome** | comandos de lote somente com origem e seleção verificadas | tornar ação falsa ou transparente sob texto | componentes com papéis próprios; não forçar card que ignora contraste |

## Implementação deste corte

1. `CanonicalCatalogEntityMenuSpec` verifica roteamento ARTIST/RELEASE/RELEASE_GROUP e gera URLs MusicBrainz exatas; distingue `playlistKind = null` para artistas. O helper `CanonicalCatalogEntityMenuBody` reutiliza `BrowseActionsSheet` com callbacks de metadata e **nunca passa callbacks Play/Queue/Download**.
2. `MainActivity.browseMenuBody` roteia artistas e álbuns metadata-only para o helper, inclusive toolbar e menus de cards/held. Outros targets preservam o resolvedor legado/Library original.
3. `CanonicalCatalogReleaseOverflowMenu` e `CanonicalPublicPlaylistOverflowMenu` passam pela mesma apresentação, preservando rating/pin/share e adicionando importação de gravações via picker **real**, sem tocar MusicBrainz.
4. `CanonicalSearchHeldMenus` usa `BrowseActionsSheet` para gravações/artistas/álbuns e playlists públicas, com estado pin/desafixar do repositório; removeu os comandos no-op de Download e seus callbacks (a UI não promete operações ausentes).
5. `BrowseActionsSheet` oferece campos opcionais `onViewSource`, `viewSourceLabel` e `onOpenLabel` somente para contextos que os validam, sem multiplicar row renderers.

## Gates

- Testes: `CanonicalEntityMenuParityTest`, `CanonicalSearchHeldMenuPolicyTest`, `BrowseActionsSheetOptionalQueueActionsTest`; rodar CI Android, TV/Wear e Desktop no **SHA exato**. Ajustes de HazeDialog da REA anterior também devem ser compilados na variante TV.
- Físico: conferir Artista catálogo × Artista local × Artista Navidrome, Álbum online × local/Navidrome, Playlist pública × própria, longa pressão na Pesquisa e página de detalhes. Ordem, alturas, ícones, blur neutro, pin/desafixar, Save, Back e ausência dos botões Play/Queue/Download em metadata-only.
- Não declarar aceite visual, reprodução ou merge sem smoke/aprovação. IDM #219 não alterado.
