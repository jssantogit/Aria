# REA #212 — linguagem visual dos cards expansíveis (Android)

Situação: implementação inicial; falta validação em dispositivo real. Referência aprovada pelo usuário: popup de filtro/ordenação na Biblioteca (15/11/2026).

## Contratos

| Papel | Fundo | Blur | Interação |
| --- | --- | --- | --- |
| Filtro / ordenação | neutro #29292D no escuro, #F5F5F7 no claro | Haze do mesmo host quando disponível; fallback sólido | ancorado ao botão, sem deslocar chrome |
| Avaliação | exatamente o neutro dos menus expansíveis (#29292D escuro / #F5F5F7 claro), sem borda | sem textura fosca própria; fallback sólido do host Dialog, sem Haze da página | modal central, título, estrelas e ações de confirmação |
| Seleção múltipla | mesma placa neutra **totalmente opaca** | **sem Haze** da lista interativa | dock fechado por padrão, menu cresce para cima, lista de ações rolável |
| Pressionamento longo | mesma placa neutra **totalmente opaca** | **sem blur** no card, fundo escurecido sutil | manter item elevado e menu sincronizados |

O tom da capa deve dar personalidade à página, mas não colorir arbitrariamente as superfícies de controles. Evitar menus inteiros verdes/roxos e qualquer texto de lista visível através do card de ações.

## Aceite antes de encerrar

1. Selecionar artista: dock compacto não encobre os nomes, ações expandem acima e continuam acessíveis; recolher e limpar seleção.
2. Testar também seleção de faixas e álbuns em telas pequenas e com miniplayer presente.
3. Pressionamento longo em páginas escuras e dinâmicas: cor e contraste estáveis, sem interferência do fundo ou duplicação de texto/capa.
4. Filtros preservam Haze quando disponíveis; avaliação usa a mesma superfície neutra sólida dos menus expansíveis, sem borda ou blur artificial, também sob redução de blur.
5. Abrir e fechar repetidamente sem piscadas, sem alterar a navegação ou o vetor do ⋯ do player.

Essa regra é comum à família de cards, mas cada papel conserva sua semântica: blur nos filtros quando o host permite; avaliação com o fundo neutro canônico (sem textura sintética no Dialog); solidez na seleção e no pressionamento longo.


## Complemento — destaque branco universal (15/11/2026)

Decisão do smoke físico: o **elemento acionado**, e não toda a superfície do menu, recebe um contorno claro consistente. A cor da placa de ações permanece grafite neutro no escuro ou neutro claro no tema claro, com legibilidade independente do artwork.

- `HeldContextMenu`: a prévia elevada mantém a imagem original do item, com contorno branco de 1,5dp (`heldContextSelectionOutline()`). O retorno animado não deve duplicar a linha de origem.
- ⋯ das barras de álbum online e playlists: preservar o vetor real do botão; exibir o mesmo contorno somente enquanto o menu está aberto. A placa popup não ganha borda branca.
- ⋯ do player: **exceção explicitamente confirmada pelo owner em 19/11/2026** — usar o contorno original dos três pontos AM-DECOMP `MorePlain`, **sem qualquer anel/linha de contorno** no botão, e destacar o estado ativo apenas escurecendo a placa circular neutra sob os pontos brancos. Puck base: preto 18% de alpha; puck selecionado 38% de alpha. Mesma geometria/posição, nenhum fallback de ícone genérico.
- O destaque de seleção múltipla (marcadores azuis) continua separado do estado ativo de menu. Não pintar contorno branco em todos os itens selecionados.
- A migração não altera callbacks de navegação nem torna metadados MusicBrainz/ListenBrainz reproduzíveis.

### Publicado nesta rodada; ainda não validado

- Novos pressionamentos longos em Artistas semelhantes, Incluído em, Playlists e lançamento em destaque da página de artista, ligados a `CanonicalSearchHeldMenus`.
- Estado de contorno ativo nos ⋯ das barras de álbum canônico, playlists públicas e playlists locais da Biblioteca.
- Vetor original e contorno compartilhados com o player.

**Limites:** inspeção textual estrutural não é teste Android. A última CI verde identificada ainda valida o commit `66727ea4`; não houve smoke/CI confirmada do HEAD desta rodada. Persistem auditoria completa dos demais lançadores na Home/Explorar, teste de gestos e validação física em telas pequenas, temas claro/escuro, blur reduzido e TalkBack. Manter PR #212 em draft.

## Correção após captura física do player — 19/11/2026

- **Defeito observado:** ao abrir o menu de três pontos da tela Now Playing, o launcher exibia um anel claro redundante; o card flutuante possuía contorno visível, superfície sólida e nenhum blur neutro. A captura mostra o contraste inconsistente com os outros cards expansíveis.
- **Launcher:** `AppleQueueVectors.MorePlain` é a geometria original dos três pontos, sem a circunferência embutida em `AppleQueueVectors.More`. O host e a réplica destacada sobre o diálogo usam o mesmo glifo e o token `portraitOverflowPuckColor(selected)`: preto 0.18 no repouso, 0.38 no menu aberto. Eliminar `heldContextSelectionOutline()` **somente** no overlay do ⋯ do player; não modificar o contorno dos held previews e de outras ferramentas.
- **Card:** `NativeContextActionPlate(PLAYER_NEUTRAL_FROSTED)` preserva 18dp de raio e cor neutra `nativeCompactPlateColor`, **sem `BorderStroke`**, ligado ao `HazeState` dedicado do player enquanto o menu está aberto. O host do player fornece `Modifier.hazeSource` na janela M3 original, com captura condicional a `!reduceDynamicBlur`; Haze real no card do Dialog superior, e fallback neutro sólido quando desabilitado/indisponível. Não acrescentar backdrop da capa colorido nem segunda textura fake.
- **Invariantes:** manter ações, navegação, gestos, área de toque, origem do overlay e redução de movimento, sem mudar IDM e sem mesclar a PR #212. O código e os testes unitários foram publicados; **blur entre janelas ainda requer smoke visual físico**, não presumir aceite só com CI.
