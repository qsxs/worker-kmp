package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkData
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
    private val store = MutableStateFlow<Map<Uuid, WorkInfo>>(emptyMap())

    // RUNNING → ENQUEUED on restore (worker was interrupted by an app kill mid-execution).
    // Only populates when the store is still empty — prevents clobbering in-flight mutations.
    suspend fun restoreFromPersistence() {
        val saved = persistence.loadAll()
        if (saved.isEmpty()) return
        val restored = saved.associate { info ->
            val state = if (info.state == WorkInfo.State.RUNNING) WorkInfo.State.ENQUEUED else info.state
            info.id to info.copy(state = state)
        }
        store.compareAndSet(emptyMap(), restored)
    }

    fun initWork(id: Uuid, tags: Set<String>) {
        updateWork(id) { current ->
            if (current == null) WorkInfo(id = id, state = WorkInfo.State.ENQUEUED, tags = tags) else null
        }
    }

    fun transitionToRunning(id: Uuid, periodic: Boolean): Boolean = updateWork(id) { info ->
        when (info?.state) {
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
        info?.takeIf { it.state != WorkInfo.State.CANCELLED && (!it.isFinished || periodic) }
            ?.copy(state = WorkInfo.State.CANCELLED, outputData = WorkData.EMPTY)
    }

    fun refreshEnqueuedWork(id: Uuid) {
        updateWork(id) { info ->
            info?.takeIf { it.state == WorkInfo.State.ENQUEUED }?.copy(outputData = WorkData.EMPTY)
        }
    }

    fun updateProgress(id: Uuid, progress: WorkProgress) {
        updateRunningWork(id) { it.copy(progress = progress) }
    }

    fun incrementAttempt(id: Uuid): Boolean = updateRunningWork(id) {
        it.copy(runAttemptCount = it.runAttemptCount + 1)
    }

    suspend fun getById(id: Uuid): WorkInfo? = store.value[id]

    fun observeByTag(tag: String): Flow<List<WorkInfo>> = store.map { infos -> infos.values.filter { tag in it.tags } }

    fun snapshot(): Map<Uuid, WorkInfo> = store.value

    private fun updateRunningWork(id: Uuid, transform: (WorkInfo) -> WorkInfo): Boolean =
        updateWork(id) { info -> info?.takeIf { it.state == WorkInfo.State.RUNNING }?.let(transform) }

    private fun updateWork(id: Uuid, transform: (WorkInfo?) -> WorkInfo?): Boolean {
        while (true) {
            val snapshot = store.value
            val updated = transform(snapshot[id]) ?: return false
            if (store.compareAndSet(snapshot, snapshot + (id to updated))) {
                scope?.launch {
                    if (updated.isFinished) persistence.delete(id) else persistence.save(updated)
                }
                return true
            }
        }
    }
}
