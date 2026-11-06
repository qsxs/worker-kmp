package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkInfo
import kotlin.uuid.Uuid

internal interface IosWorkPersistence {
    suspend fun save(info: WorkInfo)
    suspend fun loadAll(): List<WorkInfo>
    suspend fun delete(id: Uuid)
}

internal object NoOpIosWorkPersistence : IosWorkPersistence {
    override suspend fun save(info: WorkInfo) = Unit
    override suspend fun loadAll(): List<WorkInfo> = emptyList()
    override suspend fun delete(id: Uuid) = Unit
}
