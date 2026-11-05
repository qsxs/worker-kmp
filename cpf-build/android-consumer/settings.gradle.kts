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
        // 私有坐标只能从 producer 文件仓库解析，缺失时不得回退到远端。
        exclusiveContent {
            forRepository {
                maven {
                    name = "Cpf"
                    url = uri("../build/repository")
                }
            }
            filter {
                includeGroup("com.lihb.forks.worker")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "worker-android-consumer-probe"
