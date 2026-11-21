# REA #212 — playlist detail, documented visual subset

Evidence: [am-descomp issue #8](https://github.com/jssantogit/am-descomp/issues/8), consolidated `fase-3-investigacao-visual@1ac5843`; particularly S03/S05 of a **single editorial playlist**.

Changes are *BitChord approximations*, not generalized Apple facts:
- Keep the existing shared `NativePlaylistDetailHeader` (sleeve, title, verified credit, controls) and existing 76% sleeve. No claim of exact dp/radius/typography.
- Present the type (“Playlist” / “Playlist inteligente”) below the title. Move actual membership count and known complete duration to a neutral end-of-list `NativePlaylistDetailFooter`, instead of overloading the hero.
- Count derives from the entire verified membership, never search results or unrelated catalog totals; wait for loading completion. Canonical duration is only displayed if **every** member has a known positive duration. Historical normal and ListenBrainz public playlists show count only, not guessed duration.
- Preserve all existing artwork slots, metadata-only / playback eligibility, long-press track actions, normal/smart ownership, public read-only, sorting and search. Never add inline ⋮ or make smart playlists editable as manual lists.
- Do not add “Atualizado há 2 dias” or “Artistas em destaque” absent provider evidence; do not transplant the editorial playlist’s credit and other states into local or smart playlists.
- Source observations remain `runtime_observed` for the single reference and `unknown/requires_runtime` for variants. No physical smoke or APK.

## Limite de 150 registros na playlist pública

- O parser `ListenBrainzPublicPlaylists.parseListenBrainzPublicPlaylistDetail` preserva `sourceTrackCount` (número de entradas no JSPF recebido) antes de aplicar o limite intencional de 150 linhas visíveis. Isso não é uma requisição adicional nem presume itens fora do payload.
- O rodapé da playlist pública indica “Mostrando 150 de 407 faixas” quando o payload contém mais itens do que as linhas materializadas. Se o total da fonte não foi fornecido por um adaptador, sinaliza “N faixas carregadas”; não apresenta o recorte como se fosse a contagem total. Esse total pertence ao payload, não à informação editorial da Apple.
- O ordenamento original, a limitação de leituras, as identidades MBID, o cache, a reprodução condicional e os menus por long press permanecem inalterados.
