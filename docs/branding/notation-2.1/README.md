# Aria — Notation 2.1

Owner-approved identity: musical A + lowercase ria. Vector paths and colors from the approved Notation 2.1 package. Dark assets (for light backgrounds) use #B66A24 and #DB933B; no legacy dark bronze. Light assets share the ivory-to-honey gradient. No external font dependency.

## Implementation
- Default adaptive Android icon preserves the approved 108dp layers. All twelve selectable glass variants and notification/shared UI marks use the same Notation geometry; variant treatments await visual acceptance.
- Production and dev application IDs are independent. See [Android identity migration](ANDROID-IDENTITY-MIGRATION.md). Kotlin namespace migration remains pending.
- Android is the sole product platform. See [Android-only scope](ANDROID-ONLY.md).

## Validation
- Preserve upstream attribution/license and historical evidence. Do not blindly rename persisted identifiers.
- Build and test exact branch HEAD; verify actual launcher shapes, themed icon, existing alias selection, notifications, backup and fresh install. No physical smoke or merge before owner review.

## Application accent
- The user requested replacing the global Apple-inspired blue accent with Notation amber. Initial tokens derive from the approved logo gradient: light mode burnt amber `#B74D0A`, dark mode luminous amber `#FFB747`; white/black filled-control ink respectively. Contrast against white/black is 5.15:1 / 12.12:1. Neutral primary surfaces and destructive colors stay intact.
- Central Aria accent tokens drive legacy shared helper aliases, selected tabs, inline actions, settings controls, buttons and the playlist wizard. Pin-edit confirmation now uses the shared token instead of its direct blue literal. Data-coded five-star rating palette, artwork and provider marks are not redefined by this change. The CI identity audit inventories remaining common literal blues for role-by-role review; full source audit and visual acceptance remain pending.

### Accent audit follow-up
- Fixed the shared MessageState action's white-on-luminous-amber foreground, and the Home hero caption's use of the light-theme accent on an always-dark artwork scrim. Both use the centralized contrast roles. Native AppCompat day/night accent resources are now declared and checked for exact Compose parity. Neutral primary and semantic palette remain unchanged.
- Added a separate fast static inventory of literal blue colors, including non-original hues, decimal RGB, resources and explicit blue names. Results are candidates, not proof of unwanted accent; artwork, ratings and provider identities require role review. Full visual acceptance remains pending.

- Full accent inventory on `f9a48199`: 981 tracked runtime source/resource files, 42 candidates. Classified the three remaining old brand accent sites: connection onboarding mesh (warm Notation variants), Replay poster top-rank marker (luminous amber), and queue swipe affordance (luminous amber, black icon on the always-dark player). Kept artwork/service/genre/placeholder gradients, Google mark and compact rating semantic scale; the Home hero neighboring white subtitle is unrelated to filled accent. The inventory deliberately reports these remaining non-accent colors for transparency.
