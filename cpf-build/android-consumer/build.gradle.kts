import java.util.Properties

plugins {
    id("com.android.application") version "9.1.0"
    // 与宿主一样把 KGP 2.4.20 放到插件类路径，Android 使用 AGP 内置 Kotlin。
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

val cpfProperties =
    Properties().apply {
        file("../gradle.properties").inputStream().use { load(it) }
    }
val cpfVersion = providers.gradleProperty("cpf.version").orElse(cpfProperties.getProperty("cpf.version")).get()

android {
    namespace = "com.lihb.forks.worker.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lihb.forks.worker.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // AGP 内置 Kotlin 随 Java compileOptions 使用相同 JVM 目标。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 唯一私有依赖；core 和启动器 ABI 必须由 Android publication 正确传递。
    implementation("com.lihb.forks.worker:worker-android:$cpfVersion")
}
