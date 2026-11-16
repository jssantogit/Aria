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
| Addon | EXTENSION_PLAYBACK and optional DOWNLOAD | SourceRegistry exact instance tickets; isolated playback/download AddonSource instances; revocation cancels detached SharedCalls and underlying OkHttp | Connected at source dispatch; Media3 HTTP byte/call gate wired; source-cache complete-hit provenance and Cast handoff admission now guarded; exact-HEAD CI and device acceptance outstanding | ProviderCatalogSourceRegistryTest, AddonSourceTest, SharedCallsTest |
| Custom module (QuickJS) | EXTENSION_PLAYBACK and optional DOWNLOAD | Isolated playback/download ModuleSource managers; ProviderQueryExecutor cancellation, shared producer cancellation and cancellable JS/index/host fetch HTTP | Connected at source dispatch; Media3 HTTP byte/call gate wired; source-cache complete-hit provenance and Cast handoff admission now guarded; exact-HEAD CI and device acceptance outstanding | ModuleSourcePolicyTest, ModuleNetworkCancellationTest |
| Soulseek | DOWNLOAD | Native manual search now denies without canonical DOWNLOAD ticket as well as legacy pilot readiness; active socket/transfer revocation still requires separate audit | DevDebug manual entrypoint admitted with canonical DOWNLOAD ticket; tracked search listeners/peer sockets revocable. Partial pending device/entrypoint acceptance | ProviderCapabilitySessionTest |
| Local MediaStore | PERSONAL_LIBRARY | Local personal data never depends on remote editorials | Connected locally; not subject to external network gate | provider provenance tests |
| AcoustID | ACOUSTIC_IDENTIFICATION | No implemented adapter | NOT_IMPLEMENTED | Inventory only |
| Store sales | CHART_SALES | No implemented adapter | NOT_IMPLEMENTED | Inventory only |

## Security / retention invariants

- **Fail closed** if policy not installed or migration failed; no implicit provider fallback.
- No provider is mandatory. The last enabled provider can be disabled; an empty selection persists through restart.
- Revocations are scoped to **(capability, provider instance)**, not all capabilities of one vendor.
- Editorial/artwork cache stays provider-scoped. Source-backed **audio** cache is separate: complete hits require an exact current winning-instance ticket, active reads revoke, and incomplete/unknown-provenance spans are dropped before a fresh resolution; never use a stale cache row to implicitly grant playback.
- CAA Front-v3/v5 artwork must satisfy its own authorization, TTL and URL criteria; listening/public playlist metadata can remain visible with CAA off.
- Apple artist requests and visual-still downloads cancel underlying OkHttp calls. Partial visual-still files are never promoted after cancellation.
- Addon and custom-module source dispatch is capability-scoped; denying playback does not implicitly deny authorized downloads from the same configured instance. Detached producers are cancelled on capability revocation.
- Soulseek active-session sockets, devDebug manual-search listeners and registered peers are closed on canonical DOWNLOAD revocation; credential changes invalidate bound tickets, including a last-moment admission check before tracked registrations and durable publication.
- ListenBrainz personal submissions stay account-scoped. Offline queued occurrences are not discarded simply because a canonical capability is temporarily revoked; do not retry while disabled.

## Validation and merge blockers

- **Android CI SUCCESS on the exact final HEAD remains mandatory.** Intermediate or canceled runs do not count.
- PR #219 is stacked on draft PR #216. Final integration of the latter must not introduce competing catalog routes or duplicate provider authority.
- PR #212 is an independent frozen UI. No new UI, shadow screen or layout changes may replace its pixel parity; the planned native Sources tabs must be integrated only once the UI dependency can be reconciled. Its mergeability may change independently; do not mistake absence of Git conflicts for pixel parity or approval.
- Physical smoke, merge, and release are not authorized at this stage.
- Android CI [#43932559597](https://github.com/jssantogit/BitChord/actions/runs/43932559597) passed on code HEAD `b11610a` for the addon, module and Soulseek cuts above. Android CI [#43934240210](https://github.com/jssantogit/BitChord/actions/runs/43934240210) also passed on the documentation-only former HEAD `f483e82`. Both are historical gates: subsequent code changes require a fresh exact-HEAD SUCCESS.
- Still audit downstream media bytes, server/provider redirects, personal-data provenance in every presentation flow, active P2P pilot entrypoints and final source list UI before closing #218.

This document intentionally distinguishes implemented cuts from incomplete acceptance, and should be revised from code and CI evidence rather than assumed green.

## Hardening continuation — 2026-11-16 (after `f483e82`)

The following changes are code-level cuts, **not** final integration/merge approval:

- **No implicit Soulseek opt-in on upgrade:** `ProviderAuthorityCoordinator.migrateOnce` no longer appends an enabled Soulseek choice to an already-persisted `DOWNLOAD` category. A persisted empty list and an existing addon-only selection stay untouched; only a genuinely absent category receives the first-install import baseline. Regression tests cover both cases.
- **Credential / P2P lifecycle:** `SoulseekCredentialVault.save/forget` invalidates the previously bound P2P session ticket and refreshes canonical Soulseek readiness after the vault transition. Unit tests prove old sockets close before readiness refresh, late socket registration is refused and a subsequent admission must bind a new ticket. This is not evidence of complete A0 entrypoint coverage.
- **Independent dispatch order:** `SourceRegistry.active()` and `activeForDownload()` now select provider instances using their own `EXTENSION_PLAYBACK` and `DOWNLOAD` canonical priorities respectively; legacy rank and persisted list order serve only as stable fallbacks. Regression tests cover opposite priority orders for the same addon and module.
- **Still open:** the `PlaybackService` source-backed stream path hands resolved URLs to generic Media3 `OkHttpDataSource`; in-flight media-byte revocation and redirect scope are not yet proved. The experimental A0 P2P transports require an actual entrypoint audit, including consent, socket lifecycle, durable publication, credential changes and device acceptance. The new native Sources tabs, provider provenance across presentation flows, and frozen #212 / catalog #216 reconciliation remain separate blockers.
- **CI:** this continuation changes code and tests after `f483e82`; the exact final commit must pass Android unit/build and both shared test steps. Earlier green runs must not be transferred to the new SHA.

## Continued hardening: real A0 entrypoint + Media3 bytes — 2026-11-16

- **Real devDebug A0 session admission:** tests `fef36f6`, implementation `2ec1852` + `3424117`. `A0ManualLoginActivity` calls `SoulseekActiveSession.admitManualSession(ProviderRuntime.gateOrNull())` before credentials/network work; refusal is fail-closed. The server, search listener, directly accepted peers and indirect search peer sockets are generation-bound and closed on DOWNLOAD revocation. P/F transfer handoff remains registered separately and A3's durable commit still uses `commitIfCurrent`. No shared UI or prod release entrypoint was changed.
- **Media3 source-backed HTTP bytes:** tests `85eb77a` and `b0037f`, implementation `fb4def8`, `af600a2`, `4a8f6ea`, `811eaf4` and `a64dc9d`. The winning addon/custom-module instance now supplies an in-memory `ProviderPlaybackByteLease` via `DataSpec.customData` to a post-resolution datasource. A disabled instance is denied before opening and on both sides of each read, leaving unrelated providers untouched.
- **Physical cancellation:** because `OkHttpDataSource.close()` alone cannot guarantee stopping `open()` before response headers arrive, a per-datasource `ProviderPlaybackCallFactory` tracks the current OkHttp `Call` and cancels it on capability revocation. A MockWebServer `NO_RESPONSE` regression asserts the suspended request terminates; results remain provisional until this exact HEAD passes CI.
- **Explicit residual limits:** existing source-owned **audio cache** reads can bypass this post-resolver network layer, so full authorization of cached playback still needs verification. Cast receivers use a handed-off remote URL and are outside in-process call cancellation. Redirect target/credential-header boundaries must be audited and tested. The A0 transport still needs final live devDebug/device acceptance. Do not assert all of these are solved based on a unit/build CI.
- **Acceptance:** latest exact-HEAD Android CI (unit, Automix, native UI presentation) is mandatory after these changes. This document and the GitHub issue track code evidence; neither authorizes merging PR #219 or replacing the frozen #212 UI.

## Source-backed playback boundary — further code and test cuts (2026-11-16)

- **Repeatable Media3 opens:** `70d3b12` (regression tests), `8bf9874` (implementation). A normal `DataSource.close()` no longer permanently poisons the per-source OkHttp factory; reuse for seek, next item and explicit opt-in after revocation is tested. Old tickets still fail closed.
- **Unrevocable Cast handoff:** `87ee22b` (tests), `03fd09f` + `5f41962` (implementation). `resolveForCast` rejects `bitchord://source` and `bitchord://canonical` before resolving or exporting a provider URL. Local and ordinary non-provider media admission is unchanged. This is a deliberate fail-closed limit, **not** new authorized Cast support for extensions.
- **Source audio cache:** `e71978a` (policy tests) → `fe7dd0d` (pure ticket admission), `159f085`, `65f4e0f`, `0150d2d`, `3f516fc` (integration). The resolver attaches the *winning* provider instance to `DataSpec.customData`; `AudioCache.recordServed` tags its ephemeral source cache entry with the same non-secret instance identity. A complete hit is admitted only for that instance and subscribed to revocation; partial or unproven cached spans are evicted before another source resolution, preventing mixed-provider audio and bypass of a disabled source. If an entry is in use and eviction fails, playback fails closed. This covers logical `bitchord://source` cache entries, not every historical alternative cache key.
- **HTTP redirects:** `7886262` (MockWebServer regressions) → `b29d6ad` (implementation). Protected source HTTP calls enforce original scheme/host/port **on every actual network hop** via a network interceptor before sending redirect requests. Same-origin redirects continue; ordinary non-provider streams retain normal redirect handling. Custom source headers are not forwarded to a different HTTP origin.
- **Verification gate:** new source/cache/Cast/redirect changes have not inherited the old green CI. Require Android unit tests, APK build, shared Automix and native presentation checks on the exact final documentation/code HEAD. Physical Soulseek and playback/caching acceptance and #212/#216 reconciliation remain independent blockers.

