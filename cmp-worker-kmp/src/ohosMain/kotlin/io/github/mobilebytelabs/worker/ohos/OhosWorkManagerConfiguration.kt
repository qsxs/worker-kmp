package io.github.mobilebytelabs.worker.ohos

import okio.Path
import okio.Path.Companion.toPath

/**
 * 应用进程和 WorkScheduler 扩展必须使用同一个应用私有文件目录。
 * [firstSystemWorkId] 是本库分配的起点，必须在同一数据库的所有进程保持一致。
 * 与其他调度器共存时，宿主须保留从该起点开始的系统 workId 范围。
 */
public class OhosWorkManagerConfiguration(
    privateFilesDirectory: String,
    public val bundleName: String,
    public val abilityName: String,
    public val scheduler: OhosWorkScheduler,
    public val firstSystemWorkId: Int = 0,
) {
    internal val directory = workDirectory(privateFilesDirectory)

    init {
        require(bundleName.isNotBlank() && abilityName.isNotBlank()) { "Periodic work requires a bundle and ability" }
        require(firstSystemWorkId >= 0) { "Expected a nonnegative first system work ID" }
    }
}

internal fun workDirectory(privateFilesDirectory: String): Path {
    val path = privateFilesDirectory.toPath(normalize = true)
    require(path.isAbsolute) { "Worker KMP requires an absolute OHOS private files directory" }
    return path / "worker-kmp-ohos"
}
