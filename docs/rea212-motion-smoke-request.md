# REA #212 — autorização de smoke da rodada de animações

- Autorização: usuário solicitou em 15/11/2026 continuar a validação e, **apenas se estiver tudo certo**, realizar smoke.
- Escopo: rodada de animações e redução de movimento publicada a partir de `8cd010503feb86d2fdab2ba603c7c0c1bc03e138` na PR #212 Android.
- Execução: o workflow `Apple Music Bottom Bar · Smoke APK` deve rodar **primeiro** `:app:testDevDebugUnitTest`, o planejador Automix compartilhado e `:sharedUi:jvmTest`. Somente se todos passarem deve compilar `:app:assembleDevDebug` e publicar o APK universal.
- Restrição: não realizar smoke físico sem instalação/testes em dispositivo real; a publicação do APK é somente a preparação do smoke. Não executar TV, desktop ou Wear, nem mesclar a PR.
- Verificação: registrar SHA exato, resultado da CI e URL do artefato. Se testes falharem, bloquear artefato e corrigir primeiro.

## Nova tentativa — correção do gate de compilação dos testes

- Primeira execução: [GitHub Actions #43678026346](https://github.com/jssantogit/BitChord/actions/runs/43678026346), falha em `:app:compileDevDebugUnitTestKotlin` por imports incorretos de extensões Compose, função de toolbar sem import do pacote de origem e assertiva de coleção indisponível.
- Corrigidos somente os três arquivos de testes afetados, sem relaxar os critérios de aceite. Reexecutar os mesmos testes e permitir APK **somente após sucesso integral**.

## Diagnóstico focalizado após a segunda tentativa

- [Execução #43678458056](https://github.com/jssantogit/BitChord/actions/runs/43678458056): 3.593 testes Android executados; cinco falhas no editor de avaliação, nenhuma nas transições de abas/seleção/fila. APK bloqueado.
- Atualizada a expectativa numérica legada de 1 para 2 vetores V3, correspondente ao valor inicial 2.0. Etapa focalizada e relatórios XML acrescentados ao workflow para identificar as demais falhas sem repetir a suíte inteira a cada diagnóstico.
- Manter a geração de APK condicionada aos testes focalizados, à suíte Android completa e à suíte JVM compartilhada.

## Quarta tentativa — isolamento de paleta sem singleton do player

- [Execução #43679052013](https://github.com/jssantogit/BitChord/actions/runs/43679052013): gate focalizado compilou e executou 6 testes, com 4 falhas do mesmo `IllegalStateException: PlayerPlatform.install() was never called` ao montar o menu ancorado de avaliação no Compose/Robolectric. A fase de APK foi corretamente bloqueada.
- Causa localizada em `sharedUi/.../theme/ArtworkPalette.kt`: `rememberArtworkPalette` acessava `PlayerPlatform.host.settings.reduceAnimation` mesmo como apresentação de um editor isolado. A interface Android já disponibiliza `LocalReduceAnimations` na raiz da composição; o [fix `5b10c463`](https://github.com/jssantogit/BitChord/commit/5b10c463c595a617f491771b6e72b2ed6f06a14e) passou a usá-lo. Não introduzir instalação artificial do player nos testes nem alterar código de playback.
- **Solicitar smoke test-gated para o novo HEAD:** executar primeiro o gate focado dos testes de avaliação, depois a suíte Android completa, os testes shared Automix e sharedUi, e compilar/publicar o APK universal somente com todos aprovados. Registrar link, SHA e checksum do artefato; jamais marcar o smoke físico como concluído sem teste em aparelho.

## Quinta tentativa — corrigir semântica do painel ancorado

- [Execução #43680482920](https://github.com/jssantogit/BitChord/actions/runs/43680482920): os 6 testes focalizados executaram com 3 falhas restantes; a exceção anterior de `PlayerPlatform.install()` não ocorreu mais. As 3 falhas eram `merge function called on unmergeable property PaneTitle`, todas no editor de avaliação ancorado.
- Causa: `PlayerAnchoredActionsMenu` marcava o `Surface` de toda a placa com `Modifier.clickable(onClick = {})`. Isso tentava mesclar a propriedade `paneTitle` definida no editor filho. [Fix `66034d2a`](https://github.com/jssantogit/BitChord/commit/66034d2a9b33aeca52d2a816151a13e11f6b5b86) substitui o clique vazio da superfície por um capturador de toque sem semântica, preservando ações filhas e descartando toques inertes sem fechar o dialog.
- **Novo gate obrigatório:** regressões Compose focadas e toda a suíte Android, `shared:jvmTest` (Automix), `sharedUi:jvmTest`; publicar o APK universal apenas quando tudo concluir sem falha. Não alterar condições da CI nem declarar smoke físico como executado.

## Rodada pós-smoke físico — seis correções REA (15/11/2026)

- O usuário testou o APK da execução #43681012689 no Android e enviou cinco capturas. Pediu seis correções: cor uniforme das estrelas, apenas uma estrela numérica final, navegação de crédito para artista, ação de artista no menu do player, contraste do ⋯, blur dos menus e retorno de menu levantado sem piscar preto nem cair em bottom sheet genérico.
- Foram feitos reparos localizados nos controles de rating, fonte de evidência de artista local/Navidrome/MusicBrainz, fallback explícito de busca de artista sem ID, destaque discreto do ⋯ do player, placas Haze/ArtworkBackdrop e captura/visibilidade do item pressionado longamente. Preservar PR #212 draft e nenhum código fora de Android/sharedUi.
- Gate completo obrigatório: testes focalizados de rating; toda a suíte Android; testes compartilhados Automix e sharedUi; somente então compilar e publicar o APK universal devDebug. Registrar SHA e checksum, sem declarar smoke físico aprovado até receber novo feedback do aparelho.
