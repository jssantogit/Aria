# REA #212 — regressões de spotlight em Playlists e ⋯ do Player (19/11/2026)

## Evidência física enviada pelo proprietário

- **Biblioteca → Playlists → pressionamento longo:** captura `1000697916.png`. A lista inteira, inclusive o item selecionado, estava escurecida e desfocada. A arte da playlist permanecia difusa por trás da placa; o menu tinha aparência de apresentação antiga. A regra canônica da REA é manter o item de origem com sua aparência normal e escurecer apenas o restante da página.
- **Now Playing → ⋮:** captura `1000697917.png`. O destaque da âncora exibiu erro de renderização do glifo no botão, enquanto a placa de ações funcionava. O correto é preservar o glifo e a cor do botão de origem, sem compor um clone deslocado por cima.

## Causa confirmada no código

1. `UnifiedPlaylistLibraryShelf.kt` aplicava `.blur(playlistHeldBlur)` **sobre toda a LazyColumn** quando `heldMenuOrigin` estava presente, com 20dp de desfoque animado. Isso desfocava diretamente o item que a máscara por alpha de `HeldContextMenu` deveria manter intacto. A captura de pixel da origem e o `hazeSource` não justificam um segundo blur na árvore raiz.
2. `PlayerAnchoredActionsMenu.kt` desenhava um segundo `Box(40.dp)` com `Icon(34.dp)` e `background(playerOverflowPuckColor(true))` nas coordenadas reprojetadas para o diálogo de menu. O original já permanece composto na janela do `NowPlayingScreen`; a réplica cria risco de duplicação/deslocamento, sobretudo com insets e modificações de janela.

## Correção aplicada

- **Playlists raiz:** eliminar `animateDpAsState` de 20dp e `Modifier.blur()` da LazyColumn; conservar `HazeState` como fonte exclusiva da **placa de ações** em `HeldContextMenu`. O backdrop e recorte exato por alpha ficam sob responsabilidade da mesma máscara compartilhada de músicas, artistas, álbuns e Pesquisa. Os caminhos de menu normal por toque, menu de contexto por hold, e os callbacks de Play Next, queue, abrir, fixar, editar, excluir foram mantidos.
- **Player ⋮:** deixar de redesenhar qualquer glifo/puck. Aplicar `PathFillType.EvenOdd` com um buraco **oval nas bounds reais da âncora** dentro do scrim do diálogo, expondo o botão original sem alterar tint, vetor ou tamanho. Sem âncora, viewport inválido ou âncora fora da tela, desenhar somente scrim opaco e não inventar destaque. A área permanece clicável para fechar o menu. O editor de rating segue seu tratamento específico, sem mudança.
- **Regressão:** `PlayerAnchoredActionsHighlightTest` protege geometria com inset, ausência de buraco para âncora nula/fora da viewport e identidade do vetor original do Player. Os testes anteriores de menu de biblioteca/playlist e `HeldContextMenuPlateTest` continuam aplicáveis. A remoção da camada de blur é uma correção de estrutura da composição.
- **Escopo:** apenas REA #212; nenhum merge, sem alterações em IDM/AMS.

## Aceite ainda pendente

- Executar Android CI com testes, Desktop e TV/Wear no **HEAD final**; confirmar tudo antes de solicitar novo APK.
- Smoke físico: segurar playlist normal/canônica/smart da Biblioteca, capa clara/escura, comparar pixels (item não borrado, sem retângulo claro, sem perda de alpha), verificar gesto de fechamento/seleção e menu de toque normal; no Player abrir/fechar ⋮ repetidamente em modo normal e fila, com e sem fundo colorido, confirmar ícone único, sem deslocamento; testar reduzido blur/animação e tamanho de fonte aumentado.
- Quando houver novo APK, validar o SHA exato; a build de smoke anterior não contém essas correções. A PR #212 permanece Draft.
