package io.github.mobilebytelabs.worker

@ExperimentalForegroundApi
public actual suspend fun runAsForeground(worker: ForegroundWorker, info: ForegroundInfo): Unit =
    throw ForegroundNotSupportedException(
        platform = "ohos",
        reason = "No foreground execution integration is configured for OHOS.",
    )
