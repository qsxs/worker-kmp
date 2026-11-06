package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.PersistenceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** 持有本次实际执行协程；状态终结、登记表移除均不能代替等待协程退出。 */
internal class IosOwnedWorkExecution(
    private val scope: CoroutineScope,
    private val store: IosWorkStateStore,
    private val mutex: Mutex,
    private val jobs: MutableMap<Uuid, Job>,
    private val validateRequest: (WorkRequest) -> Unit,
    private val now: () -> Long,
    private val schedule: suspend () -> Unit,
) {
    suspend fun enqueueAndAwait(request: OneTimeWorkRequest, execute: suspend CoroutineScope.() -> Unit): WorkInfo {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        var ownedJob: Deferred<Unit>? = null
        var registered = false
        var failure: Throwable? = null
        try {
            withContext(NonCancellable) {
                mutex.withLock {
                    callerContext.ensureActive()
                    if (request.id in store.snapshot() || request.id in jobs) {
                        throw WorkEnqueueException("Owned iOS work requires a new work ID: ${request.id}")
                    }
                    validateRequest(request)
                    // 引用必须在块内保存，避免返回调用方时的及时取消丢失登记结果。
                    val job = scope.async(start = CoroutineStart.LAZY, block = execute)
                    ownedJob = job
                    jobs[request.id] = job
                    registered = true
                    store.initWork(
                        request.id,
                        request.tags,
                        request = request,
                        nextRunAtEpochMillis = iosInitialDueAtEpochMillis(now(), request),
                        persistenceMode = PersistenceMode.OWNED,
                    )
                    store.persistCurrent(request.id)
                }
            }
            currentCoroutineContext().ensureActive()
            val job = checkNotNull(ownedJob)
            job.start()
            schedule()
            // await 等待实际 Job、其子协程和 finally，不能按 WorkInfo.isFinished 短路。
            job.await()
        } catch (cause: Throwable) {
            val primary = try {
                callerContext.ensureActive()
                cause
            } catch (cancelled: CancellationException) {
                if (cause !is CancellationException) cancelled.addSuppressed(cause)
                cancelled
            }
            failure = primary
            throw primary
        } finally {
            withContext(NonCancellable) {
                try {
                    settle(request.id, ownedJob, registered, failure)
                } catch (cleanupFailure: Throwable) {
                    val original = failure
                    if (original == null) throw cleanupFailure
                    if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                }
            }
        }
        return checkNotNull(store.getById(request.id)) { "Owned iOS work lost its state: ${request.id}" }
    }

    private suspend fun settle(id: Uuid, job: Deferred<Unit>?, registered: Boolean, original: Throwable?) {
        var failure: Throwable? = null
        fun retain(cause: Throwable) {
            if (cause === original) return
            val previous = failure
            if (previous == null) {
                failure = cause
            } else if (previous !== cause) {
                previous.addSuppressed(cause)
            }
        }

        // LAZY Job 可能从未进入协程体；持有者始终负责清理，并在锁外等待真实结果。
        job?.cancel()
        try {
            job?.await()
        } catch (cause: Throwable) {
            if (cause !is CancellationException) retain(cause)
        }
        if (registered) {
            try {
                mutex.withLock {
                    if (job?.isCancelled == true || original != null) store.cancelWork(id, periodic = true)
                }
                try {
                    store.persistCurrent(id)
                } catch (cause: Throwable) {
                    retain(cause)
                }
                try {
                    schedule()
                } catch (cause: Throwable) {
                    retain(cause)
                }
            } finally {
                mutex.withLock {
                    if (jobs[id] === job) jobs.remove(id)
                }
            }
        }
        failure?.let { throw it }
    }
}

/** 同步工厂可能晚于取消返回，返回的对象只能交给仍有效的执行协程。 */
internal suspend fun <T> createActiveIosWorker(create: () -> T): T {
    currentCoroutineContext().ensureActive()
    val worker = create()
    currentCoroutineContext().ensureActive()
    return worker
}
