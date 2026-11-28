// Maven Central through Google's mirror first: Central itself answers CI runners with 429 (Too Many
// Requests) on busy days, and Gradle does not move on to the next repository after one.
pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral() }
}
rootProject.name = "KK_ShuRuFa"
include(":app")
