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
