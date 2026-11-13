package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.ohos.OhosWorkManagerConfiguration
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqlitePeriodicRegistrationStore
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 先提交持久意图，再释放命令锁并与系统对账。 */
internal class OhosWorkCommandCoordinator(
    scope: CoroutineScope,
    registry: WorkerRegistry,
    private val configuration: OhosWorkManagerConfiguration,
    private val database: SqliteWorkStore,
    private val store: SqliteManagedWorkStore,
) {
    private val commands = Mutex()
    private val commandLease = OhosExecutionLease(configuration.directory, "command-leases")
    private val names = nameExecutionLeases(configuration.directory)
    private val runner = OhosOneTimeOwnerRunner(scope, registry, store, configuration.directory)
    private val workerNames = registry.registeredClassNames()
    private val scheduler =
        OhosPeriodicRegistrationCoordinator(
            configuration.directory,
            SqlitePeriodicRegistrationStore(database),
            configuration.scheduler,
        )

    suspend fun enqueueOneTime(name: String?, policy: ExistingWorkPolicy, request: OneTimeWorkRequest): Uuid {
        if (policy != ExistingWorkPolicy.KEEP && policy != ExistingWorkPolicy.REPLACE) {
            throw WorkEnqueueException("OHOS unique work supports KEEP and REPLACE; work chains are unavailable.")
        }
        val (publication, ids) =
            locked {
                require(name == null || name.isNotBlank()) { "Unique work name must not be blank" }
                runner.reapAbandoned(name?.let { store.current(it) } ?: store.read(request.id))
                val kept = if (policy == ExistingWorkPolicy.KEEP && name != null) {
                    store.current(name)?.takeUnless { it.info.isFinished }
                } else {
                    null
                }
                val result = if (kept == null) {
                    runner.publish(request) { store.publishOneTime(name, policy, request) { runner.validate(request) } }
                } else {
                    SqliteManagedWorkStore.Publication(kept, displaced = null, created = false)
                }
                result to captureReconciliation(result, name.takeIf { policy == ExistingWorkPolicy.REPLACE })
            }
        reconcile(ids)
        return publication.record.intent.id
    }

    suspend fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest): Uuid {
        val (publication, ids) =
            locked {
                require(name.isNotBlank()) { "Unique work name must not be blank" }
                runner.reapAbandoned(store.current(name))
                val result = store.publishPeriodic(name, policy, request, configuration.firstSystemWorkId) {
                    if (it.workerClass !in workerNames) {
                        throw WorkEnqueueException("No worker registered for '${it.workerClass}'.")
                    }
                    it.toOhosPeriodicTiming(
                        configuration.bundleName,
                        configuration.abilityName,
                        Clock.System.now().toEpochMilliseconds(),
                    )
                }
                result to captureReconciliation(result, name.takeIf { policy == ExistingPeriodicWorkPolicy.REPLACE })
            }
        reconcile(ids)
        return publication.record.intent.id
    }

    suspend fun cancel(
        name: String? = null,
        capture: suspend SqliteManagedWorkStore.() -> List<SqliteManagedWorkStore.Record>,
    ) {
        val ids = locked {
            require(name == null || name.isNotBlank()) { "Unique work name must not be blank" }
            store.capture().mapNotNull { it.systemWorkId } + retiredIds(name)
        }
        reconcile(ids)
    }

    suspend fun recoverPendingWork() {
        val ids = locked {
            store.unfinishedOneTime().forEach { runner.reapAbandoned(it) }
            store.recoverySystemWorkIds()
        }
        for (record in store.periodicOwners()) {
            names.withLock(checkNotNull(record.intent.nameLeaseId), 0L) {
                store.reapAbandonedPeriodic(record)
            }
        }
        reconcile(ids)
    }

    private suspend fun captureReconciliation(
        publication: SqliteManagedWorkStore.Publication,
        retiredName: String?,
    ): List<Int> =
        retiredIds(retiredName) + listOfNotNull(publication.displaced?.systemWorkId, publication.record.systemWorkId)

    private suspend fun retiredIds(name: String?): List<Int> = if (name ==
        null
    ) {
        emptyList()
    } else {
        store.pendingRetiredSystemWorkIds(name)
    }

    private suspend fun <T : Any> locked(action: suspend () -> T): T = commands.withLock {
        checkNotNull(
            commandLease.withLockUntil(Uuid.NIL, 0L, shouldWait = { true }) {
                database.initialize()
                action()
            },
        )
    }

    private suspend fun reconcile(ids: List<Int>) {
        var failure: Exception? = null
        for (id in ids.distinct()) {
            try {
                scheduler.reconcile(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (failure == null) {
                    failure = error
                } else if (failure !== error) {
                    failure.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }
}
