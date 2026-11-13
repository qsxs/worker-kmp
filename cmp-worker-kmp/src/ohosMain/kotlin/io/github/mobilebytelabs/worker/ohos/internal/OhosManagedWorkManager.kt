package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.ohos.OhosWorkManagerConfiguration
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.uuid.Uuid

internal class OhosManagedWorkManager(
    scope: CoroutineScope,
    registry: WorkerRegistry,
    configuration: OhosWorkManagerConfiguration,
) : WorkManager {
    init {
        registry.lock()
    }

    private val database = SqliteWorkStore(configuration.directory)
    private val store = SqliteManagedWorkStore(database)
    private val commands = OhosWorkCommandCoordinator(scope, registry, configuration, database, store)

    override suspend fun enqueue(request: OneTimeWorkRequest): Uuid =
        commands.enqueueOneTime(null, ExistingWorkPolicy.REPLACE, request)

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Uuid = commands.enqueueOneTime(uniqueWorkName, existingWorkPolicy, request)

    override suspend fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ): Uuid = commands.enqueuePeriodic(uniqueWorkName, existingPeriodicWorkPolicy, request)

    override suspend fun cancelWorkById(id: Uuid) = commands.cancel { cancelId(id) }

    suspend fun cancelUniqueWork(uniqueWorkName: String) = commands.cancel(uniqueWorkName) {
        cancelName(uniqueWorkName)
    }

    override suspend fun cancelAllWorkByTag(tag: String) = commands.cancel { cancelTag(tag) }

    override suspend fun getWorkInfoById(id: Uuid): WorkInfo? {
        database.initialize()
        return store.read(id)?.info
    }

    fun getWorkInfoByIdFlow(id: Uuid): Flow<WorkInfo?> =
        changes().map { store.read(id)?.info }.distinctUntilChanged(::sameInfo)

    override fun getWorkInfosByTag(tag: String): Flow<List<WorkInfo>> = observe { store.tagged(tag) }

    override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
        observe { store.named(uniqueWorkName) }

    suspend fun recoverPendingWork() = commands.recoverPendingWork()

    private fun changes(): Flow<Unit> = database.observeChanges().onStart { database.initialize() }

    private fun observe(read: suspend () -> List<SqliteManagedWorkStore.Record>): Flow<List<WorkInfo>> = changes()
        .map { read().map { it.info } }
        .distinctUntilChanged { previous, next ->
            previous.size == next.size && previous.indices.all { sameInfo(previous[it], next[it]) }
        }
}

private fun sameInfo(previous: WorkInfo?, next: WorkInfo?): Boolean {
    if (previous === next) return true
    if (previous == null || next == null) return false
    return previous.id == next.id &&
        previous.state == next.state &&
        previous.tags == next.tags &&
        previous.progress.progress == next.progress.progress &&
        previous.progress.data.hasSameValues(next.progress.data) &&
        previous.outputData.hasSameValues(next.outputData) &&
        previous.runAttemptCount == next.runAttemptCount
}
