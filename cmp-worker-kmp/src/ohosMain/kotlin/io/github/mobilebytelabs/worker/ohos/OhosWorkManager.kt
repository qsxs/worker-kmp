package io.github.mobilebytelabs.worker.ohos

import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkManager
import io.github.mobilebytelabs.worker.registry.WorkerRegistry
import io.github.mobilebytelabs.worker.runtime.ProcessOneTimeWorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * 鸿蒙进程内一次性任务管理器。任务执行归应用传入的协程作用域管理。
 * 创建管理器时会锁定已经配置完成的 worker 注册表。
 */
public class OhosWorkManager private constructor(private val engine: ProcessOneTimeWorkManager) :
    WorkManager by engine {
    public constructor(
        scope: CoroutineScope,
        registry: WorkerRegistry,
    ) : this(ProcessOneTimeWorkManager(scope, registry))

    /** 通过唯一任务名称取消工作。 */
    public suspend fun cancelUniqueWork(uniqueWorkName: String): Unit = engine.cancelUniqueWork(uniqueWorkName)

    /** 按请求 ID 观察最新状态。 */
    public fun getWorkInfoByIdFlow(id: Uuid): Flow<WorkInfo?> = engine.getWorkInfoByIdFlow(id)
}
