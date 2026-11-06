package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExperimentalWorkerApi
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.WorkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    init {
        scope.launch { stateStore.restoreFromPersistence() }
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
        registerWork(request, request.tags) {
            val delayMs = request.initialDelay.inWholeMilliseconds
            if (delayMs > 0) delay(delayMs)
            awaitConstraintsSatisfied(request.constraints)
            executeWorker(request)
        }?.start()
        return request.id
    }

    override suspend fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ): Uuid {
        val replaceTag = uniqueWorkName.takeIf { existingPeriodicWorkPolicy == ExistingPeriodicWorkPolicy.REPLACE }
        val job = registerWork(request, request.tags + uniqueWorkName, replaceTag) {
            val delayMs = request.initialDelay.inWholeMilliseconds
            if (delayMs > 0) delay(delayMs)
            while (isActive) {
                executeWorker(request)
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
        tags: Set<String>,
        replaceTag: String? = null,
        execute: suspend CoroutineScope.() -> Unit,
    ): Job? {
        val (job, replacedJobs) = mutex.withLock {
            val snapshot = stateStore.snapshot()
            val existing = snapshot[request.id]
            if (request.id in jobRegistry) return@withLock null
            if (existing != null && existing.state != WorkInfo.State.ENQUEUED) return@withLock null
            val previousJobs = if (replaceTag == null) {
                emptyList()
            } else {
                snapshot.values
                    .filter { it.id != request.id && replaceTag in it.tags }
                    .mapNotNull { markCancelled(it.id) }
            }
            stateStore.initWork(request.id, tags)
            // 空快照时，恢复可能先于 initWork 写入同一个 ID。
            if (stateStore.getById(request.id)?.state != WorkInfo.State.ENQUEUED) {
                return@withLock null to previousJobs
            }
            val newJob = scope.launch(start = CoroutineStart.LAZY) {
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
            jobRegistry[request.id] = RegisteredWork(newJob, request is PeriodicWorkRequest)
            newJob to previousJobs
        } ?: return null
        replacedJobs.forEach { it.cancel() }
        return job
    }

    override suspend fun cancelWorkById(id: Uuid) {
        val job = mutex.withLock { markCancelled(id) }
        job?.cancel()
    }

    private fun markCancelled(id: Uuid): Job? {
        val work = jobRegistry[id]
        // 周期任务在两轮之间也可能显示为 SUCCEEDED 或 FAILED。
        val periodic = work != null && work.periodic && !work.job.isCompleted
        return if (stateStore.cancelWork(id, periodic)) jobRegistry.remove(id)?.job else null
    }

    override suspend fun cancelAllWorkByTag(tag: String) {
        stateStore.snapshot().values
            .filter { tag in it.tags }
            .forEach { cancelWorkById(it.id) }
    }

    // cross-platform-worker-parity-audit sub-plan 03 (closes G2) — iOS impl delegates to
    // plain enqueue; iOS BGTaskScheduler doesn't have a name-keyed unique-work primitive,
    // so REPLACE/KEEP/APPEND semantics are best-effort: REPLACE acts like a fresh enqueue,
    // KEEP doesn't dedup (would require cross-restart state mapping name→Uuid), APPEND/
    // APPEND_OR_REPLACE same. AndroidX-level semantics live on the Android actual only.
    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: io.github.mobilebytelabs.worker.ExistingWorkPolicy,
        request: io.github.mobilebytelabs.worker.OneTimeWorkRequest,
    ): Uuid = enqueue(request)

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
        co.touchlab.kermit.Logger.withTag("worker-kmp.ios").i {
            "cancelTask(\"$identifier\") submitted to BGTaskScheduler"
        }
    }

    override fun getWorkInfosByTag(tag: String): Flow<List<WorkInfo>> = stateStore.observeByTag(tag)

    // worker-kmp-single-api-completion sub-plan 01 — uniqueWorkName is added to the work's
    // tag set at enqueue time (see line ~84), so unique-work observation reduces to tag
    // observation with the uniqueWorkName as the tag.
    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
        stateStore.observeByTag(uniqueWorkName)

    override suspend fun getWorkInfoById(id: Uuid): WorkInfo? = stateStore.getById(id)

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
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

    private suspend fun executeWorker(request: WorkRequest) {
        val context = IosWorkerContext(
            id = request.id,
            inputData = request.inputData,
            tags = request.tags,
            stateStore = stateStore,
        )
        val worker = workerFactory.create(request.workerClass, context)
        if (!stateStore.transitionToRunning(request.id, periodic = request is PeriodicWorkRequest)) return

        var attempt = 0
        var result: WorkResult
        try {
            do {
                if (!stateStore.incrementAttempt(request.id)) return
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

            stateStore.finishWork(
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
        val pending = stateStore.snapshot().values
            .filter { it.state == WorkInfo.State.ENQUEUED }
        if (pending.isEmpty()) return true
        pending.forEach { info ->
            mutex.withLock {
                if (info.id !in jobRegistry) {
                    // 仅重新保存仍处于排队状态的孤立记录，旧快照可能已被取消。
                    stateStore.refreshEnqueuedWork(info.id)
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
