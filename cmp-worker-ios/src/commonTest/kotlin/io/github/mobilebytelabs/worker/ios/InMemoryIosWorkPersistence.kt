package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

internal class InMemoryIosWorkPersistence : IosWorkPersistence {
    private val mutex = Mutex()
    private val items = mutableMapOf<Uuid, StoredWork>()

    override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))

    override suspend fun loadAll(): List<WorkInfo> = loadRecords().map { it.info }

    override suspend fun saveRecord(record: StoredWork) {
        mutex.withLock { items[record.info.id] = record }
    }

    override suspend fun loadRecords(): List<StoredWork> = mutex.withLock { items.values.toList() }

    override suspend fun delete(id: Uuid) {
        mutex.withLock { items.remove(id) }
    }
}
