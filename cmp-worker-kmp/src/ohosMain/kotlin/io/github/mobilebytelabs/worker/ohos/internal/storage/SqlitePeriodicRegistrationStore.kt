package io.github.mobilebytelabs.worker.ohos.internal.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicCallback
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicRegistration
import kotlin.uuid.Uuid

internal data class PeriodicScheduleSpec(
    val bundleName: String,
    val abilityName: String,
    val requestId: Uuid,
    val intervalMillis: Int,
    val requiresNetwork: Boolean,
) {
    init {
        require(bundleName.isNotBlank() && abilityName.isNotBlank()) { "Periodic work requires a bundle and ability" }
        require(intervalMillis > 0) { "Periodic work requires a positive interval" }
    }
}

/** 分别保存期望状态和可能已写入系统的精确登记。停用时保留代次与待清理的登记。 */
internal class SqlitePeriodicRegistrationStore(private val workStore: SqliteWorkStore) {
    data class Snapshot(val desiredGeneration: Long, val desired: OhosPeriodicRegistration?, val issued: Issued?)

    data class Issued(val registration: OhosPeriodicRegistration, val confirmed: Boolean)

    internal fun setDesiredInTransaction(
        connection: SQLiteConnection,
        workId: Int,
        spec: PeriodicScheduleSpec?,
    ): Long = with(connection) {
        val snapshot = readSnapshot(workId)
        if (snapshot != null && snapshot.desired?.toSpec() == spec) return@with snapshot.desiredGeneration
        val previous = snapshot?.desiredGeneration ?: 0L
        check(previous < Long.MAX_VALUE) { "Periodic registration generation exhausted" }
        val next = previous + 1L
        writeRegistration(workId, DESIRED, next, spec, confirmed = false)
        next
    }

    internal fun allocateSystemWorkId(connection: SQLiteConnection, firstSystemWorkId: Int): Int =
        connection.prepare("SELECT COALESCE(MAX(work_id), -1) FROM periodic_registrations").use { statement ->
            check(statement.step()) { "Expected periodic work ID high-water mark" }
            val previous = statement.getLong(0)
            check(previous < Int.MAX_VALUE) { "System work ID space exhausted" }
            maxOf(firstSystemWorkId.toLong(), previous + 1L).toInt()
        }

    internal fun readPendingIds(
        connection: SQLiteConnection,
        retiredName: String? = null,
        includeConfirmedActive: Boolean = false,
    ): List<Int> = connection
        .prepare(
            """
                SELECT d.work_id
                FROM managed_work w
                JOIN periodic_registrations d ON d.work_id = w.system_work_id AND d.role = $DESIRED
                LEFT JOIN periodic_registrations i ON i.work_id = d.work_id AND i.role = $ISSUED
                WHERE (d.request_id IS NULL AND i.work_id IS NOT NULL AND (?1 IS NULL OR w.unique_name = ?1))
                    OR (?1 IS NULL AND d.request_id IS NOT NULL AND (?2 = 1 OR COALESCE(i.confirmed, 0) = 0 OR i.generation <> d.generation))
                ORDER BY d.work_id
            """.trimIndent(),
        ).use { statement ->
            if (retiredName == null) statement.bindNull(1) else statement.bindText(1, retiredName)
            statement.bindLong(2, if (includeConfirmedActive) 1L else 0L)
            buildList {
                while (statement.step()) add(statement.getLong(0).toInt())
            }
        }

    /** 对账入口在系统 ID 租约内读这一份快照，未登记的 ID 无需发出系统调用。 */
    suspend fun readForReconciliation(workId: Int): Snapshot? = workStore.readInSchema { readSnapshot(workId) }

    /** 调用 SDK 前先记录这份精确登记。即使调用方中断，也可用它核对和清理。 */
    suspend fun reserveIssued(registration: OhosPeriodicRegistration): Boolean = workStore.mutate {
        val snapshot = readSnapshot(registration.workId) ?: return@mutate false
        if (snapshot.desired != registration || snapshot.issued != null) return@mutate false
        writeRegistration(
            registration.workId,
            ISSUED,
            registration.generation,
            registration.toSpec(),
            confirmed = false,
        )
        true
    }

    /** 调用方已向系统核实此登记不存在，才可移除对应记录。 */
    suspend fun clearIssued(registration: OhosPeriodicRegistration): Boolean = workStore.mutate {
        if (readSnapshot(registration.workId)?.issued?.registration != registration) return@mutate false
        prepare("DELETE FROM periodic_registrations WHERE work_id = ? AND role = $ISSUED").use { statement ->
            statement.bindLong(1, registration.workId.toLong())
            statement.step()
        }
        true
    }

    /** 系统回查完成后，只确认仍符合当前期望的原登记。 */
    suspend fun acknowledge(registration: OhosPeriodicRegistration): Boolean = workStore.mutate {
        val snapshot = readSnapshot(registration.workId) ?: return@mutate false
        val issued = snapshot.issued ?: return@mutate false
        if (snapshot.desired != registration || issued.registration != registration) return@mutate false
        if (!issued.confirmed) {
            writeRegistration(
                registration.workId,
                ISSUED,
                registration.generation,
                registration.toSpec(),
                confirmed = true,
            )
        }
        true
    }

    fun isCurrentConfirmed(connection: SQLiteConnection, callback: OhosPeriodicCallback): Boolean = with(connection) {
        val snapshot = readSnapshot(callback.workId) ?: return@with false
        val desired = snapshot.desired ?: return@with false
        snapshot.issued?.let { it.confirmed && it.registration == desired } == true &&
            desired.generation == callback.generation &&
            desired.requestId == callback.requestId &&
            desired.bundleName == callback.bundleName &&
            desired.abilityName == callback.abilityName
    }

    private fun SQLiteConnection.readSnapshot(workId: Int): Snapshot? = prepare(
        """
            SELECT role, generation, bundle_name, ability_name, request_id, interval_millis, requires_network, confirmed
            FROM periodic_registrations r WHERE r.work_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.bindLong(1, workId.toLong())
        var desiredGeneration: Long? = null
        var desired: OhosPeriodicRegistration? = null
        var issued: Issued? = null
        while (statement.step()) {
            val generation = statement.getLong(1)
            val registration =
                if (statement.isNull(4)) {
                    null
                } else {
                    OhosPeriodicRegistration(
                        workId = workId,
                        bundleName = statement.getText(2),
                        abilityName = statement.getText(3),
                        requestId = Uuid.parse(statement.getText(4)),
                        generation = generation,
                        repeatIntervalMillis = statement.getLong(5).toInt(),
                        requiresNetwork = statement.getLong(6) == 1L,
                    )
                }
            if (statement.getLong(0) == DESIRED) {
                desiredGeneration = generation
                desired = registration
            } else {
                issued = Issued(checkNotNull(registration), statement.getLong(7) == 1L)
            }
        }
        val generation = desiredGeneration
        if (generation == null) {
            check(issued == null) { "Issued periodic registration has no desired state" }
            null
        } else {
            Snapshot(generation, desired, issued)
        }
    }

    private fun SQLiteConnection.writeRegistration(
        workId: Int,
        role: Long,
        generation: Long,
        spec: PeriodicScheduleSpec?,
        confirmed: Boolean,
    ) {
        prepare(
            """
            INSERT INTO periodic_registrations (
                work_id, role, generation, bundle_name, ability_name, request_id, interval_millis, requires_network, confirmed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(work_id, role) DO UPDATE SET
                generation = excluded.generation, bundle_name = excluded.bundle_name, ability_name = excluded.ability_name,
                request_id = excluded.request_id, interval_millis = excluded.interval_millis, requires_network = excluded.requires_network,
                confirmed = excluded.confirmed
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, workId.toLong())
            statement.bindLong(2, role)
            statement.bindLong(3, generation)
            if (spec == null) {
                for (column in 4..8) statement.bindNull(column)
            } else {
                statement.bindText(4, spec.bundleName)
                statement.bindText(5, spec.abilityName)
                statement.bindText(6, spec.requestId.toString())
                statement.bindLong(7, spec.intervalMillis.toLong())
                statement.bindLong(8, if (spec.requiresNetwork) 1L else 0L)
            }
            statement.bindLong(9, if (confirmed) 1L else 0L)
            statement.step()
        }
    }
}

private const val DESIRED = 0L
private const val ISSUED = 1L

internal fun SQLiteConnection.createPeriodicRegistrationTable() {
    execSQL(
        """
        CREATE TABLE periodic_registrations (
            work_id INTEGER NOT NULL CHECK (work_id BETWEEN 0 AND 2147483647),
            role INTEGER NOT NULL CHECK (role IN ($DESIRED, $ISSUED)),
            generation INTEGER NOT NULL CHECK (generation > 0),
            bundle_name TEXT,
            ability_name TEXT,
            request_id TEXT,
            interval_millis INTEGER CHECK (interval_millis BETWEEN 1 AND 2147483647),
            requires_network INTEGER CHECK (requires_network IN (0, 1)),
            confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1) AND (role = $ISSUED OR confirmed = 0)),
            PRIMARY KEY (work_id, role),
            CHECK (
                (role = $DESIRED AND bundle_name IS NULL AND ability_name IS NULL AND request_id IS NULL
                    AND interval_millis IS NULL AND requires_network IS NULL)
                OR (bundle_name IS NOT NULL AND ability_name IS NOT NULL AND request_id IS NOT NULL
                    AND interval_millis IS NOT NULL AND requires_network IS NOT NULL)
            )
        )
        """.trimIndent(),
    )
}

private fun OhosPeriodicRegistration.toSpec() =
    PeriodicScheduleSpec(bundleName, abilityName, requestId, repeatIntervalMillis, requiresNetwork)
