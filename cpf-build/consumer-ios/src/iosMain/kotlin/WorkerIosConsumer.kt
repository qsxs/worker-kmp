import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.ExperimentalWorkerApi
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.ios.IosWorkManager
import io.github.mobilebytelabs.worker.ios.IosWorkManagerConfig
import io.github.mobilebytelabs.worker.ios.IosWorkerFactory
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

private class PublicationWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult {
        setProgress(WorkProgress.of(50, inputData))
        return WorkResult.Success(inputData)
    }
}

/** 独立消费者通过公开制品链接实际执行、唯一任务、取消和完成等待入口。 */
@OptIn(ExperimentalWorkerApi::class, ExperimentalNativeApi::class)
@CName("worker_ios_publication_probe")
public fun workerIosPublicationProbe(): Int = runBlocking {
    val key = "PublicationWorker"
    val manager = IosWorkManager(
        object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                check(workerClass == key)
                return PublicationWorker(context)
            }
        },
        IosWorkManagerConfig(enableBackgroundTasks = false, enablePersistence = false),
    )
    try {
        val request = OneTimeWorkRequestBuilder<PublicationWorker>(key)
            .setInputData(workDataOf("result" to 47))
            .build()
        val result = manager.enqueueAndAwaitCompletion(request)
        check(result.state == WorkInfo.State.SUCCEEDED && result.outputData.getInt("result") == 47)
        val unique = OneTimeWorkRequestBuilder<PublicationWorker>(key).build()
        val id = manager.enqueueUniqueWork("publication", ExistingWorkPolicy.KEEP, unique)
        manager.getWorkInfosForUniqueWorkFlow("publication").first()
        manager.cancelWorkById(id)
        0
    } finally {
        manager.shutdown()
    }
}
