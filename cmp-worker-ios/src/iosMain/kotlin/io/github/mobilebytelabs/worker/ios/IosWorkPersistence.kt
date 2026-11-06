@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import platform.Foundation.NSUserDefaults
import kotlin.uuid.Uuid

// ── NSUserDefaults implementation ─────────────────────────────────────────────

internal class NSUserDefaultsWorkPersistence(private val key: String) : IosWorkPersistence {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))

    override suspend fun saveRecord(record: StoredWork) {
        val items = loadEncoded().toMutableList()
        items.removeAll { it.startsWith("${record.info.id}|") }
        items.add(IosWorkRecordCodec.encode(record))
        @Suppress("UNCHECKED_CAST")
        defaults.setObject(items as List<*>, forKey = key)
    }

    override suspend fun loadAll(): List<WorkInfo> = loadRecords().map { it.info }

    override suspend fun loadRecords(): List<StoredWork> = loadEncoded().mapNotNull(IosWorkRecordCodec::decode)

    override fun validateRequest(request: WorkRequest) = IosWorkRecordCodec.validateRequest(request)

    override fun validateData(data: WorkData) = IosWorkRecordCodec.validateData(data)

    override suspend fun delete(id: Uuid) {
        val items = loadEncoded().filterNot { it.startsWith("$id|") }
        @Suppress("UNCHECKED_CAST")
        defaults.setObject(items as List<*>, forKey = key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadEncoded(): List<String> =
        (defaults.arrayForKey(key) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}

internal fun createIosWorkPersistence(config: IosWorkManagerConfig): IosWorkPersistence =
    if (config.enablePersistence) {
        NSUserDefaultsWorkPersistence(config.persistenceKey)
    } else {
        NoOpIosWorkPersistence
    }
