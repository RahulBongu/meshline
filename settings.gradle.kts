// Meshline - settings
// NOTE: No INTERNET permission is declared anywhere. Dependency repositories are
// build-time only (Gradle resolves them on the dev machine); they do not grant
// the app any runtime network access.
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
rootProject.name = "Meshline"
include(":app")
