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
        // commons 和 IndicatorFastScroll 发在 JitPack 上
        maven { setUrl("https://www.jitpack.io") }
    }
}

rootProject.name = "EvelorionContacts"
include(":app")
