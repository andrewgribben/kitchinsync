pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // dav4jvm is only published on JitPack
        maven("https://jitpack.io") {
            content { includeGroup("com.github.bitfireAT") }
        }
    }
}

rootProject.name = "kitchinsync"
include(":app")
include(":synctools")
project(":synctools").projectDir = file("third_party/synctools")
