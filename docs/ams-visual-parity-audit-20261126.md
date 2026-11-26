# AMS visual parity audit — 2026-11-26

Baseline: 56279528, canonical integration branch. Static source audit, not screenshots, APK or on-device approval. Reference: approved REA/MusicBrainz native Android compositors and their existing tests; the source is authoritative for the implemented layout, not provider-specific convenience renderers.

## Structural result
No standalone AMS composable screen. MainActivity routes through HomeScreen (Home and genre), SearchScreen (mixed and typed rows via NativeSearchResultRow / NativeDetailTrackRowLayout), and CanonicalProviderDetailHost -> DetailScreen. The canonical MB route also uses DetailScreen. The native top bar, list states, artwork palette, album hero geometry (BrowseType.ALBUM), standard actions, grid, sheet and bottom navigation are shared. Distinct state/IDs are required; presentation must not fork. No physical-pixel or end-to-end verification yet.

## Findings and disposition
| Priority | Finding / source evidence | Disposition |
|---|---|---|
| P0 | DetailScreen SectionCard always rendered the default rounded square album artwork; AMS similar artists arrive in page.sections, unlike MB NativeArtistSimilarShelf. | Fix: typed native artist portrait slot reused from MB, circular art, Artista subtitle, same grid and carousel card. |
| P0 | DetailScreen allowed name-based AppleArtistArtRepository and ArtistSignatureRepository for AMS even though the exact artist ID is known. | Fix: AMS uses only exact source art; neutral placeholder if missing. |
| P0 | Catalog.artist requested latest-release but discarded it; DetailScreen selected explicit featuredRelease only for iTunes; AMS lacked recent release. | Fix: native latest-release parsed, exact route and existing TopReleaseCard; generic label without verified subtype, never first shelf guess. |
| P0 | AppleMusicWebSharedDetailAdapter maps topSongs into generic metadataTracks; NativeArtistSourceMetadataTracksSection labels them Faixas / sem ranking and drops art; repeats artist rather than verified album/year. | Pending: separate ranked source evidence from generic metadata, reuse NativeArtistTracksShelf and NativeArtistInformationalTrackRow with real source art/album linkage, no invented playback. |
| P0 | SearchScreen artist/album/track Apple row renderers use NativeSearchResultRow but no long-press callbacks (mixed and typed). | Pending: use same held item capture/neutral blur as MB; source-valid actions only, no fake play, ratings, download or MBID. |
| P1 | ProviderDetailOverflow uses frosted panel; held AMS card with no exact store URL is inert; featured card link and song actions still need route audit. | Pending: native contextual menu, exact route and permission, no unrelated Settings-only sheet. |
| P1 | MainActivity appleExploreGenreCovers populated only by iTunes; AMS genre grid is same frame but not fed its verified covers. | Pending: source-neutral genre cover projection; preserve existing 15-genre policy. |
| P1 | Home first-page two native shelves; Home/genre hasMore and DetailHost continuation currently omit AMS. | Pending: actual source cursors wired to existing scroll and shared Carregar mais; no new screens. |
| P1 | Artist playlists fetched but not projected; songs/rankings Home missing; album header type/genre and biography/credits evidence not fully projected. | Pending: use existing playlist/detail rows and shared header; no inferred type/date. |
| P1 | Top bar shares NativeDetailAlbumTopBarActions and NativeCollectionOverflowAction; Back closes shelf before page, and origin tab retained by AMS stack; cannot confirm exact spacing or transition visually. | Smoke: actual Android screenshots side by side vs MB at same size/theme, compare topbar, hero, gutters, controls, blur and Back. |

## Release gate
Require same shared compositors, source-typed artist/album/track presentation, no provider-specific visual tokens or legacy fallback, identical empty/loading/error/continuation components, long-press highlight and neutral frosted menu with only authorized actions, canonical subtitles, no fake playback or identity. Run existing REA metrics and Compose contracts in isolated workflow alongside focused tests. Then physical Android smoke: Home -> genre -> artist -> album -> related artist -> Back; Search Geral/Artistas/Álbuns/Músicas -> page -> Back; show-all and held menus, both themes, album/artist with missing artwork/empty/error/loading, opt-out/reorder, compare screenshots against MB. No production activation until audited. Audit branch independent, baseline CI not cancelled or merged.
