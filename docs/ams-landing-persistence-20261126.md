# AMS persistent curator landing cache

Additive canonical Room migration 17 → 18. A source-specific table stores
a versioned, storefront-default-locale genre landing; no raw HTTP response,
credential, canonical identity, MediaAsset or playback record is written.
Cache permission is independently checked on every read/write and the gateway
keeps its outer editorial ticket. Fifteen-minute max age, 128 entries, 1 MB
payload bound, validated curator/room/album evidence, URLs without queries or
credentials. Partial or empty aggregates are not persisted; corrupt/expired
rows miss. Refresh bypasses read, not policy. SQLite/serialization failures
do not turn a successful live read into a failed editorial result.

This is a concrete Room implementation for curated genre landings only.
The optional dependency is injectable; production screen construction and
persistent storage of other AMS operations remain to be integrated. The
current provider inventory remains BLOCKED. No UI change. Dedicated tests
cover actual database reopen, migration, expiry, scope, and invalid evidence.
