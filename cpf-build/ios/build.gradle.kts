import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

val upstreamSources = rootProject.layout.projectDirectory.dir("../cmp-worker-ios/src")

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WorkerKmpIos"
            isStatic = true
        }
    }

    // 本模块只有 iOS 目标，合并物理目录，避免相同目标集合的重复中间源集。
    applyHierarchyTemplate {
        common { withIos() }
    }
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(
                listOf(upstreamSources.dir("commonMain/kotlin"), upstreamSources.dir("iosMain/kotlin")),
            )
            resources.setSrcDirs(
                listOf(upstreamSources.dir("commonMain/resources"), upstreamSources.dir("iosMain/resources")),
            )
            dependencies {
                api(project(":"))
                implementation(libs.kotlinx.coroutines.core)
                // 启动器的公开签名包含 Composable 与 Koin Module。
                api(compose.runtime)
                api(compose.ui)
                api(libs.koin.core)
            }
        }
        commonTest {
            kotlin.setSrcDirs(
                listOf(upstreamSources.dir("commonTest/kotlin"), upstreamSources.dir("iosTest/kotlin")),
            )
            resources.setSrcDirs(
                listOf(upstreamSources.dir("commonTest/resources"), upstreamSources.dir("iosTest/resources")),
            )
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.koin.test)
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            when (name) {
                "kotlinMultiplatform" -> "worker-ios"
                "iosArm64" -> "worker-ios-iosarm64"
                "iosSimulatorArm64" -> "worker-ios-iossimulatorarm64"
                else -> error("Unsupported CPF iOS publication: $name")
            }
        pom {
            name.set("worker-ios (CPF iOS fork)")
            description.set("Private iOS platform fork of worker-kmp with complete upstream launcher")
            url.set("https://github.com/MobileByteLabs/worker-kmp")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("MobileByteLabs")
                    name.set("MobileByteLabs")
                    url.set("https://github.com/MobileByteLabs")
                }
            }
            scm {
                url.set("https://github.com/MobileByteLabs/worker-kmp/")
                connection.set("scm:git:git://github.com/MobileByteLabs/worker-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com/MobileByteLabs/worker-kmp.git")
            }
        }
    }
    repositories {
        maven {
            name = "Cpf"
            url =
                rootProject.layout.buildDirectory
                    .dir("repository")
                    .get()
                    .asFile
                    .toURI()
        }
    }
}

tasks.withType<PublishToMavenLocal>().configureEach {
    enabled = false
}

val repositoryDirectory =
    rootProject.layout.buildDirectory
        .dir("repository")
        .get()
        .asFile
val modulePath = group.toString().replace('.', '/')
val publicationVersion = version.toString()
tasks.matching { it.name == "publishKotlinMultiplatformPublicationToCpfRepository" }.configureEach {
    dependsOn("publishIosArm64PublicationToCpfRepository", "publishIosSimulatorArm64PublicationToCpfRepository")
    doFirst {
        listOf("iosarm64", "iossimulatorarm64").forEach { target ->
            val artifact = "worker-ios-$target"
            listOf("klib", "pom", "module").forEach { extension ->
                val leaf =
                    repositoryDirectory.resolve(
                        "$modulePath/$artifact/$publicationVersion/$artifact-$publicationVersion.$extension",
                    )
                check(leaf.isFile && leaf.length() > 0) { "Missing CPF Native leaf: $leaf" }
            }
        }
    }
}
