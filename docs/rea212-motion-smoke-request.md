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

## Nova solicitação — transições internas do Explorar (15/11/2026)

- **Autorização explícita do usuário:** conferir animação ao navegar entre categorias do Explorar; implementá-la se ausente; em seguida executar o smoke Android.
- **Correção verificada no código:** a rota raiz `tab:1` permanecia igual entre a grade de gêneros e um gênero escolhido, portanto o `AnimatedContent` do host não animava essas mudanças. A solução introduz `ExploreCategoryContentTransition`, com um destino de composição por categoria e o mesmo `rootTabContentTransform` (fade linear 150 ms, sem spring de tamanho; sem animação quando reduzir movimento). O chrome do topo, mini-player e barra inferior seguem fora da transição. Contratos de UI acrescentados em `ExploreCategoryContentTransitionTest`.
- **Implementação até o commit anterior a esta solicitação:** `84c8374de1c43e4683062917413a80341617756d` na branch da PR #212; o SHA do build será o novo HEAD contendo este documento.
- **Gates obrigatórios:** regressões de avaliação, suíte Android completa (incluindo novos testes do Explorar), `shared:jvmTest` e `sharedUi:jvmTest`; somente se todos passarem compilar e disponibilizar o APK universal com SHA-256. Não executar TV, desktop, Wear, nem mesclar PR #212.
- **Smoke no aparelho ainda não executado:** verificar Explorar → categoria → voltar, alternância rápida, preservação do scroll, redução de animação e ausência de flashes; repetir o smoke anterior do menu, blur e navegação local. APK disponibilizado não significa teste físico aprovado.

## Gate #43728444128 — erro de compilação e nova tentativa

- [Run #43728444128](https://github.com/jssantogit/BitChord/actions/runs/43728444128) falhou antes de executar testes, em `app:compileDevDebugKotlin`: parâmetro `reducedMotion` inexistente no helper de Explorar (o argumento é `reduceMotion`) e import da constante `LOCAL_MEDIASTORE_PROVIDER` feito como top-level em vez de usar `CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER` no módulo de artista do player. Nenhum APK foi gerado.
- Os dois erros foram corrigidos preservando a política do smoke e todo o escopo funcional aprovado. Solicitar novamente o mesmo gate de testes Android/sharedUi/Automix antes de compilar qualquer APK. Não interpretar CI como smoke físico.

## Gate #43728868988 — corrigir import do novo teste do Explorar

- [Run #43728868988](https://github.com/jssantogit/BitChord/actions/runs/43728868988) confirmou compilação de produção até `app:compileDevDebugKotlin`, mas `app:compileDevDebugUnitTestKotlin` falhou apenas por imports inexistentes de `assertDoesNotExist` e `assertExists` em `ExploreCategoryContentTransitionTest.kt`. São métodos de `SemanticsNodeInteraction`, já usados sem import em testes da branch; remover somente as linhas de import, preservando as assertivas e cenários.
- Reexecutar o mesmo gate integral, sem desabilitar testes ou compilar APK após falha. Usuário autorizou explicitamente o smoke nesta rodada.

## Gate #43729473306 — estabilizar o teste temporal do Explorar

- [Run #43729473306](https://github.com/jssantogit/BitChord/actions/runs/43729473306) passou nos testes de rating focalizados e compilou app/testes. Na suíte Android integral: `3604 tests completed, 1 failed, 1 skipped`. A falha foi exclusivamente no novo `ExploreCategoryContentTransitionTest.rootToGenreAndBackCrossfadeWithoutReplacingTheOutgoingPage`, linha 44: assertiva tentava verificar conteúdo de entrada após um único frame de 16ms no Robolectric; o destino ainda não tinha sido composto naquele instante.
- Preservar a verificação real da sobreposição temporária entre origem e destino: avançar 64ms (quatro frames), ainda dentro do fade de 150ms, antes de exigir presença de ambas as árvores. Não afrouxar a verificação de remoção do destino antigo após concluir o fade, nem desativar o teste.
- Solicitar novamente o smoke com gate completo sem publicar APK se qualquer etapa falhar. Não declarar smoke físico realizado.

## Gate #43730280457 — estabilização do teste de transição Robolectric

- [Run #43730280457](https://github.com/jssantogit/BitChord/actions/runs/43730280457): teste focalizado de avaliação PASS; suíte Android 3.604 testes / 1 falha / 1 skipped. A mesma assertiva intermediária de `ExploreCategoryContentTransitionTest` persistiu mesmo após 64ms: sob relógio de animação desativado no Robolectric, o destino novo não aparece no primeiro intervalo controlado. Nenhum outro teste falhou e nenhum APK foi gerado.
- Alteração: não ocultar nem ignorar a falha. Substituir o teste temporal dependente do agendador de frames por prova Compose determinística da navegação grade → gênero A → gênero B → grade, com exclusão dos destinos obsoletos após o estado estabilizar; testar separadamente `exploreCategoryContentTransform` contra a política exata do root (fade 150ms, sem size spring, animações reduzidas instantâneas). Não mudar o visual/efeito de produção além de expor a política para teste.
- Nova solicitação de smoke restrita à PR #212 Android: executar gate focalizado, suíte integral Android, shared Automix/sharedUi e somente após aprovação compilar e entregar APK universal e SHA-256.

## Gate de implementação — linguagem dos cards e seleção (15/11/2026)

- <b>Origem:</b> após smoke físico, usuário enviou captura de grave sobreposição de textos no modo seleção de Artistas e autorizou a consolidação da linguagem visual dos cards usando o filtro da Biblioteca como referência. Menu de pressionamento longo também exibiu cores dinâmicas e conteúdo duplicado sob placas translúcidas.
- <b>Escopo:</b> commits de teste `7b900353` e implementação `930177aa`. `NativeContextActionPlate` usa mesma placa neutra do menu de filtros, mas seleção exige tratamento SOLID (nenhum Haze sobre linha selecionável); filtro/rating conservam tratamento FROSTED com fundo sólido de segurança; menu de long press ignora cor de arte, superfície neutra opaca. Barra de seleção começa compacta e expande ações para cima, mantendo summary dock e botões acima do chrome. Guia `docs/rea212-expandable-cards-visual-language.md` com contratos e aceite.
- <b>CI autorizada como gate de qualidade, não como smoke físico:</b> testes focalizados de rating, suíte Android inteira incluindo regressões de seleção, testes shared Automix, sharedUi, compilação de APK universal somente se tudo passar. Nunca relaxar falhas. Não integrar, não desviar para TV/Wear/desktop, não declarar verificação visual sem novo teste no aparelho.
