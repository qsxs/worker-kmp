package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

internal class InMemoryIosWorkPersistence : IosWorkPersistence {
    private val mutex = Mutex()
    private val items = mutableMapOf<Uuid, WorkInfo>()

    override suspend fun save(info: WorkInfo) {
        mutex.withLock { items[info.id] = info }
    }

    override suspend fun loadAll(): List<WorkInfo> = mutex.withLock { items.values.toList() }

    override suspend fun delete(id: Uuid) {
        mutex.withLock { items.remove(id) }
    }
}
