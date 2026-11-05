package io.github.mobilebytelabs.worker.android

import androidx.work.Data
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkerContext
import kotlin.uuid.Uuid

internal class AndroidWorkerContext(
    private val androidWorker: KmpAndroidWorker,
    override val id: Uuid,
    override val inputData: WorkData,
    override val tags: Set<String>,
) : WorkerContext {
    override suspend fun setProgress(progress: WorkProgress) {
        require(!progress.data.hasKey(KEY_KMP_PROGRESS)) { "Progress data contains reserved key $KEY_KMP_PROGRESS" }
        val data = Data.Builder()
            .putAll(progress.data.keyValueMap())
            .putInt(KEY_KMP_PROGRESS, progress.progress)
            .build()
        androidWorker.setProgress(data)
    }
}
