@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalWorkerApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** 仅在 Apple 测试目标执行，覆盖真实 IosWorkManager 接线。 */
class IosWorkCompletionIntegrationTest {
    @Test
    fun disabledBackgroundAndPersistenceSupportSuccessFailureAndFreshIdValidation() = runTest {
        for (succeeded in listOf(true, false)) {
            val output = WorkData("completed" to true)
            val manager = IosWorkManager(
                workerFactory = factory {
                    if (succeeded) WorkResult.success(output) else WorkResult.Failure("Expected failure", output)
                },
                config = disabledConfig(),
            )
            val request = request()
            try {
                val info = real { manager.enqueueAndAwaitCompletion(request) }
                assertEquals(if (succeeded) WorkInfo.State.SUCCEEDED else WorkInfo.State.FAILED, info.state)
                assertEquals(1, info.runAttemptCount)
                assertEquals(output, info.outputData)
                assertFailsWith<WorkEnqueueException> { real { manager.enqueueAndAwaitCompletion(request) } }
                assertEquals(info, real { manager.getWorkInfoById(request.id) })
            } finally {
                manager.shutdown()
            }
        }
    }

    @Test
    fun retriesWaitForTheLastAttemptAndItsFinally() = runTest {
        var attempts = 0
        val finalCleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val manager = manager(
            factory {
                attempts++
                if (attempts < 3) {
                    WorkResult.retry()
                } else {
                    try {
                        WorkResult.success()
                    } finally {
                        withContext(NonCancellable) {
                            finalCleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            },
        )
        val retry = RetryConfig(maxAttempts = 3, initialDelay = 1.milliseconds, backoffPolicy = BackoffPolicy.LINEAR)
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("OwnedWorker")
            .setBackoffCriteria(BackoffPolicy.LINEAR, retry)
            .build()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { finalCleanupStarted.await() }
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            val info = real { caller.await() }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertEquals(3, info.runAttemptCount)
            assertEquals(3, attempts)
        } finally {
            releaseCleanup.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun callerCancellationWaitsForActualWorkerChildren() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val manager = manager(
            factory {
                coroutineScope {
                    launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                releaseCleanup.await()
                            }
                        }
                    }
                    awaitCancellation()
                }
            },
        )
        val request = request()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { started.await() }
            caller.cancel()
            real { cleanupStarted.await() }
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            real { caller.join() }
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
        } finally {
            releaseCleanup.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun publishedCancelledStateDoesNotReleaseTheWaiterBeforeActualCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val manager = manager(
            factory {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            },
        )
        val request = request()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { started.await() }
            real { manager.cancelWorkById(request.id) }
            real { cleanupStarted.await() }
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { real { caller.await() } }
        } finally {
            releaseCleanup.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun factoryReturningAfterCallerCancellationNeverEntersBusinessWork() = runTest {
        val factoryStarted = CompletableDeferred<Unit>()
        val releaseFactory = CompletableDeferred<Unit>()
        var ran = false
        val factory = object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                // 模拟同步工厂仍占用资源，取消只能等它真实返回。
                runBlocking {
                    factoryStarted.complete(Unit)
                    releaseFactory.await()
                }
                return object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult {
                        ran = true
                        return WorkResult.success()
                    }
                }
            }
        }
        val manager = manager(factory)
        val request = request()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { factoryStarted.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            releaseFactory.complete(Unit)
            real { caller.join() }
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
        } finally {
            releaseFactory.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun cancellationDuringRealRegistrationDoesNotLeakTheUnstartedJob() = runTest {
        val persistence = GatedPersistence()
        var ran = false
        val manager = manager(
            factory {
                ran = true
                WorkResult.success()
            },
            persistence,
        )
        val request = request()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { persistence.saveStarted.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            persistence.releaseSave.complete(Unit)
            real { caller.join() }
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
            assertTrue(real { persistence.memory.loadRecords() }.isEmpty())
        } finally {
            persistence.releaseSave.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun registrationPersistenceFailureIsOwnedAndCleansItsPartialRecord() = runTest {
        val memory = InMemoryIosWorkPersistence()
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))
            override suspend fun loadAll(): List<WorkInfo> = memory.loadAll()
            override suspend fun delete(id: Uuid) = memory.delete(id)
            override suspend fun saveRecord(record: StoredWork) {
                memory.saveRecord(record)
                error("Registration save failed")
            }
        }
        var ran = false
        val manager = manager(
            factory {
                ran = true
                WorkResult.success()
            },
            persistence,
        )
        val request = request()
        try {
            val failure = assertFailsWith<IllegalStateException> { real { manager.enqueueAndAwaitCompletion(request) } }
            assertEquals("Registration save failed", failure.message)
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
            assertTrue(real { memory.loadRecords() }.isEmpty())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun shutdownDuringRegistrationCancelsALazyJobWhoseBodyNeverStarts() = runTest {
        val persistence = GatedPersistence()
        var ran = false
        val manager = manager(
            factory {
                ran = true
                WorkResult.success()
            },
            persistence,
        )
        val request = request()
        val caller = async { manager.enqueueAndAwaitCompletion(request) }
        try {
            real { persistence.saveStarted.await() }
            manager.shutdown()
            persistence.releaseSave.complete(Unit)
            assertFailsWith<CancellationException> { real { caller.await() } }
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, real { manager.getWorkInfoById(request.id) }?.state)
            assertTrue(real { persistence.memory.loadRecords() }.isEmpty())
        } finally {
            persistence.releaseSave.complete(Unit)
            manager.shutdown()
        }
    }

    @Test
    fun shutdownAfterStartingStillWaitsForWorkerFinally() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val manager = manager(
            factory {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            },
        )
        val caller = async { manager.enqueueAndAwaitCompletion(request()) }
        try {
            real { started.await() }
            manager.shutdown()
            real { cleanupStarted.await() }
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { real { caller.await() } }
            assertFailsWith<CancellationException> { real { manager.enqueueAndAwaitCompletion(request()) } }
        } finally {
            releaseCleanup.complete(Unit)
            manager.shutdown()
        }
    }

    private fun manager(factory: IosWorkerFactory, persistence: IosWorkPersistence = NoOpIosWorkPersistence) =
        IosWorkManager(factory, disabledConfig(), persistence)

    private fun disabledConfig() = IosWorkManagerConfig(enableBackgroundTasks = false, enablePersistence = false)

    private fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CoroutineWorker>("OwnedWorker").build()

    private fun factory(work: suspend () -> WorkResult) = object : IosWorkerFactory {
        override fun create(workerClass: String, context: WorkerContext): CoroutineWorker =
            object : CoroutineWorker(context) {
                override suspend fun doWork(): WorkResult = work()
            }
    }

    private suspend fun <T> real(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5_000, block) }

    private class GatedPersistence : IosWorkPersistence {
        val memory = InMemoryIosWorkPersistence()
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()

        override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))
        override suspend fun loadAll(): List<WorkInfo> = memory.loadAll()
        override suspend fun delete(id: Uuid) = memory.delete(id)
        override suspend fun saveRecord(record: StoredWork) {
            saveStarted.complete(Unit)
            releaseSave.await()
            memory.saveRecord(record)
        }
    }
}
