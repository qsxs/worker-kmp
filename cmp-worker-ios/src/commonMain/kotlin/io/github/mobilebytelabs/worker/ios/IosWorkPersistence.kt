package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import kotlin.uuid.Uuid

internal interface IosWorkPersistence {
    suspend fun save(info: WorkInfo)
    suspend fun loadAll(): List<WorkInfo>
    suspend fun delete(id: Uuid)

    suspend fun saveRecord(record: StoredWork) = save(record.info)

    suspend fun loadRecords(): List<StoredWork> = loadAll().map { StoredWork(it) }

    fun validateRequest(request: WorkRequest) = Unit

    fun validateData(data: WorkData) = Unit
}

internal object NoOpIosWorkPersistence : IosWorkPersistence {
    override suspend fun save(info: WorkInfo) = Unit
    override suspend fun loadAll(): List<WorkInfo> = emptyList()
    override suspend fun delete(id: Uuid) = Unit
}
