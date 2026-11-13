package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class OhosStoredWorkerContext(
    private val store: SqliteManagedWorkStore,
    private val execution: SqliteManagedWorkStore.Execution,
    override val runAttemptCount: Int,
    private val executionJob: Job,
) : WorkerContext {
    override val id = execution.intent.id
    override val inputData = execution.request.inputData
    override val tags: Set<String>
        get() = execution.request.tags.toSet()

    override suspend fun setProgress(progress: WorkProgress) {
        if (executionJob.isActive) store.setProgress(execution, progress) { executionJob.isActive }
    }
}

internal suspend fun runRegisteredWorker(
    registry: WorkerRegistry,
    request: WorkRequest,
    context: WorkerContext,
): WorkResult {
    val worker = registry.create(request.workerClass, context)
        ?: error("No worker registered for '${request.workerClass}'.")
    return worker.doWork()
}

internal fun WorkResult.Failure.ohosFailureOutput(): WorkData = when {
    outputData != WorkData.EMPTY -> outputData
    message.isNotEmpty() -> workDataOf("error" to message)
    else -> WorkData.EMPTY
}

internal suspend fun <T> settleAfterFailure(primary: Throwable, settle: suspend () -> T): T? = try {
    withContext(NonCancellable) { settle() }
} catch (failure: Throwable) {
    if (failure !== primary) primary.addSuppressed(failure)
    null
}
