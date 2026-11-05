pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// 默认只包含 core 与 Android；iOS 必须显式启用，不引入上游根工程或 build-logic。
rootProject.name = "worker-kmp"

include(":android")

if (providers.gradleProperty("cpf.enableIos").orNull == "true") {
    include(":ios")
}
