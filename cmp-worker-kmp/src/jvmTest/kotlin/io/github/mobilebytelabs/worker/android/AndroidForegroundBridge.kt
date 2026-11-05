@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalForegroundApi::class)

package io.github.mobilebytelabs.worker.android

import io.github.mobilebytelabs.worker.ForegroundInfo
import io.github.mobilebytelabs.worker.ForegroundWorker
import java.util.concurrent.CompletableFuture

// 同 FQCN 的反射目标只存在于 jvmTest，不能进入生产制品。
object AndroidForegroundBridge {
    var action: (ForegroundWorker, ForegroundInfo) -> Any? = { _, _ -> CompletableFuture<Void>() }

    @JvmStatic
    fun promote(worker: ForegroundWorker, info: ForegroundInfo): Any? = action(worker, info)

    fun reset() {
        action = { _, _ -> CompletableFuture<Void>() }
    }
}
