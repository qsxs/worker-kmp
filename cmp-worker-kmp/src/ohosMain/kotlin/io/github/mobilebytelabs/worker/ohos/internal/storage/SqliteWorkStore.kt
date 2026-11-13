package io.github.mobilebytelabs.worker.ohos.internal.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

private const val SCHEMA_VERSION = 1L

/** 两个进程通过 SQLite 提交意图和状态；系统调用和 Worker 执行不占用写事务。 */
internal class SqliteWorkStore(private val directory: Path, private val driver: SQLiteDriver = BundledSQLiteDriver()) {
    private val path = (directory / "work.sqlite").toString()

    suspend fun initialize() {
        withContext(Dispatchers.IO) { FileSystem.SYSTEM.createDirectories(directory) }
        transaction {
            when (val version = readLong("PRAGMA user_version")) {
                0L -> {
                    createManagedWorkTables()
                    createPeriodicRegistrationTable()
                    execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }

                else -> checkSchemaVersion(version)
            }
        }
    }

    suspend fun <T> readInSchema(block: SQLiteConnection.() -> T): T = withConnection {
        checkSchemaVersion(readLong("PRAGMA user_version"))
        block()
    }

    suspend fun <T> mutate(block: SQLiteConnection.() -> T): T = transaction {
        checkSchemaVersion(readLong("PRAGMA user_version"))
        block()
    }

    /** SQLite 的 data_version 在同一连接中可观察其他进程或连接的提交。 */
    fun observeChanges(): Flow<Unit> = flow {
        driver.open(path).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 5000")
            checkSchemaVersion(connection.readLong("PRAGMA user_version"))
            var seen = -1L
            while (true) {
                val version = connection.readLong("PRAGMA data_version")
                if (version != seen) {
                    seen = version
                    emit(Unit)
                }
                delay(100L)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> transaction(block: SQLiteConnection.() -> T): T = withConnection {
        execSQL("BEGIN IMMEDIATE")
        try {
            val result = block()
            execSQL("COMMIT")
            result
        } catch (failure: Throwable) {
            try {
                execSQL("ROLLBACK")
            } catch (rollbackFailure: Throwable) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private suspend fun <T> withConnection(block: SQLiteConnection.() -> T): T = withContext(Dispatchers.IO) {
        driver.open(path).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 5000")
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.block()
        }
    }

    private fun checkSchemaVersion(version: Long) {
        check(version == SCHEMA_VERSION) { "Unsupported Worker KMP OHOS database schema version: $version" }
    }
}

private fun SQLiteConnection.readLong(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step()) { "Expected a value from the work database" }
    statement.getLong(0)
}
