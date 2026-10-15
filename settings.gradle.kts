/** Google Maven — только для Android-артефактов (всё остальное берём из Maven Central). */
fun RepositoryHandler.googleAndroidOnly() = google {
    content {
        includeGroupByRegex("androidx\\..*")
        includeGroupByRegex("com\\.android(\\..*)?")
        includeGroupByRegex("com\\.google\\.android\\..*")
        includeGroupByRegex("com\\.google\\.testing\\.platform.*")
    }
}

pluginManagement {
    repositories {
        // Google Maven — только для Android-артефактов (всё остальное из Maven Central).
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleAndroidOnly()
        mavenCentral()
    }
}

rootProject.name = "BodroMusic"

include(":core:model")
include(":core:subsonic")
include(":core:domain")

// `-Pbodro.jvmOnly=true` собирает только чистые JVM-модули (без Android SDK и Google Maven).
// Используется в CI-окружениях без доступа к Android SDK.
val jvmOnly = providers.gradleProperty("bodro.jvmOnly").orNull == "true"
if (jvmOnly) {
    rootProject.buildFileName = "build-jvm.gradle.kts"
} else {
    include(":app")
}
