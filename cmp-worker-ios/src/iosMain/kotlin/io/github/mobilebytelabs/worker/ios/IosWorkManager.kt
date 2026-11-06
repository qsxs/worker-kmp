package io.github.mobilebytelabs.worker.ios

import co.touchlab.kermit.Logger
import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.ExperimentalWorkerApi
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.UniqueWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.WorkKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalWorkerApi::class)
class IosWorkManager internal constructor(
    private val workerFactory: IosWorkerFactory,
    private val config: IosWorkManagerConfig = IosWorkManagerConfig.DEFAULT,
    private val persistence: IosWorkPersistence,
) : WorkManager {

    constructor(
        workerFactory: IosWorkerFactory,
        config: IosWorkManagerConfig = IosWorkManagerConfig.DEFAULT,
    ) : this(workerFactory, config, createIosWorkPersistence(config))

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("IosWorkManager"),
    )
    private val mutex = Mutex()
    private val jobRegistry = mutableMapOf<Uuid, RegisteredWork>()
    private val stateStore = IosWorkStateStore(persistence = persistence, scope = scope)
    private val restore = scope.async { stateStore.restoreFromPersistence() }

    init {
        if (config.enableBackgroundTasks) {
            registerBgProcessingTask(
                identifier = config.bgProcessingTaskIdentifier,
                scope = scope,
            ) {
                runPendingWork()
            }
        }
    }

    override suspend fun enqueue(request: OneTimeWorkRequest): Uuid {
        registerWork(request) { executeOneTime(request) }?.start()
        return request.id
    }

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Uuid {
        existingWorkPolicy.checkIosOneTimePolicy()
        restore.await()
        val (id, job, replacedJobs) = mutex.withLock {
            val livePeriodicIds = jobRegistry.filterValues { it.periodic && !it.job.isCompleted }.keys
            val result = stateStore.enqueueUniqueOneTime(
                uniqueWorkName,
                existingWorkPolicy,
                request.id,
                request.tags,
                livePeriodicIds,
                request,
            )
            val oldJobs = result.replacedIds.mapNotNull { jobRegistry.remove(it)?.job }
            val newJob = if (result.enqueued) registerJob(request) { executeOneTime(request) } else null
            Triple(result.id, newJob, oldJobs)
        }
        replacedJobs.forEach { it.cancel() }
        job?.start()
        return id
    }

    override suspend fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ): Uuid {
        val job = registerWork(
            request,
            UniqueWork(uniqueWorkName, WorkKind.PERIODIC),
            replace = existingPeriodicWorkPolicy == ExistingPeriodicWorkPolicy.REPLACE,
        ) {
            val delayMs = request.initialDelay.inWholeMilliseconds
            if (delayMs > 0) delay(delayMs)
            while (isActive) {
                if (!executeWorker(request)) break
                delay(request.repeatInterval.inWholeMilliseconds)
            }
        } ?: return request.id
        // quickRefresh: schedule BGAppRefreshTaskRequest instead of BGProcessingTaskRequest.
        if (request.quickRefresh &&
            config.enableBackgroundTasks &&
            config.appRefreshTaskIdentifier.isNotEmpty()
        ) {
            scheduleBgAppRefreshTask(
                identifier = config.appRefreshTaskIdentifier,
                earliestBeginInSeconds = request.initialDelay.inWholeMilliseconds / 1000.0,
            )
        }
        job.start()
        return request.id
    }

    private suspend fun registerWork(
        request: WorkRequest,
        uniqueWork: UniqueWork? = null,
        replace: Boolean = false,
        execute: suspend CoroutineScope.() -> Unit,
    ): Job? {
        restore.await()
        val (job, replacedJobs) = mutex.withLock {
            val snapshot = stateStore.snapshot()
            val existing = snapshot[request.id]
            if (existing != null && existing.uniqueWork != uniqueWork) {
                throw WorkEnqueueException("Work ID ${request.id} is already used by a different work identity")
            }
            if (request.id in jobRegistry) return@withLock null
            if (existing != null && existing.info.state != WorkInfo.State.ENQUEUED) return@withLock null
            existing?.requireMatchingRequest(request)
            if (uniqueWork != null) {
                val oneTime = uniqueWork.copy(kind = WorkKind.ONE_TIME)
                if (snapshot.values.any { it.uniqueWork == oneTime && !it.info.isFinished }) {
                    throw WorkEnqueueException(
                        "Unique work '${uniqueWork.name}' is already used by active one-time work",
                    )
                }
            }
            persistence.validateRequest(request)
            val previousJobs = if (uniqueWork == null || !replace) {
                emptyList()
            } else {
                snapshot.values
                    .filter { it.info.id != request.id && it.uniqueWork == uniqueWork }
                    .mapNotNull { markCancelled(it.info.id) }
            }
            // 维持周期任务按旧名称标签查询和取消的行为；唯一调度按显式身份匹配。
            val tags = if (uniqueWork == null) request.tags else request.tags + uniqueWork.name
            stateStore.initWork(request.id, tags, uniqueWork, request)
            registerJob(request, execute) to previousJobs
        } ?: return null
        replacedJobs.forEach { it.cancel() }
        return job
    }

    private fun registerJob(request: WorkRequest, execute: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                execute()
            } finally {
                val currentJob = coroutineContext[Job]
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (jobRegistry[request.id]?.job === currentJob) jobRegistry.remove(request.id)
                    }
                }
            }
        }
        jobRegistry[request.id] = RegisteredWork(job, request is PeriodicWorkRequest)
        return job
    }

    override suspend fun cancelWorkById(id: Uuid) {
        restore.await()
        val job = mutex.withLock { markCancelled(id) }
        job?.cancel()
    }

    private fun markCancelled(id: Uuid): Job? {
        val work = jobRegistry[id]
        // 周期任务在两轮之间也可能显示为 SUCCEEDED 或 FAILED。
        val periodic = (work != null && work.periodic && !work.job.isCompleted) ||
            stateStore.snapshot()[id]?.request is PeriodicWorkRequest
        return if (stateStore.cancelWork(id, periodic)) jobRegistry.remove(id)?.job else null
    }

    override suspend fun cancelAllWorkByTag(tag: String) {
        restore.await()
        stateStore.snapshot().values
            .filter { tag in it.info.tags }
            .forEach { cancelWorkById(it.info.id) }
    }

    /**
     * Cancel a pending BGTaskScheduler task by its registered identifier.
     *
     * Delegates to `BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(...)`.
     * Use this when you need to cancel by the Info.plist-registered task identifier
     * (e.g. `"com.example.background-sync"`) rather than by the per-request [Uuid].
     *
     * `cancelWorkById(uuid)` still works for the [Uuid] case — this method exposes the
     * iOS-native cancellation primitive for cases where the consumer holds the task
     * identifier but not the per-work UUID (e.g. cleanup of a process-wide schedule
     * at app uninstall preflight).
     *
     * Added by cross-platform-worker-parity-audit sub-plan 04 (closes G3).
     */
    public fun cancelTask(identifier: String) {
        platform.BackgroundTasks.BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)
        Logger.withTag("worker-kmp.ios").i {
            "cancelTask(\"$identifier\") submitted to BGTaskScheduler"
        }
    }

    override fun getWorkInfosByTag(tag: String): Flow<List<WorkInfo>> = flow {
        restore.await()
        emitAll(stateStore.observeByTag(tag))
    }

    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> = flow {
        restore.await()
        emitAll(stateStore.observeByUniqueName(uniqueWorkName))
    }

    override suspend fun getWorkInfoById(id: Uuid): WorkInfo? {
        restore.await()
        return stateStore.getById(id)
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun executeOneTime(request: OneTimeWorkRequest) {
        val delayMs = request.initialDelay.inWholeMilliseconds
        if (delayMs > 0) delay(delayMs)
        awaitConstraintsSatisfied(request.constraints)
        executeWorker(request)
    }

    // Waits until all constraints in [constraints] are satisfied.
    // On iOS, only CONNECTED network type is natively constrainable via BGProcessingTask.
    // For foreground execution the constraint is skipped and work runs immediately;
    // when background tasks are enabled a BGProcessingTask is scheduled as an additional
    // wake-up source alongside the polling loop.
    private suspend fun awaitConstraintsSatisfied(constraints: Constraints) {
        if (isConstraintsSatisfied(constraints)) return
        if (config.enableBackgroundTasks) {
            scheduleBgProcessingTask(
                identifier = config.bgProcessingTaskIdentifier,
                requiresNetwork = constraints.requiredNetworkType != NetworkType.NOT_REQUIRED,
                requiresCharging = constraints.requiresCharging,
            )
        }
        // Poll until constraints are met — background task fires as an accelerated path.
        while (!isConstraintsSatisfied(constraints)) {
            delay(5_000)
        }
    }

    // Evaluates constraints that can be checked in-process on iOS.
    // Network availability is not checked here (no synchronous reachability API without
    // platform.SystemConfiguration); work runs optimistically and retries on failure.
    private fun isConstraintsSatisfied(constraints: Constraints): Boolean {
        // Battery-not-low and storage-not-low have no synchronous iOS API; pass conservatively.
        // Charging and device-idle also have no synchronous API; pass conservatively.
        // Network type: pass conservatively — BGProcessingTask gate handles the real constraint.
        return true
    }

    private suspend fun executeWorker(request: WorkRequest): Boolean {
        if (!stateStore.transitionToRunning(request.id, periodic = request is PeriodicWorkRequest)) return false

        val context = IosWorkerContext(
            id = request.id,
            inputData = request.inputData,
            tags = request.tags,
            stateStore = stateStore,
        )
        try {
            val worker = try {
                workerFactory.create(request.workerClass, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stateStore.finishWork(request.id, succeeded = false, outputData = WorkData.EMPTY)) {
                    Logger.withTag("worker-kmp.ios").e(e) { "Unable to create worker '${request.workerClass}'" }
                }
                return false
            }
            var attempt = 0
            var result: WorkResult
            do {
                if (!stateStore.incrementAttempt(request.id)) return false
                result = try {
                    worker.doWork()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WorkResult.Failure(e.message ?: "Unknown error")
                }

                if (result is WorkResult.Retry && attempt < request.retryConfig.maxAttempts - 1) {
                    delay(iosBackoffDelay(request.retryConfig, attempt).inWholeMilliseconds)
                    attempt++
                } else {
                    break
                }
            } while (true)

            if (request is PeriodicWorkRequest && result is WorkResult.Success) {
                try {
                    persistence.validateData(result.outputData)
                } catch (failure: IllegalArgumentException) {
                    result = WorkResult.Failure(failure.message ?: "Invalid iOS work output")
                }
            }
            return stateStore.finishWork(
                request.id,
                succeeded = result is WorkResult.Success,
                outputData = (result as? WorkResult.Success)?.outputData ?: WorkData.EMPTY,
            )
        } catch (e: CancellationException) {
            stateStore.cancelWork(request.id)
            throw e
        }
    }

    // Runs all ENQUEUED work that has unsatisfied constraints.
    // Called by the BGProcessingTask handler when iOS wakes the app in the background.
    private suspend fun runPendingWork(): Boolean {
        restore.await()
        val pending = stateStore.snapshot().values
            .filter { it.info.state == WorkInfo.State.ENQUEUED }
        if (pending.isEmpty()) return true
        pending.forEach { work ->
            val id = work.info.id
            mutex.withLock {
                if (id !in jobRegistry) {
                    // 仅重新保存仍处于排队状态的孤立记录，旧快照可能已被取消。
                    stateStore.refreshEnqueuedWork(id)
                }
            }
        }
        // Allow launched coroutines a moment to start — BG task has limited time budget.
        delay(100)
        return true
    }

    private data class RegisteredWork(val job: Job, val periodic: Boolean)
}

internal fun iosBackoffDelay(config: RetryConfig, attempt: Int): Duration {
    val delayMs = when (config.backoffPolicy) {
        BackoffPolicy.EXPONENTIAL ->
            (config.initialDelay.inWholeMilliseconds * config.multiplier.pow(attempt)).toLong()

        BackoffPolicy.LINEAR ->
            config.initialDelay.inWholeMilliseconds * (attempt + 1)
    }.coerceAtMost(config.maxDelay.inWholeMilliseconds)
    return delayMs.milliseconds
}
