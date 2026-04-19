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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rouse-context"

include(":app")
include(":core:tunnel")
include(":core:mcp")
include(":core:bridge")
include(":core:testfixtures")
include(":api")
include(":notifications")
include(":work")
include(":integrations")
include(":device-tests")
include(":e2e")
