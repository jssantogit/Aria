# Provider Engine 2.0 — Android integration audit (PR #219)

Date: 2026-11-16. Scope: Android only. GitHub issue [#218](https://github.com/jssantogit/BitChord/issues/218). This document is an implementation map, **not** a merge approval.

## Provider × capability × runtime integration

| Provider / instance | Capability | Implementation and admission boundary | Status | Tests |
|---|---|---|---|---|
| MusicBrainz | CATALOG_SEARCH / IDENTITY | MusicBrainz catalog/client search + canonical search projections | Connected | MusicBrainz catalog/index tests, ProviderCachedReadAccessTest |
| MusicBrainz | EDITORIAL_DISCOGRAPHY | Catalog graph/index; revocable Room cache read | Connected | ProviderCachedReadAccessTest |
| iTunes | CATALOG_SEARCH / EDITORIAL_DISCOGRAPHY | KtorItunesCatalogTransport + RateLimitedProviderTransport, backed by #216 | Connected; #216 reconciliation required | Apple editorial, ItunesCatalog tests |
| iTunes | RELEASE_ARTWORK | AppleHighResolutionArtwork + probe guard and identity verification | Connected | AppleHighResolutionArtworkTest |
| Cover Art Archive | RELEASE_ARTWORK | PolicyGuardedCoverArtArchiveTransport + cache eligibility; separate Front-v3/v5 DAO leaks blocked | Connected | MusicBrainzPublicMetadataIndexTest, ProviderCachedReadAccessTest |
| Apple Artist Art | ARTIST_ARTWORK | Exact storefront identity; guarded HTML HTTP; independently revocable offline image warming | Connected | AppleArtistArtCapabilityTest, VisualStillFileStoreTest |
| Discogs | EDITION_METADATA | Disabled by default, needs optional encrypted token for authenticated search; authenticated and public index routes both policy guarded | Connected conditionally; explicit opt-in and credentials | DiscogsAuthenticatedDiscoveryTest, DiscogsPublicArtistDiscoveryTest |
| ListenBrainz | PUBLIC_PLAYLISTS | Anonymous Ktor search/detail/recording evidence; revocable network + read cache; separate CAA cover permission | Connected | ListenBrainzPublicPlaylistTest, ListenBrainzPublicPlaylistSingleFlightTest |
| ListenBrainz | CHART_MOST_PLAYED | Anonymous popularity/sitewide/top-artist queries + revocable network and TTL cache | Connected | ListenBrainzPopularityClientTest, ListenBrainzSitewideClientTest |
| ListenBrainz | PERSONAL_RECOMMENDATIONS | Account-scoped experimental CF Recording candidates; independent admission/cancellation, including token validation, retry and in-memory cache | Connected at adapter; current UI wiring requires #212 reconciliation | ListenBrainzRecommendationClientTest |
| ListenBrainz | LISTENING_STATISTICS | Direct playing-now/finished and persistent WorkManager outbox; independent user consent + canonical dispatch | Connected | ListeningSubmissionGateTest, ListenBrainzSubmissionStatusTest |
| Last.fm | LISTENING_STATISTICS | track.scrobble/now playing gated; account authorization remains separate | Connected | ListeningSubmissionGateTest |
| Lyrics providers | LYRICS_LOOKUP / variants | Canonical selection bridged from legacy settings, per-instance opt-out | Connected for offered sources | ProviderRuntimeMigrationSafetyTest, provider preference/compatibility tests |
| Addon / custom module | EXTENSION_PLAYBACK and optional DOWNLOAD | SourceRegistry exact instance-local EXTENSION_PLAYBACK selection and independent DOWNLOAD eligibility; source client in-flight dispatch remains to audit | Partial; audit actual per-addon network dispatch | ProviderCatalogSourceRegistryTest |
| Soulseek | DOWNLOAD | Native manual search now denies without canonical DOWNLOAD ticket as well as legacy pilot readiness; active socket/transfer revocation still requires separate audit | Partial; final consent / dispatch audit outstanding | ProviderCapabilitySessionTest |
| Local MediaStore | PERSONAL_LIBRARY | Local personal data never depends on remote editorials | Connected locally; not subject to external network gate | provider provenance tests |
| AcoustID | ACOUSTIC_IDENTIFICATION | No implemented adapter | NOT_IMPLEMENTED | Inventory only |
| Store sales | CHART_SALES | No implemented adapter | NOT_IMPLEMENTED | Inventory only |

## Security / retention invariants

- **Fail closed** if policy not installed or migration failed; no implicit provider fallback.
- No provider is mandatory. The last enabled provider can be disabled; an empty selection persists through restart.
- Revocations are scoped to **(capability, provider instance)**, not all capabilities of one vendor.
- Source-owned cache stays stored when revoked but does not publish raw rows/artwork. In-flight guarded cache reads are actively cancelled.
- CAA Front-v3/v5 artwork must satisfy its own authorization, TTL and URL criteria; listening/public playlist metadata can remain visible with CAA off.
- Apple artist requests and visual-still downloads cancel underlying OkHttp calls. Partial visual-still files are never promoted after cancellation.
- ListenBrainz personal submissions stay account-scoped. Offline queued occurrences are not discarded simply because a canonical capability is temporarily revoked; do not retry while disabled.

## Validation and merge blockers

- **Android CI SUCCESS on the exact final HEAD remains mandatory.** Intermediate or canceled runs do not count.
- PR #219 is stacked on draft PR #216. Final integration of the latter must not introduce competing catalog routes or duplicate provider authority.
- PR #212 is an independent frozen UI. No new UI, shadow screen or layout changes may replace its pixel parity; the planned native Sources tabs must be integrated only once the UI dependency can be reconciled. At audit time PR #212 reported merge conflicts, so this remains a real dependency.
- Physical smoke, merge, and release are not authorized at this stage.
- Still audit end-to-end redirects/network/caches for other public ListenBrainz recommendation transports, server/addon pipelines, content provenance in every view, and source list UI before closing #218.

This document intentionally distinguishes implemented cuts from incomplete acceptance, and should be revised from code and CI evidence rather than assumed green.
