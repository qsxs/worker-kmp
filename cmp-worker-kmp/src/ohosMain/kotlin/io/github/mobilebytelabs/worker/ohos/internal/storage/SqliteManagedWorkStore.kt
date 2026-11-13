package io.github.mobilebytelabs.worker.ohos.internal.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicCallback
import io.github.mobilebytelabs.worker.ohos.internal.hasSameValues
import io.github.mobilebytelabs.worker.ohos.internal.initialDelay
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

internal class SqliteManagedWorkStore(private val workStore: SqliteWorkStore) {
    data class Intent(val id: Uuid, val nameLeaseId: Uuid?, val epoch: Long)

    data class Record(
        val intent: Intent,
        val name: String?,
        val request: WorkRequest,
        val systemWorkId: Int?,
        val firstEligibleAtMillis: Long?,
        val info: WorkInfo,
        val owner: Uuid?,
        val occurrenceId: Uuid?,
        val occurrenceGeneration: Long?,
        val stopRequested: Boolean,
        val retryEligibleAtMillis: Long?,
    )

    data class Publication(val record: Record, val displaced: Record?, val created: Boolean)

    data class PeriodicTiming(val registration: PeriodicScheduleSpec, val firstEligibleAtMillis: Long)

    data class Execution(
        val intent: Intent,
        val owner: Uuid,
        val request: WorkRequest,
        val attempt: Int,
        val occurrenceId: Uuid? = null,
        val generation: Long = 0L,
    )

    sealed interface PeriodicClaim {
        data class Started(val execution: Execution) : PeriodicClaim

        data class Recovered(val state: WorkInfo.State) : PeriodicClaim
    }

    /** state 为 null 表示本次物理尝试已退役，周期逻辑等待下次窗口。 */
    data class PeriodicSettlement(val state: WorkInfo.State?)

    private val registrations = SqlitePeriodicRegistrationStore(workStore)

    suspend fun publishOneTime(
        name: String?,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
        validate: () -> Unit = {},
    ): Publication = workStore.mutate {
        val previous = name?.let { readCurrent(it) }
        if (policy == ExistingWorkPolicy.KEEP && previous != null && !previous.info.isFinished) {
            return@mutate Publication(previous, null, false)
        }
        sameRequest(name, request)?.let { return@mutate Publication(it, null, false) }
        validate()
        publish(name, previous, request, timing = null)
    }

    suspend fun publishPeriodic(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
        firstSystemWorkId: Int,
        timing: (PeriodicWorkRequest) -> PeriodicTiming,
    ): Publication = workStore.mutate {
        val previous = readCurrent(name)
        if (previous != null && !previous.info.isFinished) {
            when (policy) {
                ExistingPeriodicWorkPolicy.KEEP -> return@mutate Publication(previous, null, false)

                ExistingPeriodicWorkPolicy.UPDATE -> {
                    val current =
                        previous.request as? PeriodicWorkRequest
                            ?: throw WorkEnqueueException("Cannot update one-time work as periodic work.")
                    if (current.matches(request)) return@mutate Publication(previous, null, false)
                    if (current.repeatInterval != request.repeatInterval ||
                        current.constraints != request.constraints ||
                        current.initialDelay != request.initialDelay ||
                        current.flexTimeInterval != request.flexTimeInterval
                    ) {
                        throw WorkEnqueueException("OHOS cannot update an active periodic schedule in place.")
                    }
                    timing(request)
                    val updated = request.copy(id = current.id)
                    prepare("UPDATE managed_work SET request_bytes = ? WHERE id = ?").use { statement ->
                        statement.bindBlob(1, DurableWorkPayloadCodec.encodeRequest(updated))
                        statement.bindText(2, current.id.toString())
                        statement.step()
                    }
                    return@mutate Publication(checkNotNull(readId(current.id)), null, false)
                }

                ExistingPeriodicWorkPolicy.REPLACE -> Unit
            }
        }
        sameRequest(name, request)?.let { return@mutate Publication(it, null, false) }
        publish(name, previous, request, timing(request), firstSystemWorkId)
    }

    suspend fun read(id: Uuid): Record? = workStore.readInSchema { readId(id) }

    suspend fun current(name: String): Record? = workStore.readInSchema { readCurrent(name) }

    suspend fun named(name: String): List<Record> = workStore.readInSchema {
        readRecords("WHERE w.unique_name = ?", name)
    }

    suspend fun tagged(tag: String): List<Record> = workStore.readInSchema {
        readRecords().filter { tag in it.info.tags }
    }

    suspend fun unfinishedOneTime(): List<Record> = workStore.readInSchema {
        readRecords(
            "WHERE w.system_work_id IS NULL AND (w.state IN ('ENQUEUED', 'RUNNING') OR w.owner IS NOT NULL)",
        )
    }

    suspend fun periodicOwners(): List<Record> = workStore.readInSchema {
        readRecords("WHERE w.system_work_id IS NOT NULL AND w.occurrence_id IS NOT NULL")
    }

    /** 调用方须已无等待地取得稳定名字租约，并核对扫描时捕获的旧 owner。 */
    suspend fun reapAbandonedPeriodic(expected: Record): Boolean = workStore.mutate {
        val record = readId(expected.intent.id) ?: return@mutate false
        if (record.intent != expected.intent ||
            record.owner != expected.owner ||
            record.occurrenceId != expected.occurrenceId
        ) {
            return@mutate false
        }
        if (record.occurrenceId == null) return@mutate false
        settlePeriodic(record, WorkInfo.State.FAILED, output = null)
        true
    }

    suspend fun cancelId(id: Uuid): List<Record> = workStore.mutate { cancelRecords(listOfNotNull(readId(id))) }

    suspend fun cancelName(name: String): List<Record> = workStore.mutate {
        cancelRecords(listOfNotNull(readCurrent(name)))
    }

    suspend fun cancelTag(tag: String): List<Record> = workStore.mutate {
        cancelRecords(
            readRecords().filter {
                tag in
                    it.info.tags
            },
        )
    }

    /** 启动时也回查已确认的活动登记，系统登记可能在进程外丢失。 */
    suspend fun recoverySystemWorkIds(): List<Int> =
        workStore.readInSchema { registrations.readPendingIds(this, includeConfirmedActive = true) }

    suspend fun pendingRetiredSystemWorkIds(name: String): List<Int> =
        workStore.readInSchema { registrations.readPendingIds(this, retiredName = name) }

    /** 获取稳定的租约身份后，调用方须再次校验本次接纳时的逻辑 ID 和意图代次。 */
    suspend fun periodicIntent(callback: OhosPeriodicCallback): Intent? = workStore.readInSchema {
        val record = readId(callback.requestId) ?: return@readInSchema null
        record.intent.takeIf {
            record.systemWorkId == callback.workId && canRun(record) &&
                registrations.isCurrentConfirmed(this, callback)
        }
    }

    /** 调用方持有稳定名字租约，直到 Worker 和所有子协程退出。 */
    suspend fun claimPeriodic(
        callback: OhosPeriodicCallback,
        intent: Intent,
        owner: Uuid,
        nowMillis: Long,
    ): PeriodicClaim? = workStore.mutate {
        val record = readCurrentIntent(intent) ?: return@mutate null
        if (record.systemWorkId != callback.workId ||
            nowMillis < checkNotNull(record.firstEligibleAtMillis) ||
            nowMillis < (record.retryEligibleAtMillis ?: 0L) ||
            !registrations.isCurrentConfirmed(this, callback)
        ) {
            return@mutate null
        }
        recoverNameOwner(record)?.let { recovery ->
            return@mutate PeriodicClaim.Recovered(checkNotNull(recovery.state))
        }
        val current = checkNotNull(readId(intent.id))
        val request = current.request as PeriodicWorkRequest
        val occurrenceId = Uuid.random()
        claimLogical(current, owner, occurrenceId, callback.generation)
        PeriodicClaim.Started(
            Execution(intent, owner, request, current.info.runAttemptCount, occurrenceId, callback.generation),
        )
    }

    /** 一次性 Worker 的名字租约在用户代码执行前取得，回调只可认领当时接纳的意图。 */
    suspend fun claimOneTime(intent: Intent, owner: Uuid): Execution? = workStore.mutate {
        val record = readCurrentIntent(intent) ?: return@mutate null
        if (record.request !is OneTimeWorkRequest || record.owner != null) return@mutate null
        recoverNameOwner(record)
        claimLogical(record, owner, occurrenceId = null, generation = null)
        Execution(intent, owner, record.request, record.info.runAttemptCount)
    }

    /** 持有一次性任务的独立生存租约时，结算当前退出或前一进程遗留的工作。 */
    suspend fun settleUnfinishedOneTime(intent: Intent, exitState: WorkInfo.State): Boolean = workStore.mutate {
        require(exitState == WorkInfo.State.FAILED || exitState == WorkInfo.State.CANCELLED)
        val record = readId(intent.id) ?: return@mutate false
        if (record.intent != intent ||
            record.request !is OneTimeWorkRequest ||
            (record.info.isFinished && record.owner == null)
        ) {
            return@mutate false
        }
        val state = if (record.info.isFinished) record.info.state else exitState
        writeSettlement(record, state, record.info.runAttemptCount, output = null)
        true
    }

    suspend fun setProgress(
        execution: Execution,
        progress: WorkProgress,
        isExecutionActive: () -> Boolean = { true },
    ): Boolean = workStore.mutate {
        val record = readOwned(execution) ?: return@mutate false
        if (!canRun(record) || record.stopRequested || !isExecutionActive()) return@mutate false
        prepare("UPDATE managed_work SET progress_bytes = ?, progress_percent = ? WHERE id = ?").use { statement ->
            statement.bindBlob(1, DurableWorkPayloadCodec.encodeResult(progress.data))
            statement.bindLong(2, progress.progress.toLong())
            statement.bindText(3, execution.intent.id.toString())
            statement.step()
        }
        true
    }

    suspend fun canRun(execution: Execution): Boolean = workStore.readInSchema {
        val record = readOwned(execution) ?: return@readInSchema false
        canRun(record) && !record.stopRequested
    }

    suspend fun awaitCancellation(execution: Execution) {
        workStore.observeChanges().first { !canRun(execution) }
    }

    suspend fun reportOneTimeAttempt(execution: Execution, state: WorkInfo.State, attempt: Int): Boolean =
        workStore.mutate {
            require(state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING)
            val record = readOwned(execution) ?: return@mutate false
            if (!canRun(record) || record.request !is OneTimeWorkRequest) return@mutate false
            prepare(
                "UPDATE managed_work SET state = ?, attempt = ?, progress_bytes = NULL, progress_percent = 0 WHERE id = ?",
            ).use { statement ->
                statement.bindText(1, state.name)
                statement.bindLong(2, attempt.toLong())
                statement.bindText(3, execution.intent.id.toString())
                statement.step()
            }
            true
        }

    suspend fun finishOneTime(execution: Execution, state: WorkInfo.State, output: WorkData?): WorkInfo.State? =
        workStore.mutate {
            require(state.isFinished)
            val record = readOwned(execution) ?: return@mutate null
            check(record.request is OneTimeWorkRequest)
            val resolved = if (canRun(record)) state else WorkInfo.State.CANCELLED
            writeSettlement(record, resolved, record.info.runAttemptCount, output.takeIf { resolved == state })
            resolved
        }

    suspend fun finishPeriodic(
        execution: Execution,
        state: WorkInfo.State?,
        output: WorkData?,
        retryEligibleAtMillis: Long? = null,
    ): PeriodicSettlement? = workStore.mutate {
        val record = readOwned(execution) ?: return@mutate null
        settlePeriodic(record, state, output, retryEligibleAtMillis)
    }

    /** 停止只影响所接纳的物理 occurrence，周期逻辑继续等待未来窗口。 */
    suspend fun stopPeriodic(callback: OhosPeriodicCallback, occurrenceId: Uuid): Boolean = workStore.mutate {
        val record = readId(callback.requestId) ?: return@mutate false
        if (record.systemWorkId != callback.workId ||
            record.occurrenceId != occurrenceId ||
            record.occurrenceGeneration != callback.generation
        ) {
            return@mutate false
        }
        if (record.stopRequested) return@mutate true
        prepare("UPDATE managed_work SET stop_requested = 1 WHERE id = ?").use { statement ->
            statement.bindText(1, record.intent.id.toString())
            statement.step()
        }
        true
    }

    private fun SQLiteConnection.publish(
        name: String?,
        previous: Record?,
        request: WorkRequest,
        timing: PeriodicTiming?,
        firstSystemWorkId: Int = 0,
    ): Publication {
        previous?.let { cancelRecords(listOf(it)) }
        val systemWorkId =
            timing?.let {
                require(timing.registration.requestId == request.id)
                registrations.allocateSystemWorkId(this, firstSystemWorkId).also { id ->
                    registrations.setDesiredInTransaction(this, id, timing.registration)
                }
            }
        val epoch = if (name == null) 0L else advanceName(name, previous?.intent, request.id)
        prepare(
            """
            INSERT INTO managed_work (id, unique_name, intent_epoch, request_bytes, system_work_id, first_eligible_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, request.id.toString())
            statement.bindOptionalText(2, name)
            statement.bindLong(3, epoch)
            statement.bindBlob(4, DurableWorkPayloadCodec.encodeRequest(request))
            if (systemWorkId == null) statement.bindNull(5) else statement.bindLong(5, systemWorkId.toLong())
            if (timing == null) statement.bindNull(6) else statement.bindLong(6, timing.firstEligibleAtMillis)
            statement.step()
        }
        return Publication(checkNotNull(readId(request.id)), previous, true)
    }

    private fun SQLiteConnection.advanceName(name: String, previous: Intent?, nextId: Uuid): Long {
        val lastEpoch = previous?.epoch ?: 0L
        check(lastEpoch < Long.MAX_VALUE) { "Work name intent epoch exhausted" }
        val epoch = lastEpoch + 1L
        prepare(
            """
            INSERT INTO managed_work_names (name, lease_id, current_id, intent_epoch) VALUES (?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET current_id = excluded.current_id, intent_epoch = excluded.intent_epoch
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, name)
            statement.bindText(2, (previous?.nameLeaseId ?: Uuid.random()).toString())
            statement.bindText(3, nextId.toString())
            statement.bindLong(4, epoch)
            statement.step()
        }
        return epoch
    }

    private fun SQLiteConnection.sameRequest(name: String?, request: WorkRequest): Record? = readId(request.id)?.also {
        require(it.name == name && canRun(it) && it.request.matches(request)) {
            "Work ID is already bound to another or retired request"
        }
    }

    private fun WorkRequest.matches(other: WorkRequest): Boolean {
        if (workerClass != other.workerClass ||
            !inputData.hasSameValues(other.inputData) ||
            retryConfig != other.retryConfig ||
            constraints != other.constraints ||
            tags != other.tags ||
            initialDelay() != other.initialDelay()
        ) {
            return false
        }
        return when (this) {
            is OneTimeWorkRequest -> other is OneTimeWorkRequest && expeditedPolicy == other.expeditedPolicy

            is PeriodicWorkRequest ->
                other is PeriodicWorkRequest && repeatInterval == other.repeatInterval &&
                    flexTimeInterval == other.flexTimeInterval && quickRefresh == other.quickRefresh
        }
    }

    private fun SQLiteConnection.cancelRecords(records: List<Record>): List<Record> {
        records.forEach { record ->
            if (!record.info.isFinished) {
                prepare(
                    "UPDATE managed_work SET state = 'CANCELLED', progress_bytes = NULL, progress_percent = 0, output_bytes = NULL WHERE id = ?",
                ).use { statement ->
                    statement.bindText(1, record.intent.id.toString())
                    statement.step()
                }
                record.systemWorkId?.let { registrations.setDesiredInTransaction(this, it, spec = null) }
            }
        }
        return records
    }

    private fun SQLiteConnection.readCurrentIntent(intent: Intent): Record? =
        readId(intent.id)?.takeIf { it.intent == intent && canRun(it) }

    private fun SQLiteConnection.readOwned(execution: Execution): Record? = readId(execution.intent.id)?.takeIf {
        it.intent == execution.intent && it.owner == execution.owner && it.occurrenceId == execution.occurrenceId &&
            (execution.occurrenceId == null || it.occurrenceGeneration == execution.generation)
    }

    private fun SQLiteConnection.canRun(record: Record): Boolean =
        !record.info.isFinished && (record.name == null || readCurrent(record.name)?.intent == record.intent)

    /** 能拿到名字租约意味着上一持有者已退出。只结算其记录，绝不重新执行它。 */
    private fun SQLiteConnection.recoverNameOwner(record: Record): PeriodicSettlement? {
        val name = record.name ?: return null
        val ownerId =
            prepare("SELECT owner_work_id FROM managed_work_names WHERE name = ?").use { statement ->
                statement.bindText(1, name)
                check(statement.step())
                if (statement.isNull(0)) null else Uuid.parse(statement.getText(0))
            } ?: return null
        val previous = checkNotNull(readId(ownerId))
        val occurrenceId = previous.occurrenceId
        return if (occurrenceId == null) {
            val state = if (canRun(previous)) WorkInfo.State.FAILED else WorkInfo.State.CANCELLED
            writeSettlement(previous, state, previous.info.runAttemptCount, output = null)
            null
        } else {
            val settled = settlePeriodic(previous, WorkInfo.State.FAILED, output = null)
            // 不重放本任务中断的执行；若中断的是已替换的任务，新任务可继续认领。
            settled.takeIf { previous.intent == record.intent }
        }
    }

    private fun SQLiteConnection.claimLogical(record: Record, owner: Uuid, occurrenceId: Uuid?, generation: Long?) {
        prepare(
            """
            UPDATE managed_work SET state = ?, owner = ?, occurrence_id = ?, occurrence_generation = ?,
                stop_requested = 0, progress_bytes = NULL, progress_percent = 0, attempt = ? WHERE id = ?
            """.trimIndent(),
        ).use {
            it.bindText(1, if (occurrenceId == null) WorkInfo.State.ENQUEUED.name else WorkInfo.State.RUNNING.name)
            it.bindText(2, owner.toString())
            it.bindOptionalText(3, occurrenceId?.toString())
            if (generation == null) it.bindNull(4) else it.bindLong(4, generation)
            it.bindLong(5, record.info.runAttemptCount.toLong() + if (occurrenceId == null) 0 else 1)
            it.bindText(6, record.intent.id.toString())
            it.step()
        }
        record.name?.let { name ->
            prepare("UPDATE managed_work_names SET owner_work_id = ?, owner = ? WHERE name = ? AND owner IS NULL").use {
                it.bindText(1, record.intent.id.toString())
                it.bindText(2, owner.toString())
                it.bindText(3, name)
                it.step()
            }
            check(
                prepare("SELECT changes()").use {
                    it.step() && it.getLong(0) == 1L
                },
            ) { "Work name already has an owner" }
        }
    }

    private fun SQLiteConnection.settlePeriodic(
        record: Record,
        state: WorkInfo.State?,
        output: WorkData?,
        retryEligibleAtMillis: Long? = null,
    ): PeriodicSettlement {
        val active = canRun(record)
        val resolved = if (!active || record.stopRequested) WorkInfo.State.CANCELLED else state
        val nextAttempt =
            if (resolved == null) {
                record.info.runAttemptCount
            } else {
                0
            }
        val nextState = if (active) WorkInfo.State.ENQUEUED else WorkInfo.State.CANCELLED
        val nextOutput =
            when (resolved) {
                WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> output
                else -> record.info.outputData
            }
        writeSettlement(
            record,
            nextState,
            nextAttempt,
            nextOutput.takeIf { active },
            retryEligibleAtMillis.takeIf { resolved == null },
        )
        return PeriodicSettlement(resolved)
    }

    private fun SQLiteConnection.writeSettlement(
        record: Record,
        state: WorkInfo.State,
        attempt: Int,
        output: WorkData?,
        retryEligibleAtMillis: Long? = null,
    ) {
        prepare(
            """
            UPDATE managed_work SET state = ?, attempt = ?, owner = NULL, occurrence_id = NULL, occurrence_generation = NULL,
                stop_requested = 0, progress_bytes = NULL, progress_percent = 0, output_bytes = ?,
                retry_eligible_at = ? WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, state.name)
            statement.bindLong(2, attempt.toLong())
            if (output ==
                null
            ) {
                statement.bindNull(3)
            } else {
                statement.bindBlob(3, DurableWorkPayloadCodec.encodeResult(output))
            }
            if (retryEligibleAtMillis == null) statement.bindNull(4) else statement.bindLong(4, retryEligibleAtMillis)
            statement.bindText(5, record.intent.id.toString())
            statement.step()
        }
        val owner = record.owner ?: return
        prepare(
            "UPDATE managed_work_names SET owner_work_id = NULL, owner = NULL WHERE owner_work_id = ? AND owner = ?",
        ).use {
            it.bindText(1, record.intent.id.toString())
            it.bindText(2, owner.toString())
            it.step()
        }
    }

    private fun SQLiteConnection.readId(id: Uuid): Record? = readRecords("WHERE w.id = ?", id.toString()).singleOrNull()

    private fun SQLiteConnection.readCurrent(name: String): Record? =
        readRecords("WHERE n.name = ? AND n.current_id = w.id AND n.intent_epoch = w.intent_epoch", name).singleOrNull()

    private fun SQLiteConnection.readRecords(predicate: String = "", argument: String? = null): List<Record> = prepare(
        """
            SELECT w.id, n.lease_id, w.intent_epoch, w.unique_name, w.request_bytes, w.system_work_id,
                w.first_eligible_at, w.state, w.progress_bytes, w.output_bytes, w.attempt, w.owner, w.occurrence_id,
                w.occurrence_generation, w.stop_requested, w.progress_percent, w.retry_eligible_at
            FROM managed_work w LEFT JOIN managed_work_names n ON n.name = w.unique_name
            $predicate ORDER BY w.rowid
        """.trimIndent(),
    ).use { statement ->
        argument?.let { statement.bindText(1, it) }
        buildList {
            while (statement.step()) {
                val id = Uuid.parse(statement.getText(0))
                val request = DurableWorkPayloadCodec.decodeRequest(statement.getBlob(4))
                check(request.id == id) { "Managed request ID does not match the stored record" }
                add(
                    Record(
                        intent = Intent(id, statement.uuidOrNull(1), statement.getLong(2)),
                        name = if (statement.isNull(3)) null else statement.getText(3),
                        request = request,
                        systemWorkId = if (statement.isNull(5)) null else statement.getLong(5).toInt(),
                        firstEligibleAtMillis = if (statement.isNull(6)) null else statement.getLong(6),
                        info =
                        WorkInfo(
                            id = id,
                            state = WorkInfo.State.valueOf(statement.getText(7)),
                            tags = request.tags,
                            progress = WorkProgress(statement.getLong(15).toInt(), statement.resultOrEmpty(8)),
                            outputData = statement.resultOrEmpty(9),
                            runAttemptCount = statement.getLong(10).toInt(),
                        ),
                        owner = statement.uuidOrNull(11),
                        occurrenceId = statement.uuidOrNull(12),
                        occurrenceGeneration = if (statement.isNull(13)) null else statement.getLong(13),
                        stopRequested = statement.getLong(14) == 1L,
                        retryEligibleAtMillis = if (statement.isNull(16)) null else statement.getLong(16),
                    ),
                )
            }
        }
    }
}

private fun SQLiteStatement.uuidOrNull(index: Int): Uuid? = if (isNull(index)) null else Uuid.parse(getText(index))

private fun SQLiteStatement.resultOrEmpty(index: Int): WorkData =
    if (isNull(index)) WorkData.EMPTY else DurableWorkPayloadCodec.decodeResult(getBlob(index))

private fun SQLiteStatement.bindOptionalText(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

internal fun SQLiteConnection.createManagedWorkTables() {
    execSQL(
        """
        CREATE TABLE managed_work_names (
            name TEXT PRIMARY KEY NOT NULL,
            lease_id TEXT NOT NULL UNIQUE,
            current_id TEXT NOT NULL UNIQUE,
            intent_epoch INTEGER NOT NULL CHECK (intent_epoch > 0),
            owner_work_id TEXT,
            owner TEXT,
            CHECK ((owner_work_id IS NULL) = (owner IS NULL))
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE TABLE managed_work (
            id TEXT PRIMARY KEY NOT NULL,
            unique_name TEXT REFERENCES managed_work_names(name),
            intent_epoch INTEGER NOT NULL CHECK (intent_epoch >= 0),
            request_bytes BLOB NOT NULL,
            system_work_id INTEGER UNIQUE CHECK (system_work_id BETWEEN 0 AND 2147483647),
            first_eligible_at INTEGER CHECK (first_eligible_at >= 0),
            retry_eligible_at INTEGER CHECK (retry_eligible_at >= 0),
            state TEXT NOT NULL DEFAULT 'ENQUEUED'
                CHECK (state IN ('ENQUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
            progress_bytes BLOB,
            progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
            output_bytes BLOB,
            attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 2147483647),
            owner TEXT,
            occurrence_id TEXT UNIQUE,
            occurrence_generation INTEGER CHECK (occurrence_generation > 0),
            stop_requested INTEGER NOT NULL DEFAULT 0 CHECK (stop_requested IN (0, 1)),
            CHECK ((unique_name IS NULL) = (intent_epoch = 0)),
            CHECK ((system_work_id IS NULL) = (first_eligible_at IS NULL)),
            CHECK (system_work_id IS NULL OR unique_name IS NOT NULL),
            CHECK (state <> 'RUNNING' OR owner IS NOT NULL),
            CHECK (occurrence_id IS NULL OR (owner IS NOT NULL AND system_work_id IS NOT NULL)),
            CHECK ((occurrence_id IS NULL) = (occurrence_generation IS NULL)),
            CHECK (stop_requested = 0 OR occurrence_id IS NOT NULL)
        )
        """.trimIndent(),
    )
    execSQL("CREATE INDEX managed_work_by_name ON managed_work(unique_name)")
}
