# Aria — Notation 2.1

Owner-approved identity: musical A + lowercase ria. Vector paths and colors from the approved Notation 2.1 package. Dark assets (for light backgrounds) use #B66A24 and #DB933B; no legacy dark bronze. Light assets share the ivory-to-honey gradient. No external font dependency.

## Implementation
- Default adaptive Android icon preserves the approved 108dp layers. All twelve selectable glass variants and notification/shared UI marks use the same Notation geometry; variant treatments await visual acceptance.
- Production and dev application IDs are independent. See [Android identity migration](ANDROID-IDENTITY-MIGRATION.md). Kotlin namespace migration remains pending.
- Android is the sole product platform. See [Android-only scope](ANDROID-ONLY.md).

## Validation
- Preserve upstream attribution/license and historical evidence. Do not blindly rename persisted identifiers.
- Build and test exact branch HEAD; verify actual launcher shapes, themed icon, existing alias selection, notifications, backup and fresh install. No physical smoke or merge before owner review.
