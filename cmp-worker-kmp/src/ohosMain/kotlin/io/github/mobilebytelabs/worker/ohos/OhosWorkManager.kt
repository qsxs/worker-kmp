package io.github.mobilebytelabs.worker.ohos

import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.ohos.internal.OhosManagedWorkManager
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import io.github.mobilebytelabs.worker.runtime.ProcessOneTimeWorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * 鸿蒙任务管理器。提供 [OhosWorkManagerConfiguration] 时使用持久化后端；
 * 两参数构造只提供进程内一次性任务。创建后会锁定 Worker 注册表。
 */
public class OhosWorkManager private constructor(private val engine: WorkManager) : WorkManager by engine {
    public constructor(
        scope: CoroutineScope,
        registry: WorkerRegistry,
    ) : this(ProcessOneTimeWorkManager(scope, registry))

    public constructor(
        scope: CoroutineScope,
        registry: WorkerRegistry,
        configuration: OhosWorkManagerConfiguration,
    ) : this(OhosManagedWorkManager(scope, registry, configuration))

    /** 通过唯一任务名称取消工作。 */
    public suspend fun cancelUniqueWork(uniqueWorkName: String) {
        when (engine) {
            is ProcessOneTimeWorkManager -> engine.cancelUniqueWork(uniqueWorkName)
            is OhosManagedWorkManager -> engine.cancelUniqueWork(uniqueWorkName)
            else -> error("Unknown OHOS work manager backend")
        }
    }

    /** 按请求 ID 观察最新状态。 */
    public fun getWorkInfoByIdFlow(id: Uuid): Flow<WorkInfo?> = when (engine) {
        is ProcessOneTimeWorkManager -> engine.getWorkInfoByIdFlow(id)
        is OhosManagedWorkManager -> engine.getWorkInfoByIdFlow(id)
        else -> error("Unknown OHOS work manager backend")
    }

    /** 宿主启动、系统桥接可用后调用，回查活动登记，并补偿未完成的登记或取消。 */
    public suspend fun recoverPendingWork() {
        check(engine is OhosManagedWorkManager) { "Recovery requires a configured OHOS work manager" }
        engine.recoverPendingWork()
    }
}
