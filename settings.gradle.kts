rootProject.name = "ChessTree"

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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":androidApp")
include(":composeApp")
include(":gameDomain")
include(":botWire")
include(":botWorker")
include(":onlineContract")
include(":server")
