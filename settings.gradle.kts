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
    }
}

rootProject.name = "party-buttons"

include(":core")
include(":server")

// Die App braucht das Android-SDK. Ohne SDK (z. B. in claude-box) lassen sich core und die Tests trotzdem bauen.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Kein Android-SDK gefunden, Modul :app wird übersprungen")
}
