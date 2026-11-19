# REA #212 — Auditoria transversal de blur neutro em overlays Android

Data: 19/11/2026. Status: código em PR Draft; validação física/CI do HEAD exato pendentes. Escopo apenas REA, sem IDM nem merge.

## Evidência

Três capturas físicas do proprietário:
- Menu ⋯ ancorado do álbum: card neutro plano apesar do fundo ilustrado; esperava-se blur.
- Editor modal «Sua avaliação» sobre a página do artista/álbum: card plano sem blur.
- Mesmo editor modal «Sua avaliação» sobre a tracklist: mesmo defeito.
A solução é usar **um material neutro e a captura real da tela**, nunca uma textura falsa, alteração da cor baseada na capa ou transparência que deixe textos nítidos atravessarem as ações.

## Causa-raiz por camada

1. `CanonicalRecordingFeedbackPanel` usava `NativeContextActionPlate(NEUTRAL_FROSTED)`, mas seus callers de álbum, artista, biblioteca e catálogo **não passavam `hazeState`**. O algoritmo corretamente caía em sólido, então faltava a fonte real.
2. `NativeCompactOverflowMenu` era um `Surface(color = plate)` puramente sólido, independentemente do host e do estado de blur.
3. `AlertScaffold` desenhava `surface` sólido quando `hazeState == null` (como os diversos alertas de Configurações). Outros diálogos de idioma/letras/atualização usavam receitas próprias com `HazeMaterials.regular(MaterialTheme.colorScheme.surface)` quase opaco.
4. `AudioPopupCard` combinava blur com tinta preta 90% opaca, obscurecendo material difuso.
5. `FrostedMenuPanel` já possuía fonte, mas duplicava receita de material distinta do restante. A ausência de blur visível na captura não pode ser declarada solucionada sem smoke de aparelho.

## Inventário e correção

| Família | Implementação / contexto | Estado de implementação | Contrato |
| --- | --- | --- | --- |
| Filtros, ordenação, fontes, menus ⋯ de álbum/playlist | `FrostedMenuPanel` | refatorado para `neutralFrostedMaterial` | cor neutra, Haze do host, 18dp; fallback sólido para Reduce blur |
| Popups compactos de Home, Explorar, menus e playlists | `NativeCompactOverflowMenu` | alterado de Surface sólido para mesmo material com CompositionLocal de Haze | Popup herda estado do host, ainda sem borda; ações e entrada/saída preservadas |
| Avaliação de faixa/álbum/artista/playlist, inclusive MusicBrainz e Biblioteca | `CanonicalRecordingFeedbackPanel` → `NativeContextActionPlate` | inherit do Haze de página por `LocalNeutralOverlayHazeState`; fonte explícita do player tem prioridade | material neutro, raio de avaliação 30dp, sem borda, estrelas e salvar preservados |
| ⋯ do player | `PlayerAnchoredActionsMenu` | fonte específica do player e tratamento `PLAYER_NEUTRAL_FROSTED` já existentes | sem anel no launcher, card sem borda, raio 18dp |
| Confirmações, login, tokens e alertas de Configurações | `NativeAlertHost` / `AccountAlerts.AlertScaffold` | herdada fonte mesmo para callers legados `hazeState = null` | modal neutro, mesma geometria e ações |
| Idioma app/tradução, fontes das letras, canal de conta, atualizações | `AppLanguageDialog`, `TranslationLanguageDialog`, `LyricsSourcesDialog`, `AccountChannelDialog`, `UpdateAvailableDialog` | unificado fundo Haze com mesmo material; removida receita opaca isolada | modal neutro mantendo scroll, toque externo e ações |
| Saída e processamento de áudio | `AudioPopupCard` | substituído negro com tinta 90% por neutro frosted do player | contraste branco e layout de saída preservados |
| Menus de long press | `HeldContextMenu` | já implementava Haze condicional e fallback; não alterado neste corte | manter prévia original, sem texto de lista atravessando a superfície |
| Seleção múltipla / barra de ações | `CanonicalLibraryBatchActionBar` e seleção | deliberadamente fora da mudança visual: superfície sólida por legibilidade e política anterior | **não** tornar semitransparente nem revelar texto sob controles |
| Full-height `ModalBottomSheet` (qualidade, download etc.) | `SettingsSheet`, `PlaylistPickerSheet`, `SongActionsSheet` | NÃO converter para card central: trata-se de superfície/navegação de sheet, não menu expansível | preservar touch, scroll, insets e cap. Funcionalidade original |

### Fonte única e fallback

- A raiz Android cria `HazeState` uma vez, provê `LocalNeutralOverlayHazeState` e `LocalNeutralOverlayReducedBlur`, e entrega o mesmo objeto à captura `Modifier.hazeSource` no `AnimatedContent`.
- `NativeContextActionPlate` usa explicit HazeState se presente (player) ou herdado (catálogo/Biblioteca); se ausente, neutro sólido honesto.
- O modifier compartilhado `neutralFrostedMaterial` implementa shape + cor base #29292D escuro/#F5F5F7 claro + `HazeMaterials.regular(neutral.copy(alpha=0.58))` quando a fonte é válida e blur permitido. Redução de blur preserva cor sólida canônica. A camada escurecida pertence ao scrim fora da placa, não ao material, e não adiciona tint de capa.
- Haze 1.3.1 continua pinado no Android; alterações de dependências/protocolo de blur não foram feitas. **Blur entre janelas** em Popups/Dialogs precisa de prova física e desempenho; a presença de HazeState em Compose não comprova captura visual do sistema.

## Gates

1. Android CI `:app:testDevDebugUnitTest` + build; TV/Wear/Desktop; sharedUi JVM e regressões de modais. Anotar resultados **do HEAD exato**, não herdar verdes de commits anteriores.
2. Smoke em aparelho: abrir cada família com conteúdo de fundo de alto contraste; confirmar difusão real em vez de branco/preto chapado, sem texto/arte nítidos sob ações, em tema claro e escuro. Repetir com «Reduzir blur dinâmico» ligado e desligado, multiwindow estreito, animações reduzidas e lista rolando.
3. Conferir posicionamento do menu do álbum, estrelas, salvar, cancelar, seleção de arquivo, prompts de conta, saída de áudio, menus ancorados e funcionamento de Back/dismiss. Não equiparar build verde a smoke físico executado.

Nenhum APK novo ou merge está autorizado automaticamente por esta auditoria; PR #212 permanece Draft.
