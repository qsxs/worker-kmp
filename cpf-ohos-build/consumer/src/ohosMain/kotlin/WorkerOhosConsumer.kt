import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicCallback
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicExecution
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicExecutionResult
import io.github.mobilebytelabs.worker.ohos.OhosPeriodicRegistration
import io.github.mobilebytelabs.worker.ohos.OhosWorkManager
import io.github.mobilebytelabs.worker.ohos.OhosWorkManagerConfiguration
import io.github.mobilebytelabs.worker.ohos.OhosWorkScheduler
import io.github.mobilebytelabs.worker.registry.workerRegistry
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi

private class ProbeWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult {
        setProgress(WorkProgress.of(75, inputData))
        return WorkResult.success(inputData)
    }
}

/** 独立制品链接探针；传入新的应用私有目录时可由 OHOS 宿主执行。 */
@OptIn(ExperimentalNativeApi::class, ExperimentalUuidApi::class)
@CName("worker_ohos_durable_publication_probe")
fun workerOhosDurablePublicationProbe(privateFilesDirectory: String): Int = runBlocking {
    withTimeout(15_000) {
        val key = "ProbeWorker"
        val registry = workerRegistry { register(key, ::ProbeWorker) }
        val registrations = mutableMapOf<Int, OhosPeriodicRegistration>()
        val scheduler = object : OhosWorkScheduler {
            override suspend fun ensure(registration: OhosPeriodicRegistration) {
                registrations[registration.workId] = registration
            }

            override suspend fun cancel(registration: OhosPeriodicRegistration) {
                check(registrations.remove(registration.workId) == registration)
            }
        }
        val manager = OhosWorkManager(
            this,
            registry,
            OhosWorkManagerConfiguration(privateFilesDirectory, "org.example.probe", "WorkerAbility", scheduler),
        )
        val data = workDataOf("value" to 31)
        val once = OneTimeWorkRequestBuilder<ProbeWorker>(key).setInputData(data).build()
        val onceId = manager.enqueue(once)
        val onceInfo = checkNotNull(manager.getWorkInfoByIdFlow(onceId).first { it?.isFinished == true })
        check(onceInfo.state == WorkInfo.State.SUCCEEDED && onceInfo.outputData.getInt("value") == 31)

        val periodic = PeriodicWorkRequestBuilder<ProbeWorker>(key, 2.hours).setInputData(data).build()
        val periodicId = manager.enqueueUniquePeriodicWork("probe", ExistingPeriodicWorkPolicy.KEEP, periodic)
        val registration = registrations.values.single()
        val callback = OhosPeriodicCallback(
            registration.workId,
            registration.bundleName,
            registration.abilityName,
            registration.requestId,
            registration.generation,
        )
        val execution = OhosPeriodicExecution(privateFilesDirectory)
        check(execution.run(callback, registry) == OhosPeriodicExecutionResult.SUCCEEDED)
        check(manager.getWorkInfoById(periodicId)?.outputData?.getInt("value") == 31)
        registrations.clear()
        manager.recoverPendingWork()
        check(registrations.values.single() == registration)
        manager.cancelUniqueWork("probe")
        check(registrations.isEmpty())
        check(execution.run(callback, registry) == OhosPeriodicExecutionResult.REJECTED)
        0
    }
}
