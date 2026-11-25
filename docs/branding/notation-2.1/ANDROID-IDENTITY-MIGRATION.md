# Android identity migration

Aria production applicationId: `com.music.aria`; development: `com.dev.aria`; benchmark suffix remains `.benchmark`.

This is a different Android installation from the legacy package. Private sandbox data and Android Keystore entries do not automatically move. Do not uninstall the old installation before verifying an exported backup and explicitly reconfiguring any accounts or protected credentials. The existing JSON backup covers listening statistics and settings, not a complete app/database/credential transfer. Legacy backup tags remain readable, and new exports use the Aria tag.

Kotlin/Android namespaces are now `com.music.aria`, `com.music.aria.shared` and `com.music.aria.sharedui`. The migration includes class paths, JNI exports, manifests, Gradle and native build verification. The structural gate and native APK build passed at `634e6ccb`; this does not establish complete reflection, serialization or private-data migration compatibility.

Updater: own GitHub repository only, Aria APK name prefix, archive package must match current installation before ready/install. No upstream presence reports or inherited default Listen Together server. New invitations use `aria://party`; retain legacy internal media URI formats, persisted keys and historical credits until their compatibility contracts are explicitly migrated. New downloads use `Music/Aria` with legacy directory discovery retained; no automatic file moves. New synchronized lyrics use `ARIA_LYRICS` with legacy reading preserved. This is not a proof that all inherited service integrations have been removed; audit remaining endpoints, Discord identity and legacy protocol paths separately.

## Repository operation and historical evidence

The live integration and delivery tools target only `jssantogit/Aria`. The existing protected-main ruleset ID remains 25365768; its active status and canonical source were verified on GitHub before this migration. Owner approval, exact-main pinning, the dedicated token, the explicit write opt-in, and fail-closed checks remain unchanged. Historical manifests and evidence using `jssantogit/BitChord` are not rewritten or silently accepted as current execution authority. New batches must be reviewed against current Aria paths, repository identity and exact SHAs. Existing concurrency group names are retained to avoid splitting active integration lanes. No integration workflow was dispatched to perform writes during this migration.

## Emulator and physical acceptance

The native workflow now installs its own exact-HEAD x86_64 development APK in a clean API 34 Android emulator and checks the installed package, default launcher alias, activity launch and process survival. Its screenshot is evidence for human inspection, not automatic visual approval. Production package/signing, installation alongside an existing legacy app, backup restore, all launcher variants, physical audio, and device-specific rendering remain separate acceptance checks. Do not uninstall the legacy app or move existing files to perform these checks.
