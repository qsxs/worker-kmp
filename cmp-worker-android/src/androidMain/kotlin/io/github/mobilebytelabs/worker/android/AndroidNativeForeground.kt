package io.github.mobilebytelabs.worker.android

import androidx.work.ForegroundInfo
import io.github.mobilebytelabs.worker.WorkerContext

/** 为 Android WorkManager 提供启动前所需的原生前台通知。 */
public interface AndroidNativeForegroundProvider {
    /** 只构造通知描述，前台提升及等待由 WorkManager 负责。 */
    public suspend fun getAndroidForegroundInfo(): ForegroundInfo
}

/** Android Worker 工厂提供的执行上下文，支持原样提交应用构建的前台通知。 */
public interface AndroidForegroundWorkerContext : WorkerContext {
    /** 在当前 Worker 执行期间调用，等待原生前台更新完成。 */
    public suspend fun setForeground(info: ForegroundInfo)
}
