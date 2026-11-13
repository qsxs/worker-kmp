package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.uuid.Uuid

/** 一次性请求的生存租约从发布前持有到结束，进程退出后的请求只结算，不重放。 */
internal class OhosOneTimeOwnerRunner(
    private val applicationScope: CoroutineScope,
    private val registry: WorkerRegistry,
    private val store: SqliteManagedWorkStore,
    directory: Path,
) {
    private val lifetimes = OhosExecutionLease(directory, "one-time-leases")
    private val names = nameExecutionLeases(directory)

    fun validate(request: OneTimeWorkRequest) {
        request.requireValidOhosTiming()
        if (request.workerClass !in registry.registeredClassNames()) {
            throw WorkEnqueueException("No worker registered for '${request.workerClass}'.")
        }
        if (request.constraints != Constraints.NONE || request.expeditedPolicy != null) {
            throw WorkEnqueueException("Constraints and expedited execution are unavailable for in-process OHOS work.")
        }
    }

    /** 等待租约交接及登记事务，不等待名字租约或执行。 */
    suspend fun publish(
        request: OneTimeWorkRequest,
        commit: suspend () -> SqliteManagedWorkStore.Publication,
    ): SqliteManagedWorkStore.Publication {
        val acquired = CompletableDeferred<Boolean>()
        val assigned = CompletableDeferred<SqliteManagedWorkStore.Record?>()
        applicationScope
            .launch {
                try {
                    val owned =
                        lifetimes.withLock(request.id, 0L) {
                            acquired.complete(true)
                            runAssigned(assigned)
                        }
                    if (owned == null) acquired.complete(false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    acquired.completeExceptionally(failure)
                }
            }.invokeOnCompletion { failure ->
                if (failure != null) acquired.completeExceptionally(failure)
            }

        try {
            val ownsLifetime = acquired.await()
            if (!ownsLifetime) {
                checkNotNull(store.read(request.id)) { "One-time lease exists before request publication" }
            }
            return withContext(NonCancellable) {
                commit().also { publication ->
                    check(ownsLifetime || !publication.created) { "A new one-time request requires a lifetime lease" }
                    assigned.complete(publication.record.takeIf { publication.created })
                }
            }
        } finally {
            assigned.complete(null)
        }
    }

    suspend fun reapAbandoned(record: SqliteManagedWorkStore.Record?) {
        if (record == null || record.request !is OneTimeWorkRequest ||
            (record.info.isFinished && record.owner == null)
        ) {
            return
        }
        lifetimes.withLock(record.intent.id, 0L) {
            store.settleUnfinishedOneTime(record.intent, WorkInfo.State.FAILED)
        }
    }

    private suspend fun runAssigned(assigned: CompletableDeferred<SqliteManagedWorkStore.Record?>) {
        val ownerContext = currentCoroutineContext()
        var record: SqliteManagedWorkStore.Record? = null
        try {
            // 即使宿主取消，也要接收已经提交的确切意图。
            withContext(NonCancellable) { record = assigned.await() }
            record?.let { runOwner(it) }
        } finally {
            record?.let {
                val state = if (ownerContext.isActive) WorkInfo.State.FAILED else WorkInfo.State.CANCELLED
                withContext(NonCancellable) { store.settleUnfinishedOneTime(it.intent, state) }
            }
        }
    }

    private suspend fun runOwner(record: SqliteManagedWorkStore.Record) {
        val leaseId = record.intent.nameLeaseId
        if (leaseId == null) {
            runClaimed(record)
        } else {
            names.withLockUntil(
                leaseId,
                0L,
                shouldWait = { store.read(record.intent.id)?.info?.isFinished == false },
            ) { runClaimed(record) }
        }
    }

    private suspend fun runClaimed(record: SqliteManagedWorkStore.Record) {
        val caller = currentCoroutineContext()
        val execution =
            withContext(NonCancellable) { store.claimOneTime(record.intent, Uuid.random()) }
                ?: return
        var terminal: Pair<WorkInfo.State, WorkData?>? = null
        try {
            caller.ensureActive()
            terminal = execute(record.request as OneTimeWorkRequest, execution)
        } finally {
            // 用户 finally 与子协程全部退出后才结算和释放名字租约。
            withContext(NonCancellable) {
                val state = terminal?.first ?: if (caller.isActive) WorkInfo.State.FAILED else WorkInfo.State.CANCELLED
                store.finishOneTime(execution, state, terminal?.second)
            }
        }
    }

    private suspend fun execute(
        request: OneTimeWorkRequest,
        execution: SqliteManagedWorkStore.Execution,
    ): Pair<WorkInfo.State, WorkData?> = coroutineScope {
        val monitor = launch {
            store.awaitCancellation(execution)
            this@coroutineScope.cancel(CancellationException("One-time work cancellation was requested"))
        }
        try {
            executeAttempts(request, execution)
        } finally {
            monitor.cancel()
        }
    }

    private suspend fun executeAttempts(
        request: OneTimeWorkRequest,
        execution: SqliteManagedWorkStore.Execution,
    ): Pair<WorkInfo.State, WorkData?> {
        delay(request.initialDelay)
        for (attempt in 0 until request.retryConfig.maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (!store.reportOneTimeAttempt(execution, WorkInfo.State.RUNNING, attempt + 1)) {
                throw CancellationException("One-time work intent is no longer current")
            }
            val result =
                try {
                    coroutineScope {
                        runRegisteredWorker(
                            registry,
                            request,
                            OhosStoredWorkerContext(store, execution, attempt, currentCoroutineContext().job),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    WorkResult.Failure(failure.message ?: "Worker execution failed.")
                }
            currentCoroutineContext().ensureActive()
            when (result) {
                is WorkResult.Success -> return WorkInfo.State.SUCCEEDED to result.outputData

                is WorkResult.Failure -> return WorkInfo.State.FAILED to result.ohosFailureOutput()

                is WorkResult.Retry -> {
                    if (attempt + 1 >= request.retryConfig.maxAttempts) {
                        return WorkInfo.State.FAILED to WorkResult.Failure(result.reason).ohosFailureOutput()
                    }
                    if (!store.reportOneTimeAttempt(execution, WorkInfo.State.ENQUEUED, attempt + 1)) {
                        throw CancellationException("One-time work intent is no longer current")
                    }
                    delay(request.retryConfig.ohosBackoffDelay(attempt))
                }
            }
        }
        error("One-time work exhausted its validated retry budget")
    }
}
