package io.github.mobilebytelabs.worker.ohos

import io.github.mobilebytelabs.worker.ohos.internal.OhosPeriodicExecutor
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteManagedWorkStore
import io.github.mobilebytelabs.worker.ohos.internal.storage.SqliteWorkStore
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import kotlin.uuid.Uuid

/** WorkScheduler 扩展从回调读取的登记身份。 */
public data class OhosPeriodicCallback(
    val workId: Int,
    val bundleName: String,
    val abilityName: String,
    val requestId: Uuid,
    val generation: Long,
)

public enum class OhosPeriodicExecutionResult { SUCCEEDED, FAILED, CANCELLED, REJECTED, BUSY, RETRY }

/** 停止操作只影响此票据绑定的一次物理执行。 */
public class OhosPeriodicExecutionTicket internal constructor(
    internal val callback: OhosPeriodicCallback,
    public val occurrenceId: Uuid,
)

/**
 * WorkScheduler 扩展使用的冷启动入口。宿主用与管理器相同的目录和 Worker 注册名，
 * 把系统回调交给 [run]；完成后由宿主结束本次系统执行窗口。
 */
public class OhosPeriodicExecution(privateFilesDirectory: String) {
    private val directory = workDirectory(privateFilesDirectory)
    private val database = SqliteWorkStore(directory)
    private val store = SqliteManagedWorkStore(database)
    private val executor = OhosPeriodicExecutor(directory, store)

    /** 在 Worker 启动前交接票据；宿主可以处理接纳期间收到的停止。 */
    public suspend fun run(
        callback: OhosPeriodicCallback,
        registry: WorkerRegistry,
        onReserved: suspend (OhosPeriodicExecutionTicket) -> Unit = {},
    ): OhosPeriodicExecutionResult {
        registry.lock()
        database.initialize()
        return executor.run(callback, registry, onReserved)
    }

    public suspend fun cancel(ticket: OhosPeriodicExecutionTicket): Boolean {
        database.initialize()
        return store.stopPeriodic(ticket.callback, ticket.occurrenceId)
    }
}
