package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ohos.internal.storage.PeriodicScheduleSpec
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val MIN_REPEAT_MILLIS = 7_200_000L

internal fun WorkRequest.initialDelay(): Duration = when (this) {
    is OneTimeWorkRequest -> initialDelay
    is PeriodicWorkRequest -> initialDelay
}

internal fun WorkRequest.requireValidOhosTiming() {
    val delay = initialDelay()
    if (!delay.isFinite() ||
        delay < Duration.ZERO
    ) {
        throw WorkEnqueueException("Initial delay must be finite and non-negative.")
    }
    val retry = retryConfig
    if (retry.maxAttempts < 1 ||
        !retry.initialDelay.isFinite() || retry.initialDelay < Duration.ZERO ||
        !retry.maxDelay.isFinite() || retry.maxDelay < Duration.ZERO ||
        !retry.multiplier.isFinite() || retry.multiplier < 0.0
    ) {
        throw WorkEnqueueException("Invalid retry configuration.")
    }
}

internal fun RetryConfig.ohosBackoffDelay(retryIndex: Int): Duration {
    if (initialDelay == Duration.ZERO) return Duration.ZERO
    val computed = when (backoffPolicy) {
        BackoffPolicy.EXPONENTIAL -> initialDelay * multiplier.pow(retryIndex)
        BackoffPolicy.LINEAR -> initialDelay * (retryIndex + 1)
    }
    return minOf(computed, maxDelay)
}

internal fun PeriodicWorkRequest.toOhosPeriodicTiming(
    bundleName: String,
    abilityName: String,
    nowMillis: Long,
): SqliteManagedWorkStore.PeriodicTiming {
    requireValidOhosTiming()
    if (quickRefresh || flexTimeInterval != Duration.ZERO) {
        throw WorkEnqueueException("Quick refresh and flex windows are unavailable in OHOS WorkScheduler.")
    }
    val intervalMillis = repeatInterval.inWholeMilliseconds
    if (intervalMillis !in MIN_REPEAT_MILLIS..Int.MAX_VALUE.toLong() || intervalMillis.milliseconds != repeatInterval) {
        throw WorkEnqueueException(
            "OHOS repeat interval must be at least two hours and fit exactly in signed Int32 milliseconds.",
        )
    }
    if (constraints.requiresCharging || constraints.requiresDeviceIdle || constraints.requiresBatteryNotLow ||
        constraints.requiresStorageNotLow || constraints.contentUriTriggers.isNotEmpty()
    ) {
        throw WorkEnqueueException("Only the connected network constraint is implemented for OHOS periodic work.")
    }
    val requiresNetwork =
        when (constraints.requiredNetworkType) {
            NetworkType.NOT_REQUIRED -> false
            NetworkType.CONNECTED -> true
            else -> throw WorkEnqueueException("This network constraint is unavailable in OHOS WorkScheduler.")
        }
    val wholeDelay = initialDelay.inWholeMilliseconds
    val remainingFraction = if (wholeDelay.milliseconds < initialDelay) 1L else 0L
    require(nowMillis >= 0L && wholeDelay <= Long.MAX_VALUE - nowMillis - remainingFraction) {
        "OHOS initial delay exceeds the supported eligibility timestamp"
    }
    return SqliteManagedWorkStore.PeriodicTiming(
        PeriodicScheduleSpec(bundleName, abilityName, id, intervalMillis.toInt(), requiresNetwork),
        nowMillis + wholeDelay + remainingFraction,
    )
}

internal fun WorkData.hasSameValues(other: WorkData): Boolean {
    val left = keyValueMap()
    val right = other.keyValueMap()
    return left.keys == right.keys && left.all { (key, value) ->
        val candidate = right[key]
        when (value) {
            is Array<*> -> candidate is Array<*> && value.contentDeepEquals(candidate)
            is BooleanArray -> candidate is BooleanArray && value.contentEquals(candidate)
            is ByteArray -> candidate is ByteArray && value.contentEquals(candidate)
            is IntArray -> candidate is IntArray && value.contentEquals(candidate)
            is LongArray -> candidate is LongArray && value.contentEquals(candidate)
            is FloatArray -> candidate is FloatArray && value.contentEquals(candidate)
            is DoubleArray -> candidate is DoubleArray && value.contentEquals(candidate)
            else -> value == candidate
        }
    }
}
