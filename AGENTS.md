# Aria repository instructions

- **Android only.** Do not develop, release or require Desktop, Wear OS or Android TV builds or CI. Keep shared production modules and their JVM tests when needed for Android.
- Use the approved Notation 2.1 assets under docs/branding/notation-2.1. Do not reintroduce BitChord branding, donation links, update endpoints or maintainer identities as Aria's own.
- Preserve upstream license, copyright, attribution and historical evidence. Do not use upstream-owned service credentials or endpoints as Aria defaults.
- Package identity changes require an explicit migration and fresh-install/backup validation; do not blindly rename persisted identifiers.
- Verify exact HEAD with Android CI and applicable backend/shared tests; physical Android smoke remains separate. No merge without owner authorization.
