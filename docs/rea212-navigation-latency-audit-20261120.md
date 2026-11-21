# REA #212 — auditoria estática de latência na abertura de telas (20/11/2026)

## Sintoma reportado

No Android físico, troca/entrada em várias páginas — sobretudo categorias da Biblioteca e criador de playlists — é percebida como lenta, às vezes parecendo depender de uma chamada externa. O usuário confirmou que a CI do HEAD anterior estava verde. **Não foram coletadas métricas de toque→primeiro frame, toque→dados prontos nem trace de CPU** nesta investigação. Os mecanismos abaixo foram confirmados por leitura do código; sua contribuição em milissegundos depende de medição real.

## Mapa do caminho de abertura

| Caminho | Fase | Código | Classificação |
|---|---|---|---|
| Toque → rota | Atualização imediata de `pageRouteKey` | `MainActivity.kt` | Estado Compose; nenhuma chamada externa necessária |
| Troca de tabs | `rootTabContentTransform` fade de 150ms; rotas isoladas usam 180ms, Search/Criador substituição imediata | `RouteArrivalMotion.kt` e `MainActivity.kt` | Custo de animação deliberado, não rede |
| Biblioteca unificada | `UnifiedLocalCatalogIndex.ensure`, MediaStore + Room, single-flight em `Dispatchers.IO` | `CanonicalLocalLibrary.kt` | Assíncrono, uma vez/processo; a tela pode aguardar se o catálogo persistido estiver vazio |
| Primeiro conteúdo da Biblioteca | `collectAsStateWithLifecycle(initialValue=null)` do `CanonicalBrowseLibrary` + política `canonicalLibraryInitialLoading` | `CanonicalRemoteLibraryScreen.kt` | Aguarda primeira emissão Room; sem prova de rede |
| Projeção de milhares de músicas | `groupBy`, `distinctBy`, `sortedWith` no `Flow.map` | `CanonicalLibraryBrowse.kt` | **Gargalo confirmado de arquitetura:** sem contexto Default explícito, projeção podia ser executada no dispatcher de coleta Compose. Corrigido ao usar `withContext(Dispatchers.Default)` |
| Abrir Álbuns/Artistas | `entityState = Loading`; consulta `providerScopedReleases/Artists` para as fontes e filtros, deduplicação/ordenação; consultas separadas de datas e vínculos de capa | `CanonicalRemoteLibraryScreen.kt` | Consultas com `withContext(Dispatchers.IO)`, mas latência até UI Ready continua condicionada ao banco e volume |
| Entrar no criador | Nova `Dialog` com fade 150ms, setup de repositórios e múltiplos observers de definições smart/normais/filtros legados | `SmartPlaylistV2BuilderScreen.kt` | Custo de preparação local/composição; não quantificado |
| Seleção/busca de faixas no criador normal | `delay(160)` antes de toda consulta, inclusive primeira busca vazia, seguido por `canonicalNormalPlaylistSearch/Groups` em IO | `SmartPlaylistV2BuilderScreen.kt`, `CanonicalNormalPlaylistPicker.kt` | **Latência artificial confirmada** em busca vazia; preservado debounce somente para texto |
| Descobrir fontes do criador | `canonicalAvailablePlaylistSourceKeys` constrói adaptadores de contas e Keystore | `SmartPlaylistV2BuilderScreen.kt`, `CanonicalNormalPlaylistPicker.kt` | Execução antes no contexto de `produceState`; movida explicitamente a IO |
| Capas | Imagens são carregadas por Coil e fontes verificadas de arte, em paralelo | Diversos componentes | Podem completar depois do conteúdo; sem prova de que bloqueiem navegação |

## Correções focalizadas já publicadas

- `CanonicalLibraryBrowseRepository.observe`: offload da projeção `buildTracks` por `withContext(Dispatchers.Default)` sem alterar identidade, origem, ordenação ou dados. Unit test `CanonicalLibraryBrowseProjectionTest` prova ordem estável e agrupamento após a mudança.
- `SmartPlaylistV2BuilderScreen`: `canonicalAvailablePlaylistSourceKeys` executa em IO; `normalPlaylistSearchDebounceMillis` retorna 0 para query vazia e 160ms para texto digitado; testes cobrem as duas políticas. Todas as consultas de faixas mantêm seu `withContext(Dispatchers.IO)`.

## O que **não** está demonstrado

- Qual parcela do atraso físico vem de CPU, SQLite/Room, inicialização de observadores, Android Keystore, carregamento de capas, filtro de várias fontes ou GPU/blurs;
- Se a travada ocorre **antes** do primeiro frame ou se o shell abre rápido, mas fica esperando o spinner/dados;
- Se uma operação online específica é aguardada para essa rota; não há dependência obrigatória de HTTP identificada nos caminhos inspecionados de Biblioteca/Criador.
- Se a melhoria é perceptível na prática antes do novo APK/teste no mesmo dispositivo.

## Protocolo para medir antes de otimizar o resto

1. Num APK do mesmo HEAD, comparar Biblioteca→Músicas, Biblioteca→Álbuns, Biblioteca→Artistas, Biblioteca→Playlists e Criador. Executar 5–10 aberturas de cada rota com cache **quente** e uma primeira abertura **fria**, sem alternar versões no meio.
2. Medir separadamente **toque→primeiro frame utilizável**, **toque→lista pronta** e **toque→imagens completas**; anotar tela, biblioteca local estimada, fontes ativas e se havia spinner. Comparar offline e online sem apagar dados.
3. Usar Perfetto/System Trace/JankStats ou diagnóstico de frames (main vs render thread) para detectar frames lentos; cruzar com os eventos existentes `unified_library_local_index_v1` (scan, persist, ready) e `playlist_wizard_entered`.
4. Se os dados vêm tarde, otimizar o primeiro snapshot/consulta, cache válido e carregamento incremental; se o primeiro frame chega tarde, perfilar UI thread, alocação, composição, Keystore e cargas síncronas restantes.
5. Não esconder bloqueios com animações longas, não disparar HTTP especulativo, não marcar ganho numérico sem medição e não comprometer ID canônico/fonte do playback.

PR #212 segue Draft sem merge. Nenhum APK foi solicitado nem disparado nesta rodada.
