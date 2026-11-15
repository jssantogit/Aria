# Provider Engine 2.0 — foundation slice (2026-11-15)

Status: **draft, additive only; no runtime wiring**. Android only.

## Goal

A fast, resource-efficient and provider-independent BitChord. A provider, even
the last provider for a capability, can be disabled without hidden fallback.
Local/remote media sources, editorial catalog, identity evidence, artwork,
lyrics, statistics, playback and acquisition are different capabilities.

This change introduces only pure Kotlin policy types. It does **not** install,
probe, contact, enable or decommission any provider; it does not alter the
current PR #212 UI, the PR #216 Apple/MusicBrainz/Discogs editorial model,
credentials, playback or existing settings.

## Contracts introduced

- `ProviderKey(providerId, instanceId)`: one service or configured instance.
  Multiple Navidrome/OpenSubsonic accounts can be treated independently.
- `ProviderCapability`: typed purposes, not a single generic "source" flag.
  Catalog search, editorial discography, identity, edition evidence, acoustic
  identification, artwork, synchronized/plain lyrics, distinct charts,
  listening statistics, playlists, personal library, audio and downloads are
  deliberately separate.
- `ProviderDescriptor`: *registered* adapter capabilities and readiness.
  `READY` must be asserted by its adapter after satisfying prerequisites.
  The registry does not claim that an unimplemented service already works.
- `ProviderChoice`: activation and order scoped to one capability.
  Missing choice means **off**, never a default provider.
- `ProviderCapabilityPolicy`: pure ordered selection, ignores disabled,
  uninstalled, unsupported and unready adapters. Unknown saved choices may
  survive until their adapter is installed, without becoming active.
- `ProviderPolicyGate`: capability-specific revision tickets. Consumers must
  obtain `open` *before* any outbound request, and check `accept` *before*
  publishing a result. Policy changes revoke stale results of the affected
  capability without invalidating unrelated requests.

The policy is a **safety seam**, not a completed dispatcher: it cannot cancel
an active OkHttp request until call sites are actually connected.

## Invariants

1. No provider ID is hardcoded as mandatory; empty eligible sets are legal.
2. No Apple ID, MusicBrainz MBID, Discogs release or AcoustID fingerprint is
   silently treated as an interchangeable canonical identity.
3. Provider disabled for catalog can remain enabled for artwork (and vice
   versa); a blocked provider cannot be contacted through this policy.
4. The same provider kind can have separate configured account/server instances.
5. In-flight result revocation is capability-specific. Once call sites use the
   gate, they must also cancel upstream work where practical.
6. This slice introduces no background work, network requests, cache migration,
   API costs or UI changes.

## Verification

TDD regression class: `ProviderCapabilityPolicyTest`. It checks last-provider
off, cross-capability isolation, stable instance priority, missing/not-ready/
unsupported adapters, in-flight result revocation, unaffected audio tickets
and duplicate instance rejection.

Run:

```bash
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.ProviderCapabilityPolicyTest"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest
```

GitHub Android CI additionally validates Android dependencies and the relevant
shared module. **Do not mark this as CI-passing before the PR HEAD check succeeds.**

## Follow-up slices (not included)

1. Inventory real adapters from PR #216 and current Android baseline; verify
   readiness per *actual supported capability*, license, credentials and cost.
2. Persist capability preferences/ordering with explicit migration from legacy
   lyric and source settings. Do not delete credentials or library state.
3. Route first real adapters through pre-dispatch admission and post-response
   revocation, then add cancellation, single-flight and bounded rate limits.
4. Add versioned caching and evidence resolution to shared catalog/artwork
   contracts without making Apple/MusicBrainz mandatory.
5. Connect the native settings UI from PR #212 without parallel screens.
6. Measure latency, API requests, data integrity and provider-off behavior on
   the exact integrated Android HEAD before smoke/APK.

## Integration boundary

This branch is based on main and intended to be merged/cherry-picked as a
conflict-minimal foundation **after approved integration sequencing**.
PRs #212 and #216 remain independently owned, open/draft and unchanged.
No merge, forced sync or physical APK is authorized by this foundation slice.
