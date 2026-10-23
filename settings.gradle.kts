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

rootProject.name = "LunaCam"

include(
    ":app",
    ":camera-camera2",
    ":domain",
    ":feature-camera",
    ":feature-diagnostics",
    ":processing-api",
    ":processing-native"
)
