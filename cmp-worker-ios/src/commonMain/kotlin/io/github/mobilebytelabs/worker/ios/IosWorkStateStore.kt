package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal class IosWorkStateStore(
    private val persistence: IosWorkPersistence = NoOpIosWorkPersistence,
    private val scope: CoroutineScope? = null,
) {
    private val store = MutableStateFlow<Map<Uuid, StoredWork>>(emptyMap())

    // RUNNING → ENQUEUED on restore (worker was interrupted by an app kill mid-execution).
    // Only populates when the store is still empty — prevents clobbering in-flight mutations.
    suspend fun restoreFromPersistence() {
        val saved = persistence.loadAll()
        if (saved.isEmpty()) return
        val restored = saved.associate { info ->
            val state = if (info.state == WorkInfo.State.RUNNING) WorkInfo.State.ENQUEUED else info.state
            info.id to StoredWork(info.copy(state = state))
        }
        store.compareAndSet(emptyMap(), restored)
    }

    fun initWork(id: Uuid, tags: Set<String>, uniqueWork: UniqueWork? = null): StoredWork {
        val queued = StoredWork(WorkInfo(id = id, state = WorkInfo.State.ENQUEUED, tags = tags), uniqueWork)
        while (true) {
            val snapshot = store.value
            snapshot[id]?.let { return it }
            if (store.compareAndSet(snapshot, snapshot + (id to queued))) {
                persist(queued.info)
                return queued
            }
        }
    }

    fun enqueueUniqueOneTime(
        name: String,
        policy: ExistingWorkPolicy,
        id: Uuid,
        tags: Set<String>,
        livePeriodicIds: Set<Uuid> = emptySet(),
    ): UniqueEnqueue {
        if (policy == ExistingWorkPolicy.APPEND || policy == ExistingWorkPolicy.APPEND_OR_REPLACE) {
            throw WorkEnqueueException("iOS unique work does not support $policy")
        }
        val uniqueWork = UniqueWork(name, WorkKind.ONE_TIME)
        val queued = StoredWork(WorkInfo(id = id, state = WorkInfo.State.ENQUEUED, tags = tags), uniqueWork)
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

            val replaced = active.associate { work ->
                work.info.id to work.copy(info = work.info.asCancelled())
            }
            if (store.compareAndSet(snapshot, snapshot + replaced + (id to queued))) {
                replaced.values.forEach { persist(it.info) }
                persist(queued.info)
                return UniqueEnqueue(id, enqueued = true, replacedIds = replaced.keys)
            }
        }
    }

    fun transitionToRunning(id: Uuid, periodic: Boolean): Boolean = updateWork(id) { info ->
        when (info.state) {
            WorkInfo.State.ENQUEUED -> info.copy(state = WorkInfo.State.RUNNING)

            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED ->
                if (periodic) info.copy(state = WorkInfo.State.RUNNING) else null

            else -> null
        }
    }

    fun finishWork(id: Uuid, succeeded: Boolean, outputData: WorkData): Boolean = updateRunningWork(id) { info ->
        val state = if (succeeded) WorkInfo.State.SUCCEEDED else WorkInfo.State.FAILED
        info.copy(state = state, outputData = outputData)
    }

    fun cancelWork(id: Uuid, periodic: Boolean = false): Boolean = updateWork(id) { info ->
        info.takeIf { it.state != WorkInfo.State.CANCELLED && (!it.isFinished || periodic) }
            ?.asCancelled()
    }

    fun refreshEnqueuedWork(id: Uuid) {
        updateWork(id) { info ->
            info.takeIf { it.state == WorkInfo.State.ENQUEUED }?.copy(outputData = WorkData.EMPTY)
        }
    }

    fun updateProgress(id: Uuid, progress: WorkProgress) {
        updateRunningWork(id) { it.copy(progress = progress) }
    }

    fun incrementAttempt(id: Uuid): Boolean = updateRunningWork(id) {
        it.copy(runAttemptCount = it.runAttemptCount + 1)
    }

    suspend fun getById(id: Uuid): WorkInfo? = store.value[id]?.info

    fun observeByTag(tag: String): Flow<List<WorkInfo>> =
        store.map { records -> records.values.mapNotNull { it.info.takeIf { info -> tag in info.tags } } }

    fun observeByUniqueName(name: String): Flow<List<WorkInfo>> =
        store.map { records -> records.values.filter { it.uniqueWork?.name == name }.map { it.info } }

    fun snapshot(): Map<Uuid, StoredWork> = store.value

    private fun updateRunningWork(id: Uuid, transform: (WorkInfo) -> WorkInfo): Boolean =
        updateWork(id) { info -> info.takeIf { it.state == WorkInfo.State.RUNNING }?.let(transform) }

    private fun updateWork(id: Uuid, transform: (WorkInfo) -> WorkInfo?): Boolean {
        while (true) {
            val snapshot = store.value
            val work = snapshot[id] ?: return false
            val updated = transform(work.info) ?: return false
            if (store.compareAndSet(snapshot, snapshot + (id to work.copy(info = updated)))) {
                persist(updated)
                return true
            }
        }
    }

    private fun persist(info: WorkInfo) {
        scope?.launch {
            if (info.isFinished) persistence.delete(info.id) else persistence.save(info)
        }
    }

    private fun StoredWork.isActive(livePeriodicIds: Set<Uuid>): Boolean =
        !info.isFinished || (info.state != WorkInfo.State.CANCELLED && info.id in livePeriodicIds)

    private fun WorkInfo.asCancelled(): WorkInfo = copy(state = WorkInfo.State.CANCELLED, outputData = WorkData.EMPTY)

    data class StoredWork(val info: WorkInfo, val uniqueWork: UniqueWork? = null)

    data class UniqueWork(val name: String, val kind: WorkKind)

    enum class WorkKind { ONE_TIME, PERIODIC }

    data class UniqueEnqueue(val id: Uuid, val enqueued: Boolean, val replacedIds: Set<Uuid> = emptySet())
}
