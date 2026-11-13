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

// 每个平台单独生产，iOS 入口不配置 Android 工具链。
rootProject.name = "worker-kmp"

if (providers.gradleProperty("cpf.enableIos").orNull == "true") {
    include(":ios")
} else {
    include(":android")
}
