# AMS — consolidated editorial and cache foundation

Prior evidence:
- v3 genre matrix 45844219232: 14 curators passed; only Trap failed.
- Reggae 45846152097: 13 rooms, 25 accepted albums, one room read,
  dynamically discovered. Replaces Trap per user decision.
- Soul 45846637151: 21 rooms, 67 distinct candidates in 3 room reads,
  7 exact Soul + 49 combined R&B/Soul = 56 accepted, 11 rejected.
- Cache contracts 45844565571 and Android 45844219351 green.

The 15 current cases have cumulative editorial evidence, not one
full execution of the v4 matrix. The consolidation copies the already
validated cache contract files onto the Soul/Reggae revision and moves
the formerly test-only landing logic into the production gateway.
It preserves the independent search/editorial permissions, source order,
source IDs, exact versus combined-label evidence, and bounded reads.
A partial result cannot satisfy curator-only validation. An intentional
page budget is explicit via bounded. No playlist fallback or playback.

The new operation is tested hermetically with a dedicated workflow,
without repeating the already completed live matrices. Live results
above predate the refactor; the new HEAD still needs validation.
No persistent cache storage is wired, no UI is changed, no provider is
enabled. The consolidation is separate from PR #235 until validated.
