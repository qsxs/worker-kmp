pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val workerRepository = providers.gradleProperty("workerIosRepository").get()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(workerRepository)
                    metadataSources { gradleMetadata() }
                }
            }
            filter { includeGroup("com.lihb.forks.worker") }
        }
        mavenCentral()
    }
}

rootProject.name = "worker-kmp-ios-consumer"
