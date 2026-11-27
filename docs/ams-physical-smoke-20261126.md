# AMS physical Android smoke — isolated candidate

**Status: second physical smoke candidate, offline and focused live gates approved; not visually approved.** The candidate is the AMS audit branch, which was forked from IDM and still carries old branding in parts of the app. Do not use screenshots as final proof of current MAIN Aria branding; compare MusicBrainz and AMS in this same build to isolate layout differences. Reconcile with latest MAIN/IDM before production approval.

## Install
Use the ARM64 APK from the branch-local workflow. It is devDebug, package `com.dev.bitchord.ams.smoke`, launcher label Aria AMS Smoke, separate app data. It will not overwrite normal dev/prod. No source is opted in automatically. In Providers, explicitly enable Apple Music Web for Busca, Discografia and Rankings; put it first in each relevant capability. Keep MusicBrainz available as a comparison; disable unrelated catalog sources if needed to isolate a case. Do not enter personal credentials.

## Physical checklist
| Scenario | Verify |
|---|---|
| Home | Same topbar, hero, margins, cards; recent/new releases; Músicas em alta and 20 chart tiles. Scroll continuation; no duplicated songs or fake playback. |
| Search | Geral, Artistas, Álbuns, Músicas; compact shared rows and search transitions; long-press shows captured item, darkens surrounding content, neutral blur, dismisses on Back; no invented playback/download. |
| Artist | Taylor Swift, Beyoncé, Gilberto Gil; compare MusicBrainz same artist/same screen size. Hero, title/toolbar alignment, recent release, popular songs artwork, discography sections, playlists, similar circular artists, year captions, Ver tudo, scrolling. |
| Album | Open a release from artist, artist credit back to artist, track rows, genre/year/summary, related releases; hold a track; no unsupported play actions. |
| Playlist | Open Metal/Rock or artist playlist; native header/cover, ordered rows, held menu, track to album, Back restores same route. Soul may lack verified playlist rail; don't infer missing data. |
| Genres | Metal, Rock, Soul, Reggae; same Explore grid and native genre page, pagination, no generic playlists; cover placeholders when evidence absent. |
| Rankings | Brazil and US/JP/DE album charts; 100 positions, no fabricated playback; open official source. |
| Navigation & consent | Search -> artist -> album -> artist; Home -> genre -> artist -> playlist -> track album -> Back; switch provider/disable each capability and verify stale cards/menu are unavailable. |
| Both themes | Compare light and dark screenshots at identical font/display scaling; neutral blur, no overlay on held item, native spacing and touch highlight, no legacy popup or parallel provider screen. |

Mark actual results PASS/FAIL/NOT RUN and attach screenshots for discrepancies. Unit/live catalog tests do not certify physical rendering. No release or merge is authorized by the build.

## Known evidence limits
All ten album charts returned 100 positions using one request in focused live #45911136642. All ten song charts and other three canonical live groups passed in prior #45909772114; exact historical cause of the prior three album failures was not recorded. Only three genres were covered by that native consumer live test; existing separate 15-genre coverage is distinct. Current production registry remains BLOCKED absent the isolated debug flag and package.


## First physical run feedback
Physical run performed on Android 14 Samsung A54. Catalog data working; visual and local-action approval withheld by user. See the six findings in ams-visual-parity-audit-20261126.md. Do not mark the release gate approved until the fixes are re-tested. Debug performance diagnostics have 27 recovered UI stalls (max 1907.7 ms), no last crash, cause unknown.

## Second physical run — post-correction acceptance
Validated baseline: `d873c005`. [Offline contracts #45924765760](https://github.com/jssantogit/Aria/actions/runs/45924765760) and [focused live #45924765767](https://github.com/jssantogit/Aria/actions/runs/45924765767) passed. The focused live result covers 10/10 daily playlist identities/covers, BR/AU 100 tracks and 100 admissible labels each, BR 100 ranked albums/covers, eight Home rails, five rails each for K-pop and Metal, and a native album with 32 tracks. Home upstream read 6513 ms on CI; not a device frame benchmark.

Install the new ARM64 APK over the first isolated smoke package (keep its data for persistence checks). Verify the following on the same A54, font/display settings and theme where possible. Record PASS/FAIL/NOT RUN, attach screenshots of failed cases and export diagnostics for this new capture. No requirement to replay the complete first live matrix.

| Priority | Physical test | Acceptance |
|---|---|---|
| P0 | Album from artist and search | Individual track durations hidden, total duration preserved where verified; native hero and menus. |
| P0 | Mixed and typed search pagination | Mais resultados appears after rows, no overlap, tap adds results once. |
| P0 | Home -> official Top 100 BR and another market | Real cover, native playlist detail, ordered tracks, one topbar, Back returns to Home. Repeat with only Rankings enabled; daily playlist must still open. |
| P0 | Home -> Top 100 albums BR -> album -> Back | Same Library grid, original rank and cover, exact native album, scroll restored; no second topbar. Album detail requires Discografia. |
| P0 | Long press and overflow on artist, album, playlist and song | Correct captured item, neutral blur, shared actions; fix/rate/share where supported; song/album/playlist add to local playlist. No fabricated play/download. |
| P0 | Local playlist and Minhas avaliações | Add a song, then a complete album or playlist; exact labels, no duplicates, Sem áudio when unresolved; reopen and edit a song rating; restart to check persistence. |
| P1 | Home, K-pop and Metal | New actual rails, playlist covers, correct hierarchy and scrolling, no video, no duplicate continuation. |
| P1 | Provider revocation and navigation | Disable/reorder AMS Rankings while a chart is visible; stale results disappear. Keep existing native Back behavior. |
| P1 | Responsiveness | Capture fresh diagnostics during Home, scroll, search, genre and detail navigation; compare stall count/max and frame windows with first run (27 stalls, max 1907.7 ms); do not attribute old stalls to AMS without stack evidence. |

The APK is still isolated debug only. MAIN/IDM remain unmodified, and the branch must be reconciled with current MAIN before a production merge. Green automated tests do not certify these physical acceptance cases.

## Third physical run — artwork, shared playlists and styles

Validated application baseline: `e1a79a0b`. [Offline #45931808461](https://github.com/jssantogit/Aria/actions/runs/45931808461) and [focused live #45931808387](https://github.com/jssantogit/Aria/actions/runs/45931808387) passed. The live report recorded 24 album entries per style (Death, Heavy, Thrash, Black, Doom Metal), with 1/1/2/1/1 playlists respectively. These are coverage counts, not proof of unique or individually relevant albums across styles. Seven editorial artwork CDN samples passed the prior live check. Neither check proves Android rendering.

Install over the same isolated ARM64 package; retain app data. Repeat any unresolved P0 from round two.

| Priority | Physical test | Acceptance |
|---|---|---|
| P0 | Home and Explore editorial shelves | Artwork visible on cards before opening detail, including Top 100 Brasil, city charts, touring and curated playlists; no blank card when detail has verified artwork. |
| P0 | Local playlist ko vs Top 100 Brasil | Shared native playlist structure, hero, toolbar, spacing, rows and context-appropriate actions; no parallel catalog layout. |
| P0 | Metal -> five styles | Each style renders the relevant albums and editorial playlists; inspect representative titles for relevance, no unrelated fallback or duplicate entries, Back restores parent. |
| P1 | Artist -> similar artists | Circular artwork with centered artist name only; no redundant artist subtitle. |
| P1 | Diagnostics and navigation | Scroll, reopen and switch capabilities; check stale artwork, blank placeholders, layout and performance; export fresh diagnostics. |

Animated and landscape editorial artwork for playlists, albums, artists and player is a separate enhancement, not a round-three acceptance claim. Keep MAIN and IDM unchanged; production admission remains blocked pending physical approval and reconciliation.

## Third physical smoke feedback — 2026-11-27

User screenshots show editorial covers now rendering, similar artist labels centered, and Heavy Metal populated. Jazz Fusion still empty. Top 100 Brasil uses a square sleeve rather than the official immersive hero; ILLIT shows static artist artwork. User requests: retain only song chart playlists in Home, keep disabled playback and shuffle controls visible for metadata-only playlists, hide technical partial-data notices without losing completeness evidence, and enrich every subgenre from exact track genre labels independently of a specific playlist. Native animated/hero artwork requires evidence of actual source asset fields, validated URLs and lifecycle-aware rendering; not approved yet. Android diagnostic capture reports nine recovered UI stalls (max 1113.4 ms), no last crash, Apple Coil successes 537, errors 0, cancelled 25. Not evidence of animated artwork rendering or root cause.
