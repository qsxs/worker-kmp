package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicCallback
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicExecutionResult
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicExecutionTicket
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** 同名任务执行到所有子协程退出，才结算并释放跨进程租约。 */
internal class OhosPeriodicExecutor(directory: Path, private val store: SqliteManagedWorkStore) {
    private val names = nameExecutionLeases(directory)

    suspend fun run(
        callback: OhosPeriodicCallback,
        registry: WorkerRegistry,
        onReserved: suspend (OhosPeriodicExecutionTicket) -> Unit,
    ): OhosPeriodicExecutionResult {
        val intent = store.periodicIntent(callback) ?: return OhosPeriodicExecutionResult.REJECTED
        return names.withLock(checkNotNull(intent.nameLeaseId), 0L) {
            val caller = currentCoroutineContext()
            caller.ensureActive()
            val claim = withContext(NonCancellable) {
                store.claimPeriodic(callback, intent, Uuid.random(), Clock.System.now().toEpochMilliseconds())
            }
            val execution =
                when (claim) {
                    is SqliteManagedWorkStore.PeriodicClaim.Started -> claim.execution
                    is SqliteManagedWorkStore.PeriodicClaim.Recovered -> return@withLock claim.state.toExecutionResult()
                    null -> return@withLock OhosPeriodicExecutionResult.REJECTED
                }

            try {
                withContext(NonCancellable) {
                    onReserved(OhosPeriodicExecutionTicket(callback, checkNotNull(execution.occurrenceId)))
                }
                caller.ensureActive()
                val result = execute(execution, registry)
                withContext(NonCancellable) {
                    caller.ensureActive()
                    val state: WorkInfo.State?
                    val output: WorkData?
                    var retryAt: Long? = null
                    when (result) {
                        is WorkResult.Success -> {
                            state = WorkInfo.State.SUCCEEDED
                            output = result.outputData
                        }

                        is WorkResult.Failure -> {
                            state = WorkInfo.State.FAILED
                            output = result.ohosFailureOutput()
                        }

                        is WorkResult.Retry -> {
                            if (execution.attempt + 1 >= execution.request.retryConfig.maxAttempts) {
                                state = WorkInfo.State.FAILED
                                output = WorkResult.Failure(result.reason).ohosFailureOutput()
                            } else {
                                state = null
                                output = null
                                val delay = execution.request.retryConfig.ohosBackoffDelay(execution.attempt)
                                val now = Clock.System.now().toEpochMilliseconds()
                                val whole = delay.inWholeMilliseconds
                                val rounded =
                                    whole + if (whole.milliseconds < delay && whole < Long.MAX_VALUE) 1L else 0L
                                retryAt = if (rounded > Long.MAX_VALUE - now) Long.MAX_VALUE else now + rounded
                            }
                        }
                    }
                    checkNotNull(store.finishPeriodic(execution, state, output, retryAt)).state.toExecutionResult()
                }
            } catch (cancelled: CancellationException) {
                val stopped =
                    settleAfterFailure(cancelled) {
                        val requested = !store.canRun(execution)
                        store.finishPeriodic(execution, WorkInfo.State.CANCELLED, output = null)
                        requested
                    }
                if (caller.isActive && stopped == true) OhosPeriodicExecutionResult.CANCELLED else throw cancelled
            } catch (failure: Exception) {
                settleAfterFailure(failure) { store.finishPeriodic(execution, WorkInfo.State.FAILED, output = null) }
                throw failure
            }
        } ?: OhosPeriodicExecutionResult.BUSY
    }

    private suspend fun execute(execution: SqliteManagedWorkStore.Execution, registry: WorkerRegistry): WorkResult =
        coroutineScope {
            if (!store.canRun(execution)) throw CancellationException("Periodic work intent is no longer current")
            val monitor = launch {
                store.awaitCancellation(execution)
                this@coroutineScope.cancel(CancellationException("Periodic work cancellation was requested"))
            }
            try {
                coroutineScope {
                    runRegisteredWorker(
                        registry,
                        execution.request,
                        OhosStoredWorkerContext(store, execution, execution.attempt, currentCoroutineContext().job),
                    )
                }
            } finally {
                monitor.cancel()
            }
        }
}

private fun WorkInfo.State?.toExecutionResult(): OhosPeriodicExecutionResult = when (this) {
    null -> OhosPeriodicExecutionResult.RETRY
    WorkInfo.State.SUCCEEDED -> OhosPeriodicExecutionResult.SUCCEEDED
    WorkInfo.State.FAILED -> OhosPeriodicExecutionResult.FAILED
    WorkInfo.State.CANCELLED -> OhosPeriodicExecutionResult.CANCELLED
    else -> error("OHOS periodic execution did not settle")
}
