package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * 后台窗口只执行当前到期的一轮任务，和前台管理器共用同一登记表。
 * 工作协程属于本次窗口，窗口结束前等待其清理并同步保存中断状态。
 */
internal class IosPendingWorkRunner(
    private val store: IosWorkStateStore,
    private val mutex: Mutex,
    private val jobs: MutableMap<Uuid, Job>,
    private val now: () -> Long,
    private val isEligible: suspend (WorkRequest) -> Boolean,
    private val execute: suspend (WorkRequest) -> Boolean,
) {
    suspend fun run(): Boolean = supervisorScope {
        var succeeded = true
        for (id in store.snapshot().keys) {
            currentCoroutineContext().ensureActive()
            val candidate = store.snapshot()[id]?.request ?: continue
            if (!isEligible(candidate)) continue
            val job = mutex.withLock {
                val record = store.snapshot()[id]
                if (record == null || !isDue(record, now()) || jobs.containsKey(id)) {
                    null
                } else {
                    async(start = CoroutineStart.LAZY) {
                        execute(checkNotNull(record.request))
                    }.also { jobs[id] = it }
                }
            } ?: continue
            try {
                job.start()
                val result = try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    // 单项显式取消不应取消整个后台窗口。
                    currentCoroutineContext().ensureActive()
                    false
                } catch (_: Exception) {
                    false
                }
                succeeded = result && succeeded
            } finally {
                withContext(NonCancellable) {
                    job.cancelAndJoin()
                    mutex.withLock {
                        if (jobs[id] === job) {
                            store.requeueInterrupted(id, now())
                            jobs.remove(id)
                        }
                    }
                    store.persistCurrent(id)
                }
            }
        }
        store.flush()
        succeeded
    }
}

internal fun isDue(work: IosWorkStateStore.StoredWork, now: Long): Boolean =
    work.request != null && work.nextRunAtEpochMillis != null && work.nextRunAtEpochMillis <= now &&
        (
            work.info.state == WorkInfo.State.ENQUEUED ||
                (
                    work.request is PeriodicWorkRequest && work.info.isFinished &&
                        work.info.state != WorkInfo.State.CANCELLED
                    )
            )

internal data class IosBackgroundWake(
    val earliestAtEpochMillis: Long,
    val requiresNetwork: Boolean,
    val requiresCharging: Boolean,
)

/** 保留运行中任务的系统恢复机会，当前执行者仍由同一 Job 登记表排除。 */
internal fun iosBackgroundWake(records: Collection<IosWorkStateStore.StoredWork>, now: Long): IosBackgroundWake? {
    val pending = records.filter { work ->
        work.request != null && work.info.state != WorkInfo.State.CANCELLED &&
            (
                work.info.state == WorkInfo.State.RUNNING ||
                    (
                        work.nextRunAtEpochMillis != null &&
                            (work.info.state == WorkInfo.State.ENQUEUED || work.request is PeriodicWorkRequest)
                        )
                )
    }
    if (pending.isEmpty()) return null
    return IosBackgroundWake(
        earliestAtEpochMillis = pending.minOf { it.nextRunAtEpochMillis ?: now },
        requiresNetwork = pending.any {
            checkNotNull(it.request).constraints.requiredNetworkType != NetworkType.NOT_REQUIRED
        },
        requiresCharging = pending.any { checkNotNull(it.request).constraints.requiresCharging },
    )
}
