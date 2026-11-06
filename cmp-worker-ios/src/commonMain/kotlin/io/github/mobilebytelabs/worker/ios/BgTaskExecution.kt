package io.github.mobilebytelabs.worker.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

@OptIn(ExperimentalCoroutinesApi::class)
internal fun launchBgTask(
    scope: CoroutineScope,
    installExpirationHandler: (() -> Unit) -> Unit,
    complete: (Boolean) -> Unit,
    onTask: suspend CoroutineScope.() -> Boolean,
) {
    val job = scope.async(start = CoroutineStart.LAZY, block = onTask)
    installExpirationHandler { job.cancel() }
    // 仅在 Job 及所属子任务结束后回执，过期处理只请求取消。
    job.invokeOnCompletion { cause ->
        complete(cause == null && job.getCompleted())
    }
    job.start()
}
