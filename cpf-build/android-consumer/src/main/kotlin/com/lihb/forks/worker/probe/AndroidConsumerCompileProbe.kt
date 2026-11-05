@file:OptIn(
    io.github.mobilebytelabs.worker.ExperimentalForegroundApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.lihb.forks.worker.probe

import android.content.Context
import io.github.mobilebytelabs.worker.ForegroundInfo
import io.github.mobilebytelabs.worker.ForegroundWorker
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.WorkManagerFactory
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.android.AndroidForegroundBridge
import io.github.mobilebytelabs.worker.android.AndroidWorkManager
import io.github.mobilebytelabs.worker.android.WorkerKmpComposeActivity
import io.github.mobilebytelabs.worker.android.WorkerKmpStarterApplication
import io.github.mobilebytelabs.worker.android.androidWorkManagerFactory
import kotlinx.coroutines.flow.Flow
import org.koin.core.module.Module
import java.util.concurrent.CompletableFuture
import kotlin.uuid.Uuid

// 只进行类型检查；不构造 WorkManager、不调用 factory，也不触发初始化或调度。
internal object AndroidConsumerCompileProbe {
    val workManagerType: Class<AndroidWorkManager> = AndroidWorkManager::class.java
    val factoryReference: (Context) -> WorkManagerFactory = ::androidWorkManagerFactory

    // 仅通过方法引用检查新 API 的类型，不调用接口或创建实例。
    val runAttemptCountReference: (WorkerContext) -> Int = WorkerContext::runAttemptCount
    val workInfoByIdFlowReference: (AndroidWorkManager, Uuid) -> Flow<WorkInfo?> =
        AndroidWorkManager::getWorkInfoByIdFlow
    val cancelUniqueWorkReference: suspend (AndroidWorkManager, String) -> Unit =
        AndroidWorkManager::cancelUniqueWork

    // 仅检查前台提升的 Future 返回类型，不调用 promote。
    fun promotionReference(): (ForegroundWorker, ForegroundInfo) -> CompletableFuture<Void> =
        AndroidForegroundBridge::promote

    fun coreContract(manager: AndroidWorkManager): WorkManager = manager

    fun coreData(data: WorkData): WorkData = data

    // SAM 的四个参数同时核验通知 hook 对 AndroidX、UUID 和 core 的传递 ABI。
    fun notificationCustomizer(): AndroidForegroundBridge.NotificationCustomizer =
        AndroidForegroundBridge.NotificationCustomizer { _, _, _, builder ->
            builder.setOnlyAlertOnce(true)
        }
}

// 不在 Manifest 中注册，编译其父类与构造器以核验 Compose 启动器 ABI。
internal class CompileProbeActivity : WorkerKmpComposeActivity({})

// 不在 Manifest 中注册，Koin Module 只能通过私有 Android 依赖传递获得。
internal class CompileProbeApplication : WorkerKmpStarterApplication() {
    override fun koinModules(): List<Module> = emptyList()
}
