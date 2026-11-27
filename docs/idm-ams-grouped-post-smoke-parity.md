# IDM + AMS — grouped physical-smoke remediation (2026-11-26)

Status: in progress; no provider homologated by this document.

Reference: the Local Library artist, album and playlist experiences. MusicBrainz is a catalog adapter, not the visual authority. Provider identity, attribution, and supported actions may vary; geometry, gestures, navigation, search, typography and presentation must not fork. Never simulate playable assets, popularity, source-owned relationships or editorial coverage.

## Evidence and acceptance matrix

| Area | Required acceptance |
|---|---|
| Artwork | With only iTunes enabled in Capas, independently exercise MusicBrainz, Discogs and AMS catalog IDs, exact UPC/album relations, ambiguity/no-match, cache, permission revocation, and actual image render. No catalog permission required; no accidental catalog browsing under artwork authority. If identity cannot be verified, keep honest placeholder and measure reason. |
| Search | Real Top 5/10 judgments on frozen corpus, plus smoke regressions ILLIT/K-pop and compound artist/work queries; exact artist in mixed landing when returned; compare artist, album and recording precision, recall/coverage and type diversity. No fuzzy homonym collapse, source-ID rewriting or invented Discogs track index. Retry partial failed types without losing useful rows, single in-list continuation, no overlay. |
| Detail | Use local library layout as structural contract: same host/frame, top bar, spacing, actions, album lines (no individual durations), footer, related shelves, menus/long-press, rating, pinning and playlist actions where metadata identity permits; preserve disabled playback when there is no audio asset. Validate Artist/Album/Playlist in local plus four catalog adapters. |
| Artist heroes | Same local image crop, proportions, gradient, scroll and collapse; preserve real portrait identity and avoid blank/late hero reflow or stale image after back/reopen. Test missing/slow/failed image, navigation and permission revocation; no borrowed album art masquerading as artist portrait. |
| Home / Explore | Compare visible unique items, rail categories, diversity and continuation against prior targets per provider; test 15 genres independently, including K-pop, true source-supported subgenres, albums, singles/EPs and relevant playlists. Do not manufacture editorial rails or repeat titles to reach counts. Use published AMS evidence, not iTunes assumptions. |
| Performance | First usable section and first popular-track row, total section time, limiter queue vs HTTP, cold/warm cache, image scheduling/cancellation, main-thread stalls and scroll frame windows. Preserve quota and identity checks. |
| Identity | Group Discogs Master/Release correctly, preserve distinct works and disambiguation internally; display clean credits, evidence-backed classifications and variants in Other Versions. |
| Final | Consolidate verified fixes into one candidate only after explicit reconciliation of IDM and AMS branch changes and latest MAIN identity; separate offline, selective live and physical tests. Keep MAIN, production AMS readiness and original PRs unchanged until review. |

## First grouped implementation (IDM branch)

- Inherits isolated artwork-only authorization fix from 0ba08706; exact album/UPC proof, no broad search permission.
- Ports shared metadata album row duration presentation and regression from AMS 43fc0ac9.
- Moves mixed search continuation into the list for MusicBrainz, iTunes and Discogs.
- Uses exact already-resolved iTunes artist store URL and current pager instance for portrait publication.
- Preserves explicit retry for an initial failed typed iTunes stream, even if other types succeeded.
- These are partial implementation changes; no proof yet of cross-provider covers, live relevance, full visual parity, hero render, richer Home/Explore or performance improvement.

Existing tracking: #226–233, #239, #224 and #235. Reuse existing issues; do not close them solely on implementation or CI color.
