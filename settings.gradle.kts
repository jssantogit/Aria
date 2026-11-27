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
        // Maven Central's standard endpoint sometimes rate-limits shared CI
        // runners (HTTP 429) for JavaFX, ONNX and native desktop artifacts.
        // Prefer its Google-hosted mirror, retaining Maven Central as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
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
