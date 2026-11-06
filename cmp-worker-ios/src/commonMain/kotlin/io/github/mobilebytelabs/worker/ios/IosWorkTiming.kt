package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkRequest
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal fun iosInitialDueAtEpochMillis(now: Long, request: WorkRequest): Long = iosDueAtEpochMillis(
    now,
    when (request) {
        is OneTimeWorkRequest -> request.initialDelay
        is PeriodicWorkRequest -> request.initialDelay
    },
)

internal fun iosDueAtEpochMillis(now: Long, delay: Duration): Long {
    val millis = delay.inWholeMilliseconds.coerceAtLeast(0L)
    return if (now > Long.MAX_VALUE - millis) Long.MAX_VALUE else now + millis
}

internal fun iosRemainingDelayMillis(now: Long, dueAt: Long?): Long {
    if (dueAt == null || dueAt <= now) return 0L
    return if (now < 0L && dueAt > Long.MAX_VALUE + now) Long.MAX_VALUE else dueAt - now
}

internal fun iosBackoffDelay(config: RetryConfig, attempt: Int): Duration {
    val initial = config.initialDelay.inWholeMilliseconds.coerceAtLeast(0L)
    val retries = attempt.coerceAtLeast(0)
    val millis = when (config.backoffPolicy) {
        BackoffPolicy.EXPONENTIAL -> (initial * config.multiplier.pow(retries)).toLong()

        BackoffPolicy.LINEAR -> {
            val factor = retries.toLong() + 1L
            if (initial > Long.MAX_VALUE / factor) Long.MAX_VALUE else initial * factor
        }
    }.coerceIn(0L, config.maxDelay.inWholeMilliseconds.coerceAtLeast(0L))
    return millis.milliseconds
}
