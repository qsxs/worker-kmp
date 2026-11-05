package io.github.mobilebytelabs.worker.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mobilebytelabs.worker.ExperimentalForegroundApi
import io.github.mobilebytelabs.worker.ForegroundNotSupportedException
import io.github.mobilebytelabs.worker.ForegroundWorker
import io.github.mobilebytelabs.worker.WorkResult
import kotlin.uuid.Uuid
import androidx.work.ForegroundInfo as AndroidForegroundInfo
import io.github.mobilebytelabs.worker.CoroutineWorker as KmpCoroutineWorker

/**
 * Android WorkManager worker that bridges to a KMP [io.github.mobilebytelabs.worker.CoroutineWorker].
 *
 * Reads the KMP worker class name from input data, instantiates it via the registered
 * [KmpWorkerFactory], and delegates [doWork] to it.
 *
 * Registers itself with [AndroidForegroundBridge] for the duration of the user's
 * `doWork()` so that KMP `ForegroundWorker.setForeground(info)` calls are routed
 * to `androidx.work.CoroutineWorker.setForegroundAsync(...)` instead of the
 * SystemTray-based Desktop actual.
 */
@OptIn(ExperimentalForegroundApi::class)
class KmpAndroidWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val kmpFactory: KmpAndroidWorkerFactory?,
) : CoroutineWorker(appContext, params) {

    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, KmpWorkerFactory.instance)

    private val delegate: Delegate? by lazy {
        val workerClass = inputData.getString(KEY_KMP_CLASS) ?: return@lazy null
        val factory = kmpFactory ?: return@lazy null
        // UPDATE 会替换输入与标签，执行身份始终来自 WorkManager 保留的原生 ID。
        val nativeId = Uuid.parse(id.toString())
        val userInputData = inputData.toKmpInputData()
        val userTags = tags.userTags()
        val context = AndroidWorkerContext(this, nativeId, userInputData, userTags)
        Delegate(context, factory.create(workerClass, context))
    }

    override suspend fun getForegroundInfo(): AndroidForegroundInfo {
        val worker = delegate?.worker as? ForegroundWorker ?: throw ForegroundNotSupportedException(
            platform = "Android",
            reason = "An initialized ForegroundWorker is required to provide an initial foreground notification.",
        )
        return AndroidForegroundBridge.toAndroidForegroundInfo(
            applicationContext,
            worker.id,
            worker.getForegroundInfo(),
        )
    }

    override suspend fun doWork(): Result {
        val delegate = delegate ?: return Result.failure()
        val nativeId = delegate.context.id
        AndroidForegroundBridge.register(nativeId, this)
        return try {
            when (val result = delegate.worker.doWork()) {
                is WorkResult.Success -> Result.success(result.outputData.toAndroid())

                is WorkResult.Failure -> Result.failure(
                    if (result.outputData.keyValueMap().isEmpty()) {
                        androidx.work.workDataOf("error" to result.message)
                    } else {
                        result.outputData.toAndroid()
                    },
                )

                is WorkResult.Retry -> Result.retry()
            }
        } finally {
            AndroidForegroundBridge.unregister(nativeId, this)
        }
    }

    private class Delegate(val context: AndroidWorkerContext, val worker: KmpCoroutineWorker)
}
