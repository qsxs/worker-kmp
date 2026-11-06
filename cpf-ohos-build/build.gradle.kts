import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

plugins {
    kotlin("multiplatform") version "2.4.20-0.1.0-11"
    `maven-publish`
}

val cpfKotlinVersion = "2.4.20-0.1.0-11"
val publicationGroup = "com.lihb.forks.worker"
val publicationVersion = "4.0.8-cpf.3-ohos.2"
val publicationArtifacts =
    mapOf(
        "kotlinMultiplatform" to "worker-kmp",
        "ohosX64" to "worker-kmp-ohosx64",
        "ohosArm64" to "worker-kmp-ohosarm64",
    )
val repositoryDirectory =
    layout
        .dir(providers.gradleProperty("cpf.ohos.repository").map { file(it) })
        .orElse(layout.buildDirectory.dir("repository"))

group = publicationGroup
version = publicationVersion

val upstreamSources = layout.projectDirectory.dir("../cmp-worker-kmp/src")

kotlin {
    ohosX64()
    ohosArm64()

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain by getting {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("commonMain/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("commonMain/resources")))
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-0.1.0-07")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.1.0-07")
                api("co.touchlab:kermit:2.0.8-1.1.0")
            }
        }

        val ohosMain by creating {
            dependsOn(commonMain)
            kotlin.setSrcDirs(listOf(upstreamSources.dir("ohosMain/kotlin")))
        }
        getByName("ohosX64Main").dependsOn(ohosMain)
        getByName("ohosArm64Main").dependsOn(ohosMain)
    }
}

configurations.configureEach {
    val nativeOrMetadata =
        name.endsWith("CompileKlibraries") ||
            name.endsWith("CompilationDependenciesMetadata") ||
            name.endsWith("ResolvableDependenciesMetadata") ||
            name == "allSourceSetsCompileDependenciesMetadata" ||
            name == "metadataCompileClasspath"
    if (nativeOrMetadata) {
        resolutionStrategy.eachDependency {
            // Kermit 的制品请求旧版 stdlib，鸿蒙编译统一选择当前 CPF 工具链版本。
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-stdlib") {
                useVersion(cpfKotlinVersion)
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = publicationArtifacts.getValue(name)
        pom {
            name.set("worker-kmp (CPF OHOS core fork)")
            description.set("Private OHOS core build of worker-kmp for x64 and arm64")
            url.set("https://github.com/MobileByteLabs/worker-kmp")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
        }
    }
    repositories {
        maven {
            name = "Cpf"
            url = repositoryDirectory.get().asFile.toURI()
        }
    }
}

val checkCpfOhosRepository =
    tasks.register("checkCpfOhosRepository") {
        doLast {
            val repositoryPath = repositoryDirectory.get().asFile.toPath()
            val groupDirectory = repositoryPath.resolve(publicationGroup.replace('.', '/'))
            publicationArtifacts.values.forEach { artifact ->
                val candidate = groupDirectory.resolve(artifact).resolve(publicationVersion)
                check(!Files.exists(candidate)) { "CPF OHOS candidate already exists: $candidate" }
            }
            // 按版本原子占用仓库，防止两个发布进程各自写入一部分制品。
            Files.createDirectories(groupDirectory)
            val claim = groupDirectory.resolve(".cpf-ohos-$publicationVersion.publish")
            try {
                Files.createFile(claim)
            } catch (failure: FileAlreadyExistsException) {
                throw GradleException(
                    "CPF OHOS candidate $publicationVersion already claimed in $repositoryPath. " +
                        "Use -Pcpf.ohos.repository with a new local directory.",
                    failure,
                )
            }
        }
    }

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(checkCpfOhosRepository)
}

tasks.withType<PublishToMavenLocal>().configureEach {
    enabled = false
}
