# Android-only product scope

Desktop, TV, Wear and the legacy web application/extension source trees are removed from the current Android identity branch; the previous tracked source and attribution remain available in Git history. Android Gradle uses app, shared, sharedUi. The JVM test hosts of shared modules are deliberately retained. The phone-side wearable transport, installer, settings, playback hooks and dependencies have been removed. Android Auto, Cast, normal Bluetooth/media routing and the Go backend used by Android remain supported. The structural identity gate rejects the return of the retired product roots and Wear contracts. The retired-backend audit remains scoped to active Android and shared sources. No historical upstream attribution or license notice is altered in retained code.

The Android emulator is a validation environment, not another supported product platform.
