import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = providers.gradleProperty("cpf.group").get()

val enableIos = providers.gradleProperty("cpf.enableIos").orNull == "true"

// iOS 预览使用独立版本，不能覆盖已验收的默认 JVM / Android 产物。
version = if (enableIos) "4.0.8-cpf.3-ios-preview.1" else providers.gradleProperty("cpf.version").get()

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

    if (enableIos) {
        iosArm64()
        iosSimulatorArm64()
        applyHierarchyTemplate {
            common {
                withJvm()
                group("ios") { withIos() }
            }
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
        if (enableIos) {
            named("iosMain") {
                kotlin.setSrcDirs(listOf(upstreamSources.dir("iosMain/kotlin")))
                resources.setSrcDirs(listOf(upstreamSources.dir("iosMain/resources")))
            }
            named("iosTest") {
                kotlin.setSrcDirs(listOf(upstreamSources.dir("iosTest/kotlin")))
                resources.setSrcDirs(listOf(upstreamSources.dir("iosTest/resources")))
            }
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
            dependencies { implementation(libs.kotlinx.serialization.json) }
        }
    }
}

// Windows 上直接测试 iOS 的公共状态和后台任务源码，避免把它们编进 JVM 主制品。
val iosSources = layout.projectDirectory.dir("../cmp-worker-ios/src")
val iosSourcePackage = "kotlin/io/github/mobilebytelabs/worker/ios"
tasks.named<KotlinCompileTool>("compileTestKotlinJvm") {
    source(
        iosSources.file("commonMain/$iosSourcePackage/IosWorkStateStore.kt"),
        iosSources.file("commonMain/$iosSourcePackage/IosWorkPersistence.kt"),
        iosSources.file("commonMain/$iosSourcePackage/IosWorkRecordCodec.kt"),
        iosSources.file("commonMain/$iosSourcePackage/IosWorkTiming.kt"),
        iosSources.file("commonMain/$iosSourcePackage/IosPendingWorkRunner.kt"),
        iosSources.file("commonMain/$iosSourcePackage/BgTaskExecution.kt"),
        iosSources.file("commonMain/$iosSourcePackage/IosOwnedWorkExecution.kt"),
        iosSources.file("commonTest/$iosSourcePackage/InMemoryIosWorkPersistence.kt"),
        iosSources.file("commonTest/$iosSourcePackage/IosWorkStateStoreTest.kt"),
        iosSources.file("commonTest/$iosSourcePackage/IosWorkRecordCodecTest.kt"),
        iosSources.file("commonTest/$iosSourcePackage/IosPendingWorkRunnerTest.kt"),
        iosSources.file("commonTest/$iosSourcePackage/BgTaskExecutionTest.kt"),
        iosSources.file("commonTest/$iosSourcePackage/IosOwnedWorkExecutionTest.kt"),
    )
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
                "iosArm64" -> "worker-kmp-iosarm64"
                "iosSimulatorArm64" -> "worker-kmp-iossimulatorarm64"
                else -> error("Unsupported CPF publication: $name")
            }

        pom {
            name.set("worker-kmp (CPF core fork)")
            description.set("Private core fork of worker-kmp with JVM and optional iOS targets")
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

if (enableIos) {
    val verifyIosPublicationHost =
        tasks.register("verifyCpfIosPublicationHost") {
            group = "publishing"
            description = "Rejects iOS preview publication on hosts without Apple target support."
            doLast {
                check(System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                    "CPF iOS preview publication requires macOS and complete Native artifacts."
                }
            }
        }
    allprojects {
        plugins.withId("maven-publish") {
            tasks.withType<PublishToMavenRepository>().configureEach {
                dependsOn(verifyIosPublicationHost)
                if (project != rootProject) {
                    dependsOn(":publishKotlinMultiplatformPublicationToCpfRepository")
                }
            }
        }
    }

    val repositoryDirectory =
        layout.buildDirectory
            .dir("repository")
            .get()
            .asFile
    val modulePath = group.toString().replace('.', '/')
    val publicationVersion = version.toString()
    tasks.matching { it.name == "publishKotlinMultiplatformPublicationToCpfRepository" }.configureEach {
        dependsOn(
            "publishJvmPublicationToCpfRepository",
            "publishIosArm64PublicationToCpfRepository",
            "publishIosSimulatorArm64PublicationToCpfRepository",
        )
        doFirst {
            // 即使调用方排除了叶子任务，也不能发布指向缺失 Native 制品的根 metadata。
            listOf("iosarm64", "iossimulatorarm64").forEach { target ->
                val artifact = "worker-kmp-$target"
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
}
