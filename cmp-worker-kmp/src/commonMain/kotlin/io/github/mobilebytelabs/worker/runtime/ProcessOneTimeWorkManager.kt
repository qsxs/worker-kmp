package io.github.mobilebytelabs.worker.runtime

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkInfo.State
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.uuid.Uuid

internal class ProcessOneTimeWorkManager(private val scope: CoroutineScope, private val registry: WorkerRegistry) :
    WorkManager {
    private val enqueueMutex = Mutex()
    private val records = MutableStateFlow<Map<Uuid, Record>>(emptyMap())

    init {
        registry.lock()
    }

    override suspend fun enqueue(request: OneTimeWorkRequest): Uuid = enqueueOneTime(request, null)

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Uuid {
        if (existingWorkPolicy != ExistingWorkPolicy.KEEP) {
            throw WorkEnqueueException("In-process OHOS work only supports KEEP for unique work.")
        }
        if (uniqueWorkName.isBlank()) throw WorkEnqueueException("Unique work name must not be blank.")
        return enqueueOneTime(request, uniqueWorkName)
    }

    override suspend fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ): Uuid = throw WorkEnqueueException("Periodic work is unavailable for in-process OHOS work.")

    private suspend fun enqueueOneTime(request: OneTimeWorkRequest, uniqueName: String?): Uuid {
        request.validateForProcessExecution()
        val (id, newJob) = enqueueMutex.withLock {
            val snapshot = records.value
            snapshot[request.id]?.let { existing ->
                if (existing.uniqueName != uniqueName || existing.job == null) {
                    throw WorkEnqueueException("Work ID ${request.id} is already registered.")
                }
                return@withLock request.id to null
            }
            if (uniqueName != null) {
                val owner = snapshot.values.firstOrNull { it.uniqueName == uniqueName && it.job != null }
                if (owner != null) return@withLock owner.info.id to null
            }
            if (!scope.isActive) throw WorkEnqueueException("The OHOS work scope is not active.")

            val job = scope.launch(start = CoroutineStart.LAZY) {
                execute(request, coroutineContext.job)
            }
            records.update { snapshot ->
                snapshot +
                    (request.id to Record(WorkInfo(request.id, State.ENQUEUED, tags = request.tags), uniqueName, job))
            }
            // 完成回调也处理启动前的取消，并在子任务和清理都结束后归还唯一名称。
            job.invokeOnCompletion { cause -> release(request.id, job, cause) }
            request.id to job
        }
        newJob?.start()
        return id
    }

    override suspend fun cancelWorkById(id: Uuid) {
        cancelMatching { it.info.id == id }
    }

    suspend fun cancelUniqueWork(uniqueWorkName: String) {
        cancelMatching { it.uniqueName == uniqueWorkName }
    }

    override suspend fun cancelAllWorkByTag(tag: String) {
        cancelMatching { tag in it.info.tags }
    }

    private fun cancelMatching(matches: (Record) -> Boolean) {
        val previous = records.getAndUpdate { snapshot ->
            snapshot.mapValues { (_, record) ->
                if (!record.info.isFinished && matches(record)) {
                    record.copy(info = record.info.copy(state = State.CANCELLED))
                } else {
                    record
                }
            }
        }
        previous.values.forEach { record ->
            if (!record.info.isFinished && matches(record)) record.job?.cancel()
        }
    }

    override suspend fun getWorkInfoById(id: Uuid): WorkInfo? = records.value[id]?.info

    fun getWorkInfoByIdFlow(id: Uuid): Flow<WorkInfo?> = records.map { it[id]?.info }.distinctUntilChanged()

    override fun getWorkInfosByTag(tag: String): Flow<List<WorkInfo>> = observeMatching { tag in it.info.tags }

    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
        observeMatching { it.uniqueName == uniqueWorkName }

    private fun observeMatching(matches: (Record) -> Boolean): Flow<List<WorkInfo>> =
        records.map { snapshot -> snapshot.values.filter(matches).map(Record::info) }.distinctUntilChanged()

    private suspend fun execute(request: OneTimeWorkRequest, owner: Job) {
        delay(request.initialDelay)
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val attemptCount = attempt + 1
            val started = updateInfo(request.id, owner, { it.state == State.ENQUEUED }) {
                it.copy(state = State.RUNNING, runAttemptCount = attemptCount)
            }
            if (!started) return

            val context = RequestContext(request, owner, attempt)
            val result = runAttempt(request, context)
            currentCoroutineContext().ensureActive()
            when (result) {
                is WorkResult.Success -> {
                    finishAttempt(request.id, owner, attemptCount, State.SUCCEEDED, result.outputData)
                    return
                }

                is WorkResult.Failure -> {
                    finishAttempt(request.id, owner, attemptCount, State.FAILED, result.failureOutput())
                    return
                }

                is WorkResult.Retry -> {
                    if (attemptCount >= request.retryConfig.maxAttempts) {
                        finishAttempt(
                            request.id,
                            owner,
                            attemptCount,
                            State.FAILED,
                            WorkResult.Failure(result.reason).failureOutput(),
                        )
                        return
                    }
                    if (!updateRunning(request.id, owner, attemptCount) { it.copy(state = State.ENQUEUED) }) return
                    delay(request.retryConfig.backoffDelay(attempt))
                    attempt++
                }
            }
        }
    }

    private suspend fun runAttempt(request: OneTimeWorkRequest, context: WorkerContext): WorkResult = try {
        coroutineScope {
            val worker = registry.create(request.workerClass, context)
                ?: error("No worker registered for '${request.workerClass}'.")
            worker.doWork()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        WorkResult.Failure(failure.message ?: "Worker execution failed.")
    }

    private fun finishAttempt(id: Uuid, owner: Job, attemptCount: Int, state: State, output: WorkData) {
        updateRunning(id, owner, attemptCount) { it.copy(state = state, outputData = output) }
    }

    private fun updateRunning(id: Uuid, owner: Job, attemptCount: Int, transform: (WorkInfo) -> WorkInfo): Boolean =
        updateInfo(id, owner, { it.state == State.RUNNING && it.runAttemptCount == attemptCount }, transform)

    private fun updateInfo(
        id: Uuid,
        owner: Job,
        matches: (WorkInfo) -> Boolean,
        transform: (WorkInfo) -> WorkInfo,
    ): Boolean {
        val previous = records.getAndUpdate { snapshot ->
            val record = snapshot[id]
            if (record != null && record.job === owner && matches(record.info)) {
                snapshot + (id to record.copy(info = transform(record.info)))
            } else {
                snapshot
            }
        }[id]
        return previous != null && previous.job === owner && matches(previous.info)
    }

    private fun release(id: Uuid, owner: Job, cause: Throwable?) {
        records.update { snapshot ->
            val record = snapshot[id]
            if (record == null || record.job !== owner) return@update snapshot
            val finalInfo = if (record.info.isFinished) {
                record.info
            } else {
                val terminal = if (cause is CancellationException) State.CANCELLED else State.FAILED
                record.info.copy(state = terminal)
            }
            snapshot + (id to record.copy(info = finalInfo, job = null))
        }
    }

    private inner class RequestContext(
        request: OneTimeWorkRequest,
        private val owner: Job,
        override val runAttemptCount: Int,
    ) : WorkerContext {
        override val id = request.id
        override val inputData = request.inputData
        override val tags = request.tags

        override suspend fun setProgress(progress: WorkProgress) {
            updateRunning(id, owner, runAttemptCount + 1) {
                // 清理时当前上下文可能不可取消，仍需检查原任务的 Job。
                if (owner.isActive) it.copy(progress = progress) else it
            }
        }
    }

    private data class Record(val info: WorkInfo, val uniqueName: String?, val job: Job?)
}

private fun OneTimeWorkRequest.validateForProcessExecution() {
    if (constraints != Constraints.NONE || expeditedPolicy != null) {
        throw WorkEnqueueException("Constraints and expedited execution are unavailable for in-process OHOS work.")
    }
    if (!initialDelay.isFinite() || initialDelay < Duration.ZERO) {
        throw WorkEnqueueException("Initial delay must be finite and non-negative.")
    }
    val retry = retryConfig
    if (retry.maxAttempts < 1 ||
        !retry.initialDelay.isFinite() || retry.initialDelay < Duration.ZERO ||
        !retry.maxDelay.isFinite() || retry.maxDelay < Duration.ZERO ||
        (retry.backoffPolicy == BackoffPolicy.EXPONENTIAL && (!retry.multiplier.isFinite() || retry.multiplier < 0.0))
    ) {
        throw WorkEnqueueException("Invalid retry configuration.")
    }
}

private fun RetryConfig.backoffDelay(retryIndex: Int): Duration {
    if (initialDelay == Duration.ZERO) return Duration.ZERO
    val computed = when (backoffPolicy) {
        BackoffPolicy.EXPONENTIAL -> initialDelay * multiplier.pow(retryIndex)
        BackoffPolicy.LINEAR -> initialDelay * (retryIndex + 1)
    }
    return minOf(computed, maxDelay)
}

private fun WorkResult.Failure.failureOutput(): WorkData = when {
    outputData != WorkData.EMPTY -> outputData
    message.isNotEmpty() -> workDataOf("error" to message)
    else -> WorkData.EMPTY
}
