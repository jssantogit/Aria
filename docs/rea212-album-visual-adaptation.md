# REA #212 — Adaptação visual de álbuns (aproximação deliberada)

Referência de evidências: [am-descomp #8](https://github.com/jssantogit/am-descomp/issues/8), fichas consolidadas no commit `1ac5843f269328cad989fed9aefceb8e3f5ad137`.

- Não são medidas oficiais da Apple: `0.66 × largura` para capa de álbum (antes 0.76), cap da altura preservado; 46dp para os controles laterais dos álbuns (antes 50dp) e espaçamento 12dp. Playlists e demais superfícies mantêm a geometria anterior.
- Header canônico: um único caption sob artista para gênero *se presente e comprovado*, ano *se data de release verificada*, e badge *somente* de qualidade da fonte/asset. O modelo canônico atual não fornece gênero confirmado e não fabrica um. Badge não comprova codec/stream negociado.
- Faixas de álbuns canônicos: manter numeração de release original, divisores, RecordingId estável e menu de pressionamento longo, sem ⋮ inline. Mínimo 60dp (antes 68dp); artista repetido do crédito do álbum pode ser omitido; artista convidado/diferente permanece. Biblioteca geral, playlists e outras linhas não são alteradas.
- Os modos local, catálogo e servidor conservam identidade e capacidades; álbum metadata-only não ganha ação de reprodução. As ações locais existentes (duas pílulas) não foram trocadas por trio online; apenas as dimensões são ajustadas.
- Aproximação a validar em aparelho antes de afirmar paridade; os verdadeiros bounds, fontes e variantes Apple continuam unknown/requires_runtime. Não gerar APK nem fazer merge nesta rodada.

## Compatibilidade do adaptador legado (Song)
- `LegacySongRow.SongRow` recebeu `albumCredit` opcional: apenas o detalhe de álbum numerado utiliza esse parâmetro, com o crédito obtido de `page.headerLines()` já usado no hero. Linhas normais e playlists não mudam. Crédito de participação diferente do álbum e formato de download permanecem embaixo, quando disponíveis.
- As linhas de álbum metadata-only no `DetailScreen` usam a mesma regra condicional do compositor canônico, preservando seleção, índice original e ações por long-press.
- Testes JVM do componente compartilhado verificam artista repetido, participação e ausência de regressão das listas gerais.
