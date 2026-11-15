# Provider Engine 2.0 — foundation slice (2026-11-15)

Status: **draft, additive only; no UI wiring**. Android only.

## Goal

A fast, resource-efficient and provider-independent BitChord. A provider, even
the last provider for a capability, can be disabled without hidden fallback.
Local/remote media sources, editorial catalog, identity evidence, artwork,
lyrics, statistics, playback and acquisition are different capabilities.

This change introduces pure Kotlin policy types, durable fail-closed storage,
single-flight coalescing, active in-flight revocation, provenance-aware cache
filtering, and guarded adapter contracts. It does **not** alter the PR #212 UI,
the PR #216 Apple/MusicBrainz/Discogs editorial model, credentials, or
existing playback routes.

## Contracts introduced

- `ProviderKey(providerId, instanceId)`: one service or configured instance.
  Multiple Navidrome/OpenSubsonic accounts can be treated independently.
- `ProviderCapability`: typed purposes, not a single generic "source" flag.
  Catalog search, editorial discography, identity, edition evidence, acoustic
  identification, artwork, synchronized/plain lyrics, distinct charts,
  listening statistics, playlists, personal library, audio and downloads are
  deliberately separate.
- `ProviderDescriptor`, `ProviderReadiness`, `ProviderOperationalHealth` &
  `ProviderCapabilityStatus`: explicitly separates (1) whether an adapter is
  implemented (`NOT_IMPLEMENTED`), (2) whether static prerequisites/credentials
  are met (`NEEDS_CONFIGURATION` vs `READY`), (3) whether the user authorized
  it (`ProviderChoice.enabled` and not quarantined), and (4) live operational
  health (`UNKNOWN`, `OPERATIONAL`, `DEGRADED`, `UNAVAILABLE`).
- `ProviderChoice`: activation and order scoped to one capability.
  Missing choice means **off**, never a default provider.
- `ProviderCapabilityPolicy`: pure ordered selection, ignores disabled,
  uninstalled, unsupported, unready and `UNAVAILABLE` adapters. Unknown saved
  choices survive until their adapter is installed, without becoming active.
- `ProviderPolicyGate`: `(capability, provider instance)`-scoped revision
  tickets and revocation listeners. Replacing a policy invalidates and cancels
  only the specific `(capability, instance)` pairs that ceased to be permitted,
  preserving sibling provider instances (e.g., turning off Apple artwork keeps
  Fanart.tv artwork alive).

## Invariants

1. No provider ID is hardcoded as mandatory; empty eligible sets are legal.
2. No Apple ID, MusicBrainz MBID, Discogs release or AcoustID fingerprint is
   silently treated as an interchangeable canonical identity.
3. Provider disabled for catalog can remain enabled for artwork (and vice
   versa); disabling one provider instance does not revoke sibling instances.
4. In-flight revocation is active: `ProviderQueryExecutor`, `ProviderSingleFlight.runGuarded`,
   `RateLimitedProviderTransport` and `ArtworkProbePolicyGuard` cancel active
   child coroutines/sockets immediately on revocation.
5. `ProviderCachedReadAccess` is fail-closed when no gate is supplied; absence
   of authorization in production never grants cache read access.
6. `CatalogProvenanceFilter` hides single-provider editorial rows when their
   source capability is disabled, while preserving local/personal `MediaAsset`s,
   user ratings, and facts corroborated by other active providers even when
   those entities carry an MBID.

## Cutover Refinement Slices Implemented (2026-11-15)

1. **Single-Authority Legacy Compatibility (`LegacyProviderCompatibility`):**
   Eliminates dual-write across independent `SharedPreferences`. Once migrated
   into `ProviderCapabilitySession`, legacy lyrics and extension readers project
   from `ProviderSessionSnapshot` and writes mutate only `ProviderCapabilitySession`.
   Failed preference writes surface `ProviderSessionStatus.STORAGE_ERROR` and
   quarantine only the affected capability until a subsequent durable commit
   succeeds.
2. **Per-Instance Revocation & Active Cancellation:**
   `ProviderPolicyGate` tracks revisions and cancellation hooks per
   `(ProviderCapability, ProviderKey)` pair. `ProviderQueryExecutor` and
   `ProviderSingleFlight.runGuarded` cancel active operations immediately upon
   revocation while preserving sibling observers when no policy change occurred.
3. **Provenance-Aware Cache Visibility (`CatalogProvenanceFilter`):**
   Protects locally owned tracks, user ratings, and multi-provider corroborated
   entries carrying MBIDs from being hidden when MusicBrainz editorial search
   is turned off.
4. **General Adapter Contracts (`ProviderCatalogAdapters`):**
   Provides `BuiltInProviderDescriptors`, `RateLimitedProviderTransport`
   (re-checking admission both before and after rate-limiter queues such as
   iTunes' 3.1s and Discogs' 3.0s intervals), `ArtworkProbePolicyGuard`
   (covering secondary `HttpURLConnection` CDN dimension/portrait probes),
   and `LyricsCapabilityDispatcher`.

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
