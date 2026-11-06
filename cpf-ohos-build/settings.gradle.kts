pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.eazytec-cloud.com/nexus/repository/maven-inner/") }
            filter {
                listOf("kermit", "kermit-core").forEach { artifact ->
                    listOf("", "-ohosx64", "-ohosarm64").forEach { suffix ->
                        includeVersion("co.touchlab", artifact + suffix, "2.0.8-1.1.0")
                    }
                }
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "worker-kmp"
