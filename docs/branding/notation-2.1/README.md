# Aria — Notation 2.1

Owner-approved identity: musical A + lowercase ria. Vector paths and colors from the approved Notation 2.1 package. Dark assets (for light backgrounds) use #B66A24 and #DB933B; no legacy dark bronze. Light assets share the ivory-to-honey gradient. The Android launcher layers preserve the 108dp adaptive canvas and approved foreground transform. No external font dependency.

## Scope of first slice
- Replace only the default Android adaptive launcher icon and themed monochrome layer. Minimum SDK is 26.
- Store canonical wordmark vector variants for subsequent UI integration.
- Preserve application IDs, namespace, signing, data paths, deep links, updater contracts and existing icon alias identities.

## Remaining
- Audit every localized visible app label, dev/benchmark overrides, notification/widget/shortcut branding, selectable icon variants and previews, Android Auto, desktop, TV and Wear surfaces.
- Preserve upstream attribution/license; do not rename protocol or storage identifiers without an explicit migration.
- Build and test exact branch HEAD; verify actual launcher shapes, themed icon, existing alias selection and upgrade/data retention. No physical smoke or merge before owner review.
