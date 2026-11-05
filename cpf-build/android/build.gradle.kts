import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

val upstreamSources = rootProject.layout.projectDirectory.dir("../cmp-worker-android/src/androidMain")

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(21)
    android {
        namespace = "io.github.mobilebytelabs.worker.android"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        androidMain {
            // 挂接全部上游 Android 源码，保留启动器和通知定制接口。
            kotlin.setSrcDirs(listOf(upstreamSources.dir("kotlin")))
            resources.setSrcDirs(listOf(upstreamSources.dir("resources")))
            dependencies {
                api(project(":"))
                implementation(libs.kotlinx.coroutines.android)
                // 这些依赖的类型出现在公开类、父类或方法签名中，消费端需可见。
                api(libs.androidx.work.runtime.ktx)
                api(libs.compose.runtime)
                api(libs.androidx.activity.compose)
                api(libs.koin.android)
            }
        }
    }
}

// AGP 公共 Variant API 直接读取原始 Manifest，不复制或生成替代文件。
extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.manifests.addStaticManifestFile(
            upstreamSources.file("AndroidManifest.xml").asFile.absolutePath,
        )
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            when (name) {
                "kotlinMultiplatform" -> "worker-android"
                "android" -> "worker-android-android"
                else -> error("Unsupported CPF Android publication: $name")
            }

        pom {
            name.set("worker-android (CPF Android fork)")
            description.set("Private Android platform fork of worker-kmp with complete upstream launchers")
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
