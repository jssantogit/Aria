# Provider Engine 2.0 — foundation slice (2026-11-15)

Status: **draft, additive foundation; no UI wiring**. Android only.

## Goal

A fast, resource-efficient and provider-independent BitChord. A provider, even
the last provider for a capability, can be disabled without hidden fallback.
Local/remote media sources, editorial catalog, identity evidence, artwork,
lyrics, statistics, playback and acquisition are different capabilities.

## Current Wiring vs. Pending Cutover (Honest Scope)

### Connected on this branch
- **Android startup & MusicBrainz HTTP (`BitChordApplication`, `ProviderRuntime`, `MusicBrainzClient`):**
  `ProviderRuntime.install(this)` initializes fail-closed before network work,
  `migrateMusicBrainzLegacyAccess()` imports MusicBrainz (`CATALOG_SEARCH`,
  `EDITORIAL_DISCOGRAPHY`, `IDENTITY`) once without overriding explicit opt-outs,
  and `MusicBrainzClient.get()` enforces admission + active single-flight
  cancellation across `CATALOG_SEARCH`, `EDITORIAL_DISCOGRAPHY` and `IDENTITY`.
- **MusicBrainz Room graph cache (`MusicBrainzCatalogIndex`):**
  `cachedArtistGroups`, `cachedGroupReleases` and `cachedRelease` are gated by
  `ProviderCachedReadAccess` (fail-closed in production Android when uninitialized).
- **Per-instance quarantine & recovery (`ProviderCapabilitySession`, `ProviderPolicyGate`):**
  `session.update()` quarantines only the specific `(ProviderCapability, ProviderKey)`
  pairs whose authorization changed before durable disk I/O, preserving sibling
  providers in the same capability and recovering cleanly when a subsequent
  storage commit succeeds.

### Prepared contracts (not yet wired to live UI / PR #212 / PR #216)
- `LegacyProviderCompatibility`: single-authority bridge ready to replace
  direct writes in `AppSettings` (lyrics) and `SourceRegistry` (addons/modules)
  once call sites are cut over together.
- `CatalogProvenanceFilter`: pure visibility filter ready to be applied to
  Room/DAO projections so local/personal assets and multi-provider corroborated
  entities with MBIDs stay visible when MusicBrainz editorial search is off.
- `RateLimitedProviderTransport`, `ArtworkProbePolicyGuard`, `LyricsCapabilityDispatcher`:
  policy-bound transport/probe/dispatch wrappers ready to wrap iTunes, Discogs,
  artwork CDN probes and lyrics providers once PR #216 is reconciled.

## Verification

Targeted regression classes:
- `ProviderCapabilityPolicyTest`
- `ProviderPreferenceStoreTest`
- `ProviderCapabilitySessionTest`
- `ProviderQueryExecutorTest`
- `ProviderSingleFlightTest`
- `ProviderCachedReadAccessTest`
- `ProviderReactiveCacheTest`
- `LegacyProviderChoicesTest`
- `MusicBrainzCapabilitySearchTest`
- `MusicBrainzTransportAdmissionTest`
- `MusicBrainzCachedSearchRepositoryTest`
- `CatalogProvenanceFilterTest`
- `ProviderCatalogAdaptersTest`

Run:

```bash
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.Provider*Test"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.CatalogProvenanceFilterTest"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest
```

GitHub Android CI additionally validates Android dependencies and the relevant
shared module. **Do not mark this as CI-passing before the PR HEAD check succeeds.**

## Integration boundary

This branch is based on `main` and intended to be integrated after PR #209,
PR #212, and PR #216 are reconciled. PRs #212 and #216 remain independently
owned, open/draft and unchanged. No UI changes, forced merge, or physical APK
are authorized by this slice.
