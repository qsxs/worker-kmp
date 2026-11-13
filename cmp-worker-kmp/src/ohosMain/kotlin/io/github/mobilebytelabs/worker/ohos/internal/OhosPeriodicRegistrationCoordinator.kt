package io.github.mobilebytelabs.worker.ohos.internal

import io.github.mobilebytelabs.worker.ohos.OhosWorkScheduler
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqlitePeriodicRegistrationStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.uuid.Uuid

/** 系统登记按 workId 串行，系统调用与数据库写事务分开。 */
internal class OhosPeriodicRegistrationCoordinator(
    directory: Path,
    private val store: SqlitePeriodicRegistrationStore,
    private val scheduler: OhosWorkScheduler,
) {
    private val leases = OhosExecutionLease(directory, "registration-leases")

    suspend fun reconcile(workId: Int) {
        require(workId >= 0) { "Expected a nonnegative system work ID" }
        while (leases.withLock(Uuid.NIL, workId.toLong()) { reconcileLocked(workId) } == null) {
            delay(25L)
        }
    }

    private suspend fun reconcileLocked(workId: Int) {
        while (true) {
            val snapshot = store.readForReconciliation(workId) ?: return
            val desired = snapshot.desired
            val issued = snapshot.issued?.registration
            if (issued != null && issued != desired) {
                finishIssuedOperation { scheduler.cancel(issued) }
                check(store.clearIssued(issued)) { "Issued periodic registration changed during reconciliation" }
                continue
            }
            if (desired == null) return
            if (issued == null && !store.reserveIssued(desired)) continue

            finishIssuedOperation { scheduler.ensure(desired) }
            if (store.acknowledge(desired)) return
        }
    }
}

private suspend fun finishIssuedOperation(operation: suspend () -> Unit) {
    val caller = currentCoroutineContext()
    withContext(NonCancellable) {
        caller.ensureActive()
        // 已发出的操作结束前不能释放登记锁。
        operation()
    }
    caller.ensureActive()
}
