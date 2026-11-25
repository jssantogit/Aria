# Android identity migration

Aria production applicationId: `com.music.aria`; development: `com.dev.aria`; benchmark suffix remains `.benchmark`.

This is a different Android installation from the legacy package. Private sandbox data and Android Keystore entries do not automatically move. Do not uninstall the old installation before verifying an exported backup and explicitly reconfiguring any accounts or protected credentials. The existing JSON backup covers listening statistics and settings, not a complete app/database/credential transfer. Legacy backup tags remain readable, and new exports use the Aria tag.

Kotlin/Android namespace `com.music.bitchord` is temporarily retained so class names, JNI, tests, R and manifest references continue to resolve. Its comprehensive migration is a separate mechanical change with an audit of JNI, reflection, serialization, scripts and resources. Do not confuse it with the installed applicationId.

Updater: own GitHub repository only, Aria APK name prefix, archive package must match current installation before ready/install. No upstream presence reports or inherited default Listen Together server. This is not a proof that all inherited service integrations have been removed; audit remaining endpoints, Discord identity and legacy protocol paths separately.
