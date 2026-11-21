# REA #212 — alinhamento visual do detalhe de álbum (19/11/2026)

## Evidência nova (capturas recebidas do proprietário)

- Apple Music, **BREAK EVEN - EP / ILLIT**: `1000697915.jpg` (736 × 1536 px). Artista centralizado sob o título, caption `K-pop · 2026 · Dolby Atmos · Lossless`, tríade de controles, quatro faixas numeradas com título compacto, separadores e ⋮, data/contagem/duração/créditos no rodapé.
- BitChord, **&Our Vibe / SAY MY NAME**: `1000697913.jpg` (601 × 1536 px). Capa, título, crédito e rótulo legítimo da fonte já estão em uma composição coerente. Faixas sem miniaturas, numeradas, e menu por hold; não há motivo para mudar o modelo de ações baseado apenas na referência.
- BitChord, **BREAK EVEN / ILLIT**: `1000697911.png` (709 × 1536 px). Na entrada aninhada no artista, o crédito do álbum está ausente no cabeçalho, mas aparece repetido como `ILLIT` nas quatro faixas, deixando a lista alta e comprimindo a hierarquia hero→ações→faixas. Rodapé mostra a contagem/duração, sem data verificada nessa tela. O título exibido perde `- EP` em relação à Apple; o tipo/editorial e o nome efetivo da release precisam de verificação separada.

**Limite de medição:** os três arquivos possuem larguras de exportação diferentes, embora todos tenham altura 1536 px. Não usar posições/raios em px como dp/sp nem concluir fidelidade de escala; o banner verde `Apple Music` na primeira captura não é evidência de insets nativos. A proporção horizontal da capa fica em torno de 2/3 da largura nas três imagens; o `ALBUM_SLEEVE_FRACTION = 0.66f` atual é uma aproximação adequada, sem troca necessária nesta rodada.

## Consulta ao am-descomp

Referência canônica: [detalhe de álbum / issue #8](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/screens/rea212-album-detail.md) e [handoff R3/R5](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/rea212-final-visual-handoff.md).

- **runtime_observed:** hero de artwork, título/artista centralizados, caption e badges de origem na amostra, controles e lista numerada com separadores, rodapé do álbum exemplificado.
- **static_verified:** layouts candidatos de cabeçalho com artista, título e badges; footer com data/contagem/duração/copyright; layout candidato de faixa com índice/título/subtítulo inicialmente oculto/⋮/divider.
- **unknown / requires_runtime:** dimensões em dp, hierarquia efetivamente usada no runtime, fonte/baseline, semântica de todas as ações, cálculo de backdrop, presença universal de créditos/qualidade/⋮ e thresholds de scroll. A captura antiga S07 foi **reescalada** e não fornece medidas físicas.

Essas fontes sustentam a **hierarquia de apresentação**, não autorizam badges Dolby Atmos/Lossless sem MediaAsset real, créditos inventados, novos streams ou download do catálogo metadata-only. A escolha BitChord de pressionamento longo para ações de faixas fica preservada; o ⋮ observado no Apple não é nova obrigação funcional.

## Ajustes publicados na composição compartilhada do BitChord

| Ponto | Antes | Agora | Proteção |
| --- | --- | --- | --- |
| Crédito ausente em álbum aninhado | `release.artistDisplay.orEmpty()` gerava header sem artista; faixas repetiam ILLIT | `nativeAlbumDisplayCredit` prefere crédito explícito; só usa o artista das gravações membros **se todos os créditos não vazios coincidirem** | Sem inferência por título, sem ArtistId, sem link novo, guest/misto conserva subtítulos |
| Álbum raiz da Biblioteca | Fallback pelo artista da primeira faixa, mesmo em álbum misto | Mesma regra determinística de consenso, com precedência da release | Não misturar artistas entre fontes |
| Densidade das faixas numeradas | `heightIn(min=60.dp)` para detalhe do álbum | `heightIn(min=54.dp)` (mínimo acima do touch target 48dp; conteúdo com duas linhas pode expandir) | Demais linhas da Biblioteca continuam em 68dp; índices/divisores, rating e hold preservados |
| Espaço título → artista | 2dp em todas as superfícies | 7dp em álbum (`inlineAlbumMetadata`); 2dp nos demais | Evita alterar a apresentação de playlists |
| Capa | Fração 0,66 da largura | Sem mudança | Evita reagir a pixels exportados em escalas diferentes |

Teste: `CanonicalAlbumDetailParityTest.missingReleaseCreditUsesOnlyUnanimousVerifiedAlbumArtists` cobre caso ILLIT, crédito explícito, mixed guest e dados desconhecidos. Manter as demais regressões existentes.

## Ainda não implementado / não presumir

1. **Rótulo `- EP`:** investigar a política atual de `canonicalAlbumDisplayTitle` com o `primaryType` verificado e decidir se preservar o nome literal ou formatar um subtipo; não adicionar sufixo indiscriminado.
2. **Caption e rodapé:** gênero, data, copyright e Atmos/Lossless só com evidência source/asset. Data ausente não se preenche usando data atual, screenshot Apple nem nome do lançamento.
3. **Toolbar e posicionamento absoluto:** comparar em aparelho com screenshot sem banner/adornos de marca, DPI/fontScale/insets conhecidos, incluindo tema claro/escuro, títulos longos e fonte ampliada.
4. **Menus:** manter o contrato de long press existente da REA, a paleta de origem e ações determinadas por identidade e capacidade. Não alterar IDM/AMS.
5. **Aceite:** Android unit, shared JVM, TV/Wear, Desktop no **novo HEAD**, seguido de APK e confronto físico. Até lá, a PR #212 segue DRAFT sem merge; não declarar pixel-perfect nem smoke concluído.
