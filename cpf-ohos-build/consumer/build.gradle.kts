import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("multiplatform") version "2.4.21-0.1.0-13"
}

val workerVersion = providers.gradleProperty("workerOhosVersion").get()
val nativeVersion = "2.4.21-0.1.0-13"

kotlin {
    listOf(ohosX64(), ohosArm64()).forEach { target ->
        target.binaries.sharedLib { baseName = "worker_ohos_consumer" }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("com.lihb.forks.worker:worker-kmp:$workerVersion")
        }
        val ohosMain by creating { dependsOn(commonMain.get()) }
        getByName("ohosX64Main").dependsOn(ohosMain)
        getByName("ohosArm64Main").dependsOn(ohosMain)
    }
}

configurations.configureEach {
    val nativeOrMetadata =
        name.endsWith("CompileKlibraries") ||
            name.endsWith("CompilationDependenciesMetadata") ||
            name.endsWith("ResolvableDependenciesMetadata") ||
            name == "allSourceSetsCompileDependenciesMetadata" || name == "metadataCompileClasspath"
    if (nativeOrMetadata) {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-stdlib") {
                useVersion(nativeVersion)
            }
        }
    }
}

tasks.register("verifyWorkerVariants") {
    doLast {
        mapOf("ohosX64CompileKlibraries" to "ohosx64", "ohosArm64CompileKlibraries" to "ohosarm64")
            .forEach { (configuration, suffix) ->
                val artifacts =
                    configurations
                        .getByName(configuration)
                        .incoming.artifacts.artifacts
                val klibs = artifacts.filter { it.file.extension == "klib" }
                val selected = klibs.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                val worker = selected.singleOrNull { it.group == "com.lihb.forks.worker" }
                check(worker?.module == "worker-kmp-$suffix" && worker.version == workerVersion) {
                    "Wrong Worker KMP variant for $configuration: $worker"
                }
                check(selected.any { it.group == "androidx.sqlite" && it.module == "sqlite-bundled-$suffix" }) {
                    "Worker KMP did not expose the SQLite runtime for $suffix"
                }
                check(selected.any { it.group == "com.squareup.okio" && it.module == "okio-$suffix" }) {
                    "Worker KMP did not expose the Okio runtime for $suffix"
                }
                println("$configuration -> com.lihb.forks.worker:worker-kmp-$suffix:$workerVersion")
            }
    }
}
