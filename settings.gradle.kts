pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Aria"

// Aria is Android-only. Keep the shared modules and their JVM test hosts;
// they exercise production Android code without shipping a desktop app.
include(":shared")
include(":sharedUi")
include(":app")
