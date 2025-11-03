pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        flatDir {
            dirs(
                File(rootDir, "commonlib/libs"),
                File(rootDir, "sdk/libs"),
                File(rootDir, "emv/libs"),
                File(rootDir, "app/libs")
            )
        }
    }
}

rootProject.name = "avoqado-tpv"
include(":app")
include(":commonlib")
include(":emv")
include(":sdk")
