pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        mavenLocal()
        maven {url = uri("https://xxx.xxx.xxx/releases")}
        maven {url = uri("https://xxx.xxx.xxx/snapshots")}
    }
}

rootProject.name = "AndroidModuleBuildingAccelerator"
include(":feature:payments")
include(":feature:home")
include(":infra:network")
include(":infra:analytics")
include(":ui:proton")
include(":app")

enableFeaturePreview("VERSION_CATALOGS")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")