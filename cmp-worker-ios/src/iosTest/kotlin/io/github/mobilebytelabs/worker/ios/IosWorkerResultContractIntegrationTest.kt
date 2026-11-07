@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalWorkerApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** 只在 Apple 目标执行，直接覆盖真实 iOS 工厂、执行和持久化校验链。 */
class IosWorkerResultContractIntegrationTest {
    @Test
    fun factoryStartsAtZeroAndReusedContextAdvancesOnRetry() = runTest {
        val factoryAttempts = mutableListOf<Int>()
        val workAttempts = mutableListOf<Int>()
        val factory = object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                factoryAttempts += context.runAttemptCount
                return object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult {
                        workAttempts += context.runAttemptCount
                        return if (context.runAttemptCount < 2) WorkResult.Retry() else WorkResult.Success()
                    }
                }
            }
        }
        val request =
            OneTimeWorkRequestBuilder<CoroutineWorker>("RetryContext")
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    RetryConfig(maxAttempts = 3, initialDelay = 1.milliseconds, backoffPolicy = BackoffPolicy.LINEAR),
                ).build()
        val manager = IosWorkManager(factory, disabledConfig())
        try {
            val info = real { manager.enqueueAndAwaitCompletion(request) }
            assertEquals(listOf(0), factoryAttempts)
            assertEquals(listOf(0, 1, 2), workAttempts)
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertEquals(3, info.runAttemptCount)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun restoredOneTimeKeepsAttemptButPeriodicStartsANewCycleAndBothKeepFailureOutput() = runTest {
        for (periodic in listOf(false, true)) {
            val factoryAttempts = mutableListOf<Int>()
            val workAttempts = mutableListOf<Int>()
            val output = WorkData("failure" to "Expected", "large" to "x".repeat(20_000))
            val factory = object : IosWorkerFactory {
                override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                    factoryAttempts += context.runAttemptCount
                    return object : CoroutineWorker(context) {
                        override suspend fun doWork(): WorkResult {
                            workAttempts += context.runAttemptCount
                            return WorkResult.Failure("Expected", output)
                        }
                    }
                }
            }
            val request =
                if (periodic) {
                    PeriodicWorkRequestBuilder<CoroutineWorker>("Restored", 1.hours).build()
                } else {
                    OneTimeWorkRequestBuilder<CoroutineWorker>("Restored").build()
                }
            val previousAttempts = if (periodic) 5 else 2
            val persistence = InMemoryIosWorkPersistence()
            persistence.saveRecord(
                StoredWork(
                    info =
                    WorkInfo(
                        request.id,
                        if (periodic) WorkInfo.State.SUCCEEDED else WorkInfo.State.ENQUEUED,
                        runAttemptCount = previousAttempts,
                    ),
                    request = request,
                    nextRunAtEpochMillis = 0L,
                ),
            )
            val manager = IosWorkManager(factory, disabledConfig(), persistence)
            try {
                assertFalse(real { manager.runPendingWork() })
                val info = real { manager.getWorkInfoById(request.id) }
                val expectedAttempt = if (periodic) 0 else previousAttempts
                assertEquals(listOf(expectedAttempt), factoryAttempts)
                assertEquals(listOf(expectedAttempt), workAttempts)
                assertEquals(WorkInfo.State.FAILED, info?.state)
                assertEquals(previousAttempts + 1, info?.runAttemptCount)
                assertEquals(output, info?.outputData)
            } finally {
                manager.shutdown()
            }
        }
    }

    @Test
    fun noPersistenceKeepsFailureOutputWithoutAnInventedSizeLimit() = runTest {
        val output = WorkData("large" to "x".repeat(20_000), "double" to Double.NaN)
        val manager =
            IosWorkManager(
                factory { WorkResult.Failure("Expected", output) },
                disabledConfig(),
            )
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("FailureOutput").build()
        try {
            val info = real { manager.enqueueAndAwaitCompletion(request) }
            assertEquals(WorkInfo.State.FAILED, info.state)
            assertEquals(20_000, info.outputData.getString("large")?.length)
            assertTrue((info.outputData.keyValueMap()["double"] as Double).isNaN())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun periodicFailureOutputUsesTheSameRealPersistenceValidationAsSuccess() = runTest {
        val request = PeriodicWorkRequestBuilder<CoroutineWorker>("InvalidFailureOutput", 1.hours).build()
        val memory = InMemoryIosWorkPersistence()
        memory.saveRecord(
            StoredWork(WorkInfo(request.id, WorkInfo.State.ENQUEUED), request = request, nextRunAtEpochMillis = 0L),
        )
        val persistence =
            object : IosWorkPersistence by memory {
                override fun validateData(data: WorkData) = IosWorkRecordCodec.validateData(data)
            }
        val manager =
            IosWorkManager(
                factory { WorkResult.Failure("Expected", WorkData("unsupported" to Any())) },
                disabledConfig(),
                persistence,
            )
        try {
            assertFalse(real { manager.runPendingWork() })
            val info = real { manager.getWorkInfoById(request.id) }
            assertEquals(WorkInfo.State.FAILED, info?.state)
            assertEquals(WorkData.EMPTY, info?.outputData)
        } finally {
            manager.shutdown()
        }
    }

    private fun disabledConfig() = IosWorkManagerConfig(enableBackgroundTasks = false, enablePersistence = false)

    private fun factory(work: suspend () -> WorkResult): IosWorkerFactory = object : IosWorkerFactory {
        override fun create(workerClass: String, context: WorkerContext): CoroutineWorker =
            object : CoroutineWorker(context) {
                override suspend fun doWork(): WorkResult = work()
            }
    }

    private suspend fun <T> real(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5_000, block) }
}
