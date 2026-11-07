package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkerContext
import kotlin.uuid.Uuid

internal class IosWorkerContext(
    override val id: Uuid,
    override val inputData: WorkData,
    override val tags: Set<String>,
    private val stateStore: IosWorkStateStore,
    private val runAttemptCountProvider: () -> Int = { 0 },
) : WorkerContext {
    // getter 跟随复用 Worker 的重试游标，不能在构造时冻结为首次执行次数。
    override val runAttemptCount: Int
        get() = runAttemptCountProvider()

    override suspend fun setProgress(progress: WorkProgress) {
        stateStore.updateProgress(id, progress)
    }
}
