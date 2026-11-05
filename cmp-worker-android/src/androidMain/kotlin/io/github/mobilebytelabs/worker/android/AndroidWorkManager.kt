package io.github.mobilebytelabs.worker.android

import android.content.Context
import android.os.Build
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import co.touchlab.kermit.Logger
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import androidx.work.WorkManager as AndroidWM

class AndroidWorkManager(context: Context) : WorkManager {

    private val wm = AndroidWM.getInstance(context)

    override suspend fun enqueue(request: io.github.mobilebytelabs.worker.OneTimeWorkRequest): Uuid {
        wm.enqueue(request.toAndroidOneTime()).result.await()
        return request.id
    }

    override suspend fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: io.github.mobilebytelabs.worker.PeriodicWorkRequest,
    ): Uuid {
        wm.enqueueUniquePeriodicWork(
            uniqueWorkName,
            existingPeriodicWorkPolicy.toAndroid(),
            request.toAndroidPeriodic(),
        ).result.await()
        return request.id
    }

    // cross-platform-worker-parity-audit sub-plan 03 (closes G2)
    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: io.github.mobilebytelabs.worker.OneTimeWorkRequest,
    ): Uuid {
        wm.enqueueUniqueWork(
            uniqueWorkName,
            existingWorkPolicy.toAndroid(),
            request.toAndroidOneTime(),
        ).result.await()
        return request.id
    }

    override suspend fun cancelWorkById(id: Uuid) {
        wm.cancelWorkById(UUID.fromString(id.toString())).result.await()
    }

    override suspend fun cancelAllWorkByTag(tag: String) {
        wm.cancelAllWorkByTag(tag).result.await()
    }

    override fun getWorkInfosByTag(tag: String): Flow<List<WorkInfo>> =
        wm.getWorkInfosByTagFlow(tag).map { list -> list.map { it.toKmp() } }

    // worker-kmp-single-api-completion sub-plan 01 — delegates to androidx.work native API.
    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
        wm.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .map { list -> list.map { it.toKmp() } }

    override suspend fun getWorkInfoById(id: Uuid): WorkInfo? =
        wm.getWorkInfoById(UUID.fromString(id.toString())).await()?.toKmp()

    // ── Private builders ──────────────────────────────────────────────────────

    private fun io.github.mobilebytelabs.worker.OneTimeWorkRequest.toAndroidOneTime(): OneTimeWorkRequest {
        val data = buildInputData(workerClass, id, inputData, tags)
        val builder = OneTimeWorkRequestBuilder<KmpAndroidWorker>()
            .setId(UUID.fromString(id.toString()))
            .setInputData(data)
            .setConstraints(constraints.toAndroid())
            .setBackoffCriteria(
                retryConfig.backoffPolicy.toAndroid(),
                retryConfig.initialDelay.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
            .addTag(id.toTag())
            .apply { tags.forEach { addTag(it) } }
        if (initialDelay.inWholeMilliseconds > 0) {
            builder.setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        expeditedPolicy?.let { policy ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(policy.toAndroid())
            } else {
                Logger.withTag("worker-kmp.android").d {
                    "setExpedited() requested but SDK_INT=${Build.VERSION.SDK_INT} < 31 (Android 12). " +
                        "Request will run as ordinary background work."
                }
            }
        }
        return builder.build()
    }

    private fun io.github.mobilebytelabs.worker.PeriodicWorkRequest.toAndroidPeriodic(): PeriodicWorkRequest {
        val data = buildInputData(workerClass, id, inputData, tags)
        val intervalMs = repeatInterval.inWholeMilliseconds
            .coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        val builder = PeriodicWorkRequestBuilder<KmpAndroidWorker>(intervalMs, TimeUnit.MILLISECONDS)
            .setId(UUID.fromString(id.toString()))
            .setInputData(data)
            .setConstraints(constraints.toAndroid())
            .addTag(id.toTag())
            .apply { tags.forEach { addTag(it) } }
        if (initialDelay.inWholeMilliseconds > 0) {
            builder.setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        // quickRefresh is iOS-only — no Android mapping (logged once if set).
        if (quickRefresh) {
            Logger.withTag("worker-kmp.android").d {
                "setQuickRefresh(true) requested but is iOS-only — ignored on Android."
            }
        }
        return builder.build()
    }

    private fun buildInputData(
        workerClass: String,
        id: Uuid,
        inputData: io.github.mobilebytelabs.worker.WorkData,
        tags: Set<String>,
    ): androidx.work.Data {
        require(!inputData.hasKey(KEY_KMP_CLASS) && !inputData.hasKey(KEY_KMP_ID)) {
            "Input data contains reserved key $KEY_KMP_CLASS or $KEY_KMP_ID"
        }
        require(tags.none { it.startsWith(TAG_KMP_ID_PREFIX) }) { "Tags contain reserved prefix $TAG_KMP_ID_PREFIX" }
        // 原生 Builder 对包含元数据的最终载荷一次性执行类型与大小校验，失败时尚未入队。
        return androidx.work.Data.Builder()
            .putAll(inputData.keyValueMap())
            .putString(KEY_KMP_CLASS, workerClass)
            .putString(KEY_KMP_ID, id.toString())
            .build()
    }
}
