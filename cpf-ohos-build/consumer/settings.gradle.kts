pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
}

val cpfProperties =
    java.util.Properties().apply {
        file("../../cpf-build/gradle.properties").inputStream().use { load(it) }
    }
val workerVersion =
    providers
        .gradleProperty("workerOhosVersion")
        .orElse(providers.gradleProperty("cpf.version"))
        .orElse(cpfProperties.getProperty("cpf.version"))
        .get()
val workerRepository = providers.gradleProperty("workerOhosRepository").get()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url =
                        if (workerRepository.startsWith(
                                "https://",
                            )
                        ) {
                            uri(workerRepository)
                        } else {
                            uri(file(workerRepository))
                        }
                    metadataSources { gradleMetadata() }
                }
            }
            filter {
                listOf("", "-ohosx64", "-ohosarm64").forEach {
                    includeVersion("com.lihb.forks.worker", "worker-kmp$it", workerVersion)
                }
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://qsxs.github.io/cpf-maven/") {
                    metadataSources { gradleMetadata() }
                }
            }
            filter {
                listOf("sqlite", "sqlite-async", "sqlite-framework", "sqlite-bundled").forEach { artifact ->
                    listOf("", "-ohosx64", "-ohosarm64").forEach { suffix ->
                        includeVersion("androidx.sqlite", artifact + suffix, "2.7.1-chunjizhang-cpf11-p2r1")
                    }
                }
            }
        }
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

rootProject.name = "worker-kmp-ohos-consumer"
