pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kepan-clock-android"
include(":domain-core")
include(":render-core")
include(":wallpaper")
include(":app")
