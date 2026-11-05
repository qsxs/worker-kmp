package io.github.mobilebytelabs.worker.android

import androidx.work.Data
import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import kotlin.uuid.Uuid
import androidx.work.BackoffPolicy as AndroidBackoffPolicy
import androidx.work.Constraints as AndroidConstraints
import androidx.work.ExistingPeriodicWorkPolicy as AndroidExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy as AndroidExistingWorkPolicy
import androidx.work.NetworkType as AndroidNetworkType
import androidx.work.OutOfQuotaPolicy as AndroidOutOfQuotaPolicy
import androidx.work.WorkInfo as AndroidWorkInfo

internal const val KEY_KMP_ID = "kmp_worker_id"
internal const val KEY_KMP_CLASS = "kmp_worker_class"
internal const val KEY_KMP_PROGRESS = "kmp_worker_progress_percent"
internal const val TAG_KMP_ID_PREFIX = "kmp_id:"

internal fun Uuid.toTag(): String = "$TAG_KMP_ID_PREFIX$this"

internal fun Set<String>.extractKmpId(): Uuid? = firstOrNull { it.startsWith(TAG_KMP_ID_PREFIX) }
    ?.removePrefix(TAG_KMP_ID_PREFIX)
    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

internal fun Set<String>.userTags(): Set<String> = filterNot { it.startsWith(TAG_KMP_ID_PREFIX) }.toSet()

internal fun BackoffPolicy.toAndroid(): AndroidBackoffPolicy = when (this) {
    BackoffPolicy.EXPONENTIAL -> AndroidBackoffPolicy.EXPONENTIAL
    BackoffPolicy.LINEAR -> AndroidBackoffPolicy.LINEAR
}

internal fun NetworkType.toAndroid(): AndroidNetworkType = when (this) {
    NetworkType.NOT_REQUIRED -> AndroidNetworkType.NOT_REQUIRED
    NetworkType.CONNECTED -> AndroidNetworkType.CONNECTED
    NetworkType.UNMETERED -> AndroidNetworkType.UNMETERED
    NetworkType.NOT_ROAMING -> AndroidNetworkType.NOT_ROAMING
    NetworkType.METERED -> AndroidNetworkType.METERED
}

internal fun Constraints.toAndroid(): AndroidConstraints {
    val builder = AndroidConstraints.Builder()
        .setRequiredNetworkType(requiredNetworkType.toAndroid())
        .setRequiresCharging(requiresCharging)
        .setRequiresDeviceIdle(requiresDeviceIdle)
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .setRequiresStorageNotLow(requiresStorageNotLow)
    contentUriTriggers.forEach { trigger ->
        builder.addContentUriTrigger(
            android.net.Uri.parse(trigger.uriString),
            trigger.triggerForDescendants,
        )
    }
    return builder.build()
}

internal fun OutOfQuotaPolicy.toAndroid(): AndroidOutOfQuotaPolicy = when (this) {
    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST -> AndroidOutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
    OutOfQuotaPolicy.DROP_WORK_REQUEST -> AndroidOutOfQuotaPolicy.DROP_WORK_REQUEST
}

internal fun ExistingPeriodicWorkPolicy.toAndroid(): AndroidExistingPeriodicWorkPolicy = when (this) {
    ExistingPeriodicWorkPolicy.KEEP -> AndroidExistingPeriodicWorkPolicy.KEEP
    ExistingPeriodicWorkPolicy.REPLACE -> AndroidExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
    ExistingPeriodicWorkPolicy.UPDATE -> AndroidExistingPeriodicWorkPolicy.UPDATE
}

// cross-platform-worker-parity-audit sub-plan 03 (closes G2) — convert commonMain's
// ExistingWorkPolicy to AndroidX's.
internal fun ExistingWorkPolicy.toAndroid(): AndroidExistingWorkPolicy = when (this) {
    ExistingWorkPolicy.REPLACE -> AndroidExistingWorkPolicy.REPLACE
    ExistingWorkPolicy.KEEP -> AndroidExistingWorkPolicy.KEEP
    ExistingWorkPolicy.APPEND -> AndroidExistingWorkPolicy.APPEND
    ExistingWorkPolicy.APPEND_OR_REPLACE -> AndroidExistingWorkPolicy.APPEND_OR_REPLACE
}

internal fun AndroidWorkInfo.State.toKmp(): WorkInfo.State = when (this) {
    AndroidWorkInfo.State.ENQUEUED -> WorkInfo.State.ENQUEUED
    AndroidWorkInfo.State.RUNNING -> WorkInfo.State.RUNNING
    AndroidWorkInfo.State.SUCCEEDED -> WorkInfo.State.SUCCEEDED
    AndroidWorkInfo.State.FAILED -> WorkInfo.State.FAILED
    AndroidWorkInfo.State.BLOCKED -> WorkInfo.State.BLOCKED
    AndroidWorkInfo.State.CANCELLED -> WorkInfo.State.CANCELLED
}

internal fun AndroidWorkInfo.toKmp(): WorkInfo = WorkInfo(
    id = Uuid.parse(id.toString()),
    state = state.toKmp(),
    progress = WorkProgress(
        progress = progress.getInt(KEY_KMP_PROGRESS, 0),
        data = WorkData(*progress.keyValueMap.filterKeys { it != KEY_KMP_PROGRESS }.map { it.toPair() }.toTypedArray()),
    ),
    outputData = outputData.toKmp(),
    tags = tags.userTags(),
    runAttemptCount = runAttemptCount,
)

internal fun Data.toKmp(): WorkData = WorkData(*keyValueMap.map { it.toPair() }.toTypedArray())

// 仅执行输入剥离身份元数据；输出和业务进度保留同名字段。
internal fun Data.toKmpInputData(): WorkData = WorkData(
    *keyValueMap.filterKeys { it != KEY_KMP_CLASS && it != KEY_KMP_ID }.map { it.toPair() }.toTypedArray(),
)

internal fun WorkData.toAndroid(): Data = Data.Builder().putAll(keyValueMap()).build()
