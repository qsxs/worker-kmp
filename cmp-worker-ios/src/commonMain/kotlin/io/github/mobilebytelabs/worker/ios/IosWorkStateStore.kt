package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

internal class IosWorkStateStore(
    private val persistence: IosWorkPersistence = NoOpIosWorkPersistence,
    private val scope: CoroutineScope? = null,
) {
    private val store = MutableStateFlow<Map<Uuid, StoredWork>>(emptyMap())
    private val persistenceMutex = Mutex()

    // RUNNING → ENQUEUED on restore (worker was interrupted by an app kill mid-execution).
    // Only populates when the store is still empty — prevents clobbering in-flight mutations.
    suspend fun restoreFromPersistence() {
        persistenceMutex.withLock {
            val saved = persistence.loadRecords()
            if (saved.isEmpty()) return@withLock
            val restored = saved.associate { work ->
                val info = work.info
                val state = if (info.state == WorkInfo.State.RUNNING) WorkInfo.State.ENQUEUED else info.state
                info.id to work.copy(info = info.copy(state = state))
            }
            store.compareAndSet(emptyMap(), restored)
        }
    }

    fun initWork(
        id: Uuid,
        tags: Set<String>,
        uniqueWork: UniqueWork? = null,
        request: WorkRequest? = null,
        nextRunAtEpochMillis: Long? = null,
    ): StoredWork {
        val initialDue = nextRunAtEpochMillis.takeIf { request != null }
        val queued = StoredWork(
            WorkInfo(id = id, state = WorkInfo.State.ENQUEUED, tags = tags),
            uniqueWork,
            request,
            initialDue,
        )
        while (true) {
            val snapshot = store.value
            val existing = snapshot[id]
            val record = if (existing == null) {
                queued
            } else {
                // 只用显式提交的请求补全旧记录，保留已经持久化的截止时间。
                val attached = existing.request ?: request
                val dueAt =
                    existing.nextRunAtEpochMillis
                        ?: initialDue.takeIf { existing.info.state == WorkInfo.State.ENQUEUED }
                if (existing.request === attached && existing.nextRunAtEpochMillis == dueAt) return existing
                existing.copy(request = attached, nextRunAtEpochMillis = dueAt)
            }
            if (store.compareAndSet(snapshot, snapshot + (id to record))) {
                persist(id)
                return record
            }
        }
    }

    fun enqueueUniqueOneTime(
        name: String,
        policy: ExistingWorkPolicy,
        id: Uuid,
        tags: Set<String>,
        livePeriodicIds: Set<Uuid> = emptySet(),
        request: WorkRequest? = null,
        nextRunAtEpochMillis: Long? = null,
    ): UniqueEnqueue {
        policy.checkIosOneTimePolicy()
        val uniqueWork = UniqueWork(name, WorkKind.ONE_TIME)
        val queued = StoredWork(
            WorkInfo(id = id, state = WorkInfo.State.ENQUEUED, tags = tags),
            uniqueWork,
            request,
            nextRunAtEpochMillis.takeIf { request != null },
        )
        while (true) {
            val snapshot = store.value
            val existing = snapshot[id]
            if (existing != null) {
                if (existing.uniqueWork != uniqueWork || existing.info.isFinished) {
                    throw WorkEnqueueException("Work ID $id cannot be reused for unique one-time work '$name'")
                }
                return UniqueEnqueue(id, enqueued = false)
            }

            val named = snapshot.values.filter { it.uniqueWork?.name == name }
            if (named.any { it.uniqueWork?.kind == WorkKind.PERIODIC && it.isActive(livePeriodicIds) }) {
                throw WorkEnqueueException("Unique work '$name' is already used by active periodic work")
            }
            val active = named.filter { it.uniqueWork?.kind == WorkKind.ONE_TIME && !it.info.isFinished }
            if (policy == ExistingWorkPolicy.KEEP && active.isNotEmpty()) {
                return UniqueEnqueue(active.first().info.id, enqueued = false)
            }

            request?.let(persistence::validateRequest)
            val replaced = active.associate { work ->
                work.info.id to work.asCancelled()
            }
            if (store.compareAndSet(snapshot, snapshot + replaced + (id to queued))) {
                replaced.keys.forEach(::persist)
                persist(id)
                return UniqueEnqueue(id, enqueued = true, replacedIds = replaced.keys)
            }
        }
    }

    fun transitionToRunning(id: Uuid, periodic: Boolean): Boolean = updateRecord(id) { work ->
        val state = work.info.state
        if (state == WorkInfo.State.ENQUEUED ||
            (periodic && (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED))
        ) {
            work.copy(info = work.info.copy(state = WorkInfo.State.RUNNING), nextRunAtEpochMillis = null)
        } else {
            null
        }
    }

    fun finishWork(id: Uuid, succeeded: Boolean, outputData: WorkData, nextRunAtEpochMillis: Long? = null): Boolean =
        updateRunningRecord(id) { work ->
            val state = if (succeeded) WorkInfo.State.SUCCEEDED else WorkInfo.State.FAILED
            work.copy(
                info = work.info.copy(state = state, outputData = outputData),
                nextRunAtEpochMillis = nextRunAtEpochMillis,
            )
        }

    fun cancelWork(id: Uuid, periodic: Boolean = false): Boolean = updateRecord(id) { work ->
        val info = work.info
        work.takeIf { info.state != WorkInfo.State.CANCELLED && (!info.isFinished || periodic) }?.asCancelled()
    }

    fun refreshEnqueuedWork(id: Uuid) {
        updateWork(id) { info ->
            info.takeIf { it.state == WorkInfo.State.ENQUEUED }?.copy(outputData = WorkData.EMPTY)
        }
    }

    fun updateProgress(id: Uuid, progress: WorkProgress) {
        updateRunningWork(id) {
            persistence.validateData(progress.data)
            it.copy(progress = progress)
        }
    }

    fun incrementAttempt(id: Uuid): Boolean = updateRunningRecord(id) { work ->
        work.copy(
            info = work.info.copy(runAttemptCount = work.info.runAttemptCount + 1),
            nextRunAtEpochMillis = null,
        )
    }

    suspend fun scheduleRetry(id: Uuid, nextRunAtEpochMillis: Long): Boolean {
        val updated = updateRunningRecord(id, persistAsync = false) {
            it.copy(nextRunAtEpochMillis = nextRunAtEpochMillis)
        }
        if (!updated) return false
        // 等待重试前写入截止时间，进程重启才能知道还有多少退避时间。
        persistCurrent(id)
        return true
    }

    suspend fun getById(id: Uuid): WorkInfo? = store.value[id]?.info

    fun observeByTag(tag: String): Flow<List<WorkInfo>> =
        store.map { records -> records.values.mapNotNull { it.info.takeIf { info -> tag in info.tags } } }

    fun observeByUniqueName(name: String): Flow<List<WorkInfo>> =
        store.map { records -> records.values.filter { it.uniqueWork?.name == name }.map { it.info } }

    fun snapshot(): Map<Uuid, StoredWork> = store.value

    private fun updateRunningWork(id: Uuid, transform: (WorkInfo) -> WorkInfo): Boolean =
        updateWork(id) { info -> info.takeIf { it.state == WorkInfo.State.RUNNING }?.let(transform) }

    private fun updateWork(id: Uuid, transform: (WorkInfo) -> WorkInfo?): Boolean =
        updateRecord(id) { work -> transform(work.info)?.let { work.copy(info = it) } }

    private fun updateRunningRecord(
        id: Uuid,
        persistAsync: Boolean = true,
        transform: (StoredWork) -> StoredWork,
    ): Boolean = updateRecord(id, persistAsync) { work ->
        work.takeIf { it.info.state == WorkInfo.State.RUNNING }?.let(transform)
    }

    private fun updateRecord(id: Uuid, persistAsync: Boolean = true, transform: (StoredWork) -> StoredWork?): Boolean {
        while (true) {
            val snapshot = store.value
            val work = snapshot[id] ?: return false
            val updated = transform(work) ?: return false
            if (store.compareAndSet(snapshot, snapshot + (id to updated))) {
                if (persistAsync) persist(id)
                return true
            }
        }
    }

    private fun persist(id: Uuid) {
        scope?.launch { persistCurrent(id) }
    }

    private suspend fun persistCurrent(id: Uuid) {
        persistenceMutex.withLock {
            // 排队的旧写入应保存当前状态，不能把已取消的工作写回。
            val work = store.value[id] ?: return@withLock
            if (work.info.isFinished && !work.hasRecurringRequest()) {
                persistence.delete(id)
            } else {
                persistence.saveRecord(work)
            }
        }
    }

    private fun StoredWork.isActive(livePeriodicIds: Set<Uuid>): Boolean = !info.isFinished || hasRecurringRequest() ||
        (info.state != WorkInfo.State.CANCELLED && info.id in livePeriodicIds)

    private fun StoredWork.hasRecurringRequest(): Boolean =
        request is PeriodicWorkRequest && info.state != WorkInfo.State.CANCELLED

    private fun StoredWork.asCancelled(): StoredWork = copy(
        info = info.copy(state = WorkInfo.State.CANCELLED, outputData = WorkData.EMPTY),
        nextRunAtEpochMillis = null,
    )

    data class StoredWork(
        val info: WorkInfo,
        val uniqueWork: UniqueWork? = null,
        val request: WorkRequest? = null,
        val nextRunAtEpochMillis: Long? = null,
    ) {
        fun requireMatchingRequest(incoming: WorkRequest) {
            val saved = request ?: return
            if (!IosWorkRecordCodec.matchesRequest(saved, incoming)) {
                throw WorkEnqueueException("Work ID ${info.id} is already used by a different work request")
            }
        }
    }

    data class UniqueWork(val name: String, val kind: WorkKind)

    enum class WorkKind { ONE_TIME, PERIODIC }

    data class UniqueEnqueue(val id: Uuid, val enqueued: Boolean, val replacedIds: Set<Uuid> = emptySet())
}

internal fun ExistingWorkPolicy.checkIosOneTimePolicy() {
    if (this == ExistingWorkPolicy.APPEND || this == ExistingWorkPolicy.APPEND_OR_REPLACE) {
        throw WorkEnqueueException("iOS unique work does not support $this")
    }
}
