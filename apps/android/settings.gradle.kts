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

rootProject.name = "hermes-mama"

include(":app")
include(":core-contract")
include(":core-gateway")
include(":core-controller")
include(":core-ui")
include(":feature-chat")
include(":feature-browser")
include(":feature-voice")
include(":feature-settings")
include(":testing")
