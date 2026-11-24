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
                File(rootDir, "app/libs")
            )
        }
    }
}

rootProject.name = "avoqado-tpv"
include(":app")
include(":sdk")
include(":commonlib")
include(":emv")
include(":campo")
include(":cobropropio")
include(":motorlab") // app de laboratorio del motor de cobro (bloque 4-L): sólo la PAX del laboratorio, nunca un negocio
