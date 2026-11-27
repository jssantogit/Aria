# AMS physical Android smoke — isolated candidate

**Status: prepared, not visually approved.** The candidate is the AMS audit branch, which was forked from IDM and still carries old branding in parts of the app. Do not use screenshots as final proof of current MAIN Aria branding; compare MusicBrainz and AMS in this same build to isolate layout differences. Reconcile with latest MAIN/IDM before production approval.

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
