import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    kotlin("multiplatform") version "2.4.20"
}

val workerVersion = providers.gradleProperty("workerIosVersion").get()

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WorkerKmpConsumer"
            isStatic = true
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation("com.lihb.forks.worker:worker-ios:$workerVersion")
        }
    }
}

tasks.register("verifyWorkerIosVariants") {
    doLast {
        val evidence =
            layout.buildDirectory
                .file("worker-ios-variants.txt")
                .get()
                .asFile
        evidence.parentFile.mkdirs()
        evidence.bufferedWriter().use { writer ->
            mapOf(
                "iosArm64CompileKlibraries" to "iosarm64",
                "iosSimulatorArm64CompileKlibraries" to "iossimulatorarm64",
            ).forEach { (configurationName, target) ->
                val artifacts =
                    configurations
                        .getByName(configurationName)
                        .incoming.artifacts.artifacts
                val modules = artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                val workerModules = modules.filter { it.group == "com.lihb.forks.worker" }
                check(
                    workerModules.map { it.module }.toSet() == setOf("worker-kmp-$target", "worker-ios-$target"),
                ) {
                    "Unexpected Worker KMP iOS modules: $workerModules"
                }
                check(workerModules.all { it.version == workerVersion }) { "Mixed Worker KMP versions" }
                writer.appendLine(configurationName)
                artifacts.forEach { artifact ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                    val sha256 = HexFormat.of().formatHex(digest)
                    writer.appendLine("${artifact.id.componentIdentifier}\t$sha256\t${artifact.file.name}")
                }
                println("$configurationName -> ${workerModules.joinToString()}")
            }
        }
    }
}
