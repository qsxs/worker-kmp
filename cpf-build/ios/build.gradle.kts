import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

    applyDefaultHierarchyTemplate()
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("commonMain/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("commonMain/resources")))
            dependencies {
                api(project(":"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kermit)
            }
        }
        named("iosMain") {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("iosMain/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("iosMain/resources")))
            // 与应用现有 wrapper 相同，调度制品排除独立的 Compose/Koin 示例入口。
            kotlin.exclude("**/WorkerKmpMainViewController.kt")
        }
        commonTest {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("commonTest/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("commonTest/resources")))
            kotlin.exclude("**/WorkerKmpMainViewControllerTest.kt")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        named("iosTest") {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("iosTest/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("iosTest/resources")))
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
            description.set("Worker KMP iOS scheduling backend")
            url.set("https://github.com/qsxs/worker-kmp")
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

tasks.withType<Jar>().matching { it.name.contains("sources", ignoreCase = true) }.configureEach {
    from(rootProject.layout.projectDirectory.file("../LICENSE"))
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
