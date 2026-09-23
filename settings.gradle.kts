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

rootProject.name = "RemoteServices"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":core:security")
include(":core:network")
include(":core:update")
include(":core:logging")
include(":feature:services")
include(":feature:web")
include(":feature:update")
include(":feature:settings")
include(":adapter:luci")
