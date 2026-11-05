import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = providers.gradleProperty("cpf.group").get()
version = providers.gradleProperty("cpf.version").get()

require(group.toString() != "io.github.mobilebytelabs") {
    "CPF publications must use a private group."
}

val upstreamSources = layout.projectDirectory.dir("../cmp-worker-kmp/src")

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    // 使用 JDK 21 运行工具链，产物保持上游 JVM 11 字节码目标。
    jvmToolchain(21)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("commonMain/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("commonMain/resources")))
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.kermit)
            }
        }
        jvmMain {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("jvmMain/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("jvmMain/resources")))
        }
        commonTest {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("commonTest/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("commonTest/resources")))
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest {
            kotlin.setSrcDirs(listOf(upstreamSources.dir("jvmTest/kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("jvmTest/resources")))
        }
    }
}

// JVM Java 任务与 Kotlin 保持相同目标，不采用 JDK 21 的默认字节码级别。
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.release.set(11)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            when (name) {
                "kotlinMultiplatform" -> "worker-kmp"
                "jvm" -> "worker-kmp-jvm"
                else -> error("Unsupported CPF publication: $name")
            }

        pom {
            name.set("worker-kmp (CPF core fork)")
            description.set("Private core fork of worker-kmp: common metadata and JVM target")
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
                layout.buildDirectory
                    .dir("repository")
                    .get()
                    .asFile
                    .toURI()
        }
    }
}

// 私有产物只写入本入口文件仓库，避免进入用户共享的 Maven Local。
tasks.withType<PublishToMavenLocal>().configureEach {
    enabled = false
}
