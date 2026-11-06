@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalWorkerApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.oneTimeWorkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class IosWorkManagerTest {

    private fun workManager(persistence: IosWorkPersistence = InMemoryIosWorkPersistence()) = IosWorkManager(
        workerFactory = TestIosWorkerFactory,
        config = IosWorkManagerConfig.DEFAULT,
        persistence = persistence,
    )

    @Test
    fun enqueue_returnsId() = runTest {
        val wm = workManager()
        val req = OneTimeWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
        ).build()
        val id = wm.enqueue(req)
        assertEquals(req.id, id)
    }

    @Test
    fun enqueue_successWorker_transitionsToSucceeded() = runTest {
        val wm = workManager()
        val req = OneTimeWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
        ).build()
        val id = wm.enqueue(req)
        eventually { wm.getWorkInfoById(id)?.state == WorkInfo.State.SUCCEEDED }
        assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(id)?.state)
    }

    @Test
    fun enqueue_failWorker_transitionsToFailed() = runTest {
        val wm = workManager()
        val req = OneTimeWorkRequestBuilder<FailIosWorker>(
            FailIosWorker::class.simpleName!!,
        ).build()
        val id = wm.enqueue(req)
        eventually { wm.getWorkInfoById(id)?.state == WorkInfo.State.FAILED }
        assertEquals(WorkInfo.State.FAILED, wm.getWorkInfoById(id)?.state)
    }

    @Test
    fun enqueue_retryWorker_retriesAndSucceeds() = runTest {
        RetryThenSucceedIosWorker.callCount = 0
        val wm = workManager()
        val shortRetry = RetryConfig(
            maxAttempts = 3,
            initialDelay = 10.milliseconds,
            backoffPolicy = BackoffPolicy.LINEAR,
        )
        val req = OneTimeWorkRequestBuilder<RetryThenSucceedIosWorker>(
            RetryThenSucceedIosWorker::class.simpleName!!,
        ).setBackoffCriteria(BackoffPolicy.LINEAR, shortRetry).build()
        val id = wm.enqueue(req)
        eventually(timeoutMs = 3_000) { wm.getWorkInfoById(id)?.state == WorkInfo.State.SUCCEEDED }
        assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(id)?.state)
        assertTrue(RetryThenSucceedIosWorker.callCount >= 3)
    }

    @Test
    fun cancelWorkById_stopsEnqueuedWork() = runTest {
        val wm = workManager()
        val req = OneTimeWorkRequestBuilder<SlowIosWorker>(
            SlowIosWorker::class.simpleName!!,
        ).build()
        val id = wm.enqueue(req)
        wm.cancelWorkById(id)
        eventually { wm.getWorkInfoById(id)?.state?.isFinished == true }
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(id)?.state)
    }

    @Test
    fun cancelWorkById_discardsLateResultAndProgressFromRunningWorker() = runTest {
        val running = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val reportedLateProgress = CompletableDeferred<Unit>()
        val factory = object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker =
                object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult {
                        val job = currentCoroutineContext().job
                        return withContext(NonCancellable) {
                            running.complete(job)
                            release.await()
                            setProgress(WorkProgress.COMPLETE)
                            reportedLateProgress.complete(Unit)
                            WorkResult.success(WorkData("late" to true))
                        }
                    }
                }
        }
        val wm = IosWorkManager(workerFactory = factory, persistence = NoOpIosWorkPersistence)
        try {
            val request = OneTimeWorkRequestBuilder<SlowIosWorker>("late-result").build()
            val id = wm.enqueue(request)
            val job = withContext(Dispatchers.Default) { withTimeout(2_000) { running.await() } }
            assertEquals(WorkInfo.State.RUNNING, wm.getWorkInfoById(id)?.state)
            wm.cancelWorkById(id)
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(2_000) { job.join() } }
            assertTrue(reportedLateProgress.isCompleted)
            val cancelled = wm.getWorkInfoById(id)
            assertEquals(WorkInfo.State.CANCELLED, cancelled?.state)
            assertEquals(WorkProgress.NONE, cancelled?.progress)
            assertEquals(WorkData.EMPTY, cancelled?.outputData)
            assertEquals(1, cancelled?.runAttemptCount)
        } finally {
            release.complete(Unit)
            wm.shutdown()
        }
    }

    @Test
    fun enqueueUniqueWork_keepsOrReplacesTheRegisteredWorker() = runTest {
        val started = Channel<Uuid>(Channel.UNLIMITED)
        val factory = object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker =
                object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult {
                        started.send(context.id)
                        awaitCancellation()
                    }
                }
        }
        val wm = IosWorkManager(workerFactory = factory, persistence = NoOpIosWorkPersistence)
        val name = "backup"
        val builder = OneTimeWorkRequestBuilder<SlowIosWorker>("unique-running").addTag("manual")
        val first = builder.build()
        val dropped = builder.setId(Uuid.random()).build()
        val replacement = builder.setId(Uuid.random()).build()
        try {
            assertEquals(first.id, wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, first))
            assertEquals(first.id, withContext(Dispatchers.Default) { withTimeout(2_000) { started.receive() } })
            assertEquals(first.id, wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, dropped))
            assertNull(wm.getWorkInfoById(dropped.id))

            val periodic = PeriodicWorkRequestBuilder<SlowIosWorker>("periodic", repeatInterval = 5.minutes).build()
            assertFailsWith<WorkEnqueueException> {
                wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.REPLACE, periodic)
            }
            assertNull(wm.getWorkInfoById(periodic.id))
            assertEquals(WorkInfo.State.RUNNING, wm.getWorkInfoById(first.id)?.state)

            assertEquals(replacement.id, wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, replacement))
            assertEquals(replacement.id, withContext(Dispatchers.Default) { withTimeout(2_000) { started.receive() } })
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(first.id)?.state)
            val named = wm.getWorkInfosForUniqueWorkFlow(name).first()
            assertEquals(listOf(replacement.id), named.filterNot { it.isFinished }.map { it.id })
            assertTrue(wm.getWorkInfosByTag(name).first().isEmpty())
            assertEquals(setOf(first.id, replacement.id), wm.getWorkInfosByTag("manual").first().map { it.id }.toSet())
        } finally {
            wm.shutdown()
            started.cancel()
        }
    }

    @Test
    fun enqueueUniqueWork_unknownWorkerFailsAndKeepCanRunNextRequest() = runTest {
        val wm = workManager(persistence = NoOpIosWorkPersistence)
        val name = "factory-retry"
        val unknown = OneTimeWorkRequestBuilder<SuccessIosWorker>("unknown-worker").build()
        val registered = OneTimeWorkRequestBuilder<SuccessIosWorker>(SuccessIosWorker::class.simpleName!!).build()
        try {
            assertEquals(unknown.id, wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, unknown))
            eventually { wm.getWorkInfoById(unknown.id)?.state == WorkInfo.State.FAILED }
            assertEquals(WorkInfo.State.FAILED, wm.getWorkInfoById(unknown.id)?.state)
            assertEquals(WorkData.EMPTY, wm.getWorkInfoById(unknown.id)?.outputData)

            assertEquals(registered.id, wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, registered))
            eventually { wm.getWorkInfoById(registered.id)?.state == WorkInfo.State.SUCCEEDED }
            assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(registered.id)?.state)
            assertEquals(WorkInfo.State.FAILED, wm.getWorkInfoById(unknown.id)?.state)
        } finally {
            wm.shutdown()
        }
    }

    @Test
    fun getWorkInfoById_returnsNullForUnknown() = runTest {
        val wm = workManager()
        assertNull(wm.getWorkInfoById(Uuid.random()))
    }

    @Test
    fun getWorkInfosByTag_flowEmitsOnStateChange() = runTest {
        val wm = workManager()
        val tag = "ios-sync"
        val req = OneTimeWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
        ).addTag(tag).build()
        wm.enqueue(req)
        eventually { wm.getWorkInfoById(req.id)?.state == WorkInfo.State.SUCCEEDED }
        val items = wm.getWorkInfosByTag(tag).first()
        assertTrue(items.any { it.id == req.id })
    }

    @Test
    fun cancelAllWorkByTag_cancelsAll() = runTest {
        val wm = workManager()
        val tag = "batch-ios"
        val ids = (1..3).map {
            val req = OneTimeWorkRequestBuilder<SlowIosWorker>(
                SlowIosWorker::class.simpleName!!,
            ).addTag(tag).build()
            wm.enqueue(req)
        }
        wm.cancelAllWorkByTag(tag)
        ids.forEach { id ->
            eventually { wm.getWorkInfoById(id)?.isFinished == true }
        }
        ids.forEach { id ->
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(id)?.state)
        }
    }

    @Test
    fun enqueue_passesInputData() = runTest {
        var received: WorkData? = null
        val factory = object : IosWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                received = context.inputData
                return SuccessIosWorker(context)
            }
        }
        val wm = IosWorkManager(workerFactory = factory)
        val data = WorkData("key" to "value", "num" to 42)
        val req = oneTimeWorkRequest<SuccessIosWorker> { setInputData(data) }
        wm.enqueue(req)
        eventually { received != null }
        assertEquals("value", received?.getString("key"))
    }

    @Test
    fun progressReportingWorker_updatesProgress() = runTest {
        val wm = workManager()
        val req = OneTimeWorkRequestBuilder<ProgressIosWorker>(
            ProgressIosWorker::class.simpleName!!,
        ).build()
        val id = wm.enqueue(req)
        eventually { wm.getWorkInfoById(id)?.state == WorkInfo.State.SUCCEEDED }
        assertEquals(100, wm.getWorkInfoById(id)?.progress?.progress)
    }

    @Test
    fun enqueueUniquePeriodicWork_executesMultipleTimes() = runTest {
        val wm = workManager()
        val req = PeriodicWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
            repeatInterval = 50.milliseconds,
        ).build()
        wm.enqueueUniquePeriodicWork("periodic-ios", ExistingPeriodicWorkPolicy.REPLACE, req)
        eventually(timeoutMs = 2_000) {
            (wm.getWorkInfoById(req.id)?.runAttemptCount ?: 0) >= 2
        }
        val count = wm.getWorkInfoById(req.id)?.runAttemptCount ?: 0
        assertTrue(count >= 2, "Expected ≥2 runs, got $count")
        wm.cancelWorkById(req.id)
    }

    @Test
    fun enqueueUniquePeriodicWork_replace_cancelsExisting() = runTest {
        val wm = workManager()
        val req1 = PeriodicWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
            repeatInterval = 5.minutes,
        ).build()
        val req2 = PeriodicWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
            repeatInterval = 5.minutes,
        ).build()
        wm.enqueueUniquePeriodicWork("unique-ios", ExistingPeriodicWorkPolicy.REPLACE, req1)
        wm.enqueueUniquePeriodicWork("unique-ios", ExistingPeriodicWorkPolicy.REPLACE, req2)
        eventually { wm.getWorkInfoById(req1.id)?.isFinished == true }
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(req1.id)?.state)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun enqueueUniquePeriodicWork_replace_replayingCancelledIdKeepsReplacement() = runTest {
        val wm = workManager()
        val builder = PeriodicWorkRequestBuilder<SuccessIosWorker>(
            SuccessIosWorker::class.simpleName!!,
            repeatInterval = 5.minutes,
        ).setInitialDelay(5.minutes)
        val previous = builder.build()
        val replacement = builder.build()
        val name = "replace-ios"
        val policy = ExistingPeriodicWorkPolicy.REPLACE
        val taggedOnly = OneTimeWorkRequestBuilder<SlowIosWorker>(SlowIosWorker::class.simpleName!!)
            .setInitialDelay(5.minutes)
            .addTag(name)
            .build()
        val activeIds = mutableListOf<Set<Uuid>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            wm.getWorkInfosForUniqueWorkFlow(name).collect { work ->
                activeIds += work.filterNot(WorkInfo::isFinished).map(WorkInfo::id).toSet()
            }
        }
        try {
            wm.enqueue(taggedOnly)
            wm.enqueueUniquePeriodicWork(name, policy, previous)
            wm.enqueueUniquePeriodicWork(name, policy, replacement)
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(previous.id)?.state)
            assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(replacement.id)?.state)

            assertEquals(previous.id, wm.enqueueUniquePeriodicWork(name, policy, previous))

            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(previous.id)?.state)
            assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(replacement.id)?.state)
            assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(taggedOnly.id)?.state)
            val taggedIds = wm.getWorkInfosByTag(name).first().map { it.id }.toSet()
            assertEquals(setOf(previous.id, replacement.id, taggedOnly.id), taggedIds)
            assertEquals(listOf(setOf(previous.id), setOf(replacement.id)), activeIds.filter { it.isNotEmpty() })

            wm.cancelAllWorkByTag(name)
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(replacement.id)?.state)
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(taggedOnly.id)?.state)
        } finally {
            wm.shutdown()
        }
    }

    // ── Config + persistence tests ────────────────────────────────────────────

    @Test
    fun config_defaults_disableBackgroundTasksAndEnablePersistence() {
        val config = IosWorkManagerConfig.DEFAULT
        assertEquals(false, config.enableBackgroundTasks)
        assertEquals("", config.bgProcessingTaskIdentifier)
        assertEquals(true, config.enablePersistence)
        assertEquals("worker-kmp-ios", config.persistenceKey)
    }

    @Test
    fun config_canEnableBackgroundTasks() {
        val config = IosWorkManagerConfig(
            enableBackgroundTasks = true,
            bgProcessingTaskIdentifier = "com.example.sync",
        )
        assertEquals(true, config.enableBackgroundTasks)
        assertEquals("com.example.sync", config.bgProcessingTaskIdentifier)
    }

    @Test
    fun persistence_save_isCalledOnEnqueue() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val wm = workManager(persistence = persistence)
        try {
            val req = OneTimeWorkRequestBuilder<SlowIosWorker>(
                SlowIosWorker::class.simpleName!!,
            ).setInitialDelay(5.minutes).build()
            wm.enqueue(req)
            eventually { persistence.loadAll().any { it.id == req.id } }
            assertEquals(WorkInfo.State.ENQUEUED, persistence.loadAll().singleOrNull { it.id == req.id }?.state)
        } finally {
            wm.shutdown()
        }
    }

    @Test
    fun persistence_restore_reEnqueuesInterruptedWork() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        // Simulate a work item that was RUNNING when app was killed.
        val id = kotlin.uuid.Uuid.random()
        persistence.save(WorkInfo(id = id, state = WorkInfo.State.RUNNING, tags = setOf("sync")))

        val wm = workManager(persistence = persistence)
        // Give the restore coroutine a moment to run.
        eventually { wm.getWorkInfoById(id) != null }
        val restored = wm.getWorkInfoById(id)
        // RUNNING → ENQUEUED on restore.
        assertEquals(WorkInfo.State.ENQUEUED, restored?.state)
    }

    @Test
    fun persistence_restore_keepsFinalStates() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val id = kotlin.uuid.Uuid.random()
        persistence.save(WorkInfo(id = id, state = WorkInfo.State.SUCCEEDED, tags = emptySet()))

        val wm = workManager(persistence = persistence)
        eventually { wm.getWorkInfoById(id) != null }
        assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(id)?.state)
    }

    @Test
    fun persistence_restoreIsSharedByCommandsAndFirstObservations() = runTest {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val restored = WorkInfo(Uuid.random(), WorkInfo.State.RUNNING, tags = setOf("lookup"))
        val byId = WorkInfo(Uuid.random(), WorkInfo.State.ENQUEUED)
        val byTag = WorkInfo(Uuid.random(), WorkInfo.State.ENQUEUED, tags = setOf("to-cancel"))
        val persistence = object : IosWorkPersistence {
            override suspend fun loadAll(): List<WorkInfo> {
                loadStarted.complete(Unit)
                releaseLoad.await()
                return listOf(restored, byId, byTag)
            }

            override suspend fun save(info: WorkInfo) = Unit
            override suspend fun delete(id: Uuid) = Unit
        }
        val wm = workManager(persistence)
        try {
            withContext(Dispatchers.Default) { withTimeout(2_000) { loadStarted.await() } }
            val abandoned = async(start = CoroutineStart.UNDISPATCHED) { wm.getWorkInfoById(restored.id) }
            val lookup = async(start = CoroutineStart.UNDISPATCHED) { wm.getWorkInfoById(restored.id) }
            val tagFlow = async(start = CoroutineStart.UNDISPATCHED) { wm.getWorkInfosByTag("lookup").first() }
            val nameFlow =
                async(start = CoroutineStart.UNDISPATCHED) { wm.getWorkInfosForUniqueWorkFlow("lookup").first() }
            val cancelId = async(start = CoroutineStart.UNDISPATCHED) { wm.cancelWorkById(byId.id) }
            val cancelTag = async(start = CoroutineStart.UNDISPATCHED) { wm.cancelAllWorkByTag("to-cancel") }

            val builder = OneTimeWorkRequestBuilder<SlowIosWorker>(SlowIosWorker::class.simpleName!!)
                .setInitialDelay(5.minutes)
            val ordinary = builder.build()
            val unique = builder.setId(Uuid.random()).build()
            val periodic = PeriodicWorkRequestBuilder<SlowIosWorker>(
                SlowIosWorker::class.simpleName!!,
                repeatInterval = 5.minutes,
            ).setInitialDelay(5.minutes).build()
            val enqueue = async(start = CoroutineStart.UNDISPATCHED) { wm.enqueue(ordinary) }
            val enqueueUnique = async(start = CoroutineStart.UNDISPATCHED) {
                wm.enqueueUniqueWork("new-unique", ExistingWorkPolicy.KEEP, unique)
            }
            val enqueuePeriodic = async(start = CoroutineStart.UNDISPATCHED) {
                wm.enqueueUniquePeriodicWork("new-periodic", ExistingPeriodicWorkPolicy.REPLACE, periodic)
            }

            val waiters = listOf(
                abandoned,
                lookup,
                tagFlow,
                nameFlow,
                cancelId,
                cancelTag,
                enqueue,
                enqueueUnique,
                enqueuePeriodic,
            )
            assertTrue(waiters.none { it.isCompleted })
            abandoned.cancelAndJoin()
            assertFalse(lookup.isCompleted)
            releaseLoad.complete(Unit)

            val expected = restored.copy(state = WorkInfo.State.ENQUEUED)
            assertEquals(expected, lookup.await())
            assertEquals(listOf(expected), tagFlow.await())
            assertTrue(nameFlow.await().isEmpty())
            cancelId.await()
            cancelTag.await()
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(byId.id)?.state)
            assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(byTag.id)?.state)
            assertEquals(ordinary.id, enqueue.await())
            assertEquals(unique.id, enqueueUnique.await())
            assertEquals(periodic.id, enqueuePeriodic.await())
        } finally {
            releaseLoad.complete(Unit)
            wm.shutdown()
        }
    }

    @Test
    fun persistence_restoreFailurePropagatesToCommandsAndObservers() = runTest {
        val persistence = object : IosWorkPersistence {
            override suspend fun loadAll(): List<WorkInfo> = error("Could not load iOS work records")

            override suspend fun save(info: WorkInfo) = Unit
            override suspend fun delete(id: Uuid) = Unit
        }
        val wm = workManager(persistence)
        try {
            val lookup = assertFailsWith<IllegalStateException> { wm.getWorkInfoById(Uuid.random()) }
            val observer = assertFailsWith<IllegalStateException> { wm.getWorkInfosByTag("lookup").first() }
            assertEquals("Could not load iOS work records", lookup.message)
            assertEquals(lookup.message, observer.message)
        } finally {
            wm.shutdown()
        }
    }
}

// ── Test workers ──────────────────────────────────────────────────────────────

class SuccessIosWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult = WorkResult.success()
}

class FailIosWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult = WorkResult.failure("fail")
}

class SlowIosWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult {
        delay(30_000)
        return WorkResult.success()
    }
}

class ProgressIosWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult {
        setProgress(WorkProgress(50))
        setProgress(WorkProgress(100))
        return WorkResult.success()
    }
}

class RetryThenSucceedIosWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork(): WorkResult {
        callCount++
        return if (callCount >= 3) WorkResult.success() else WorkResult.retry()
    }

    companion object {
        var callCount = 0
    }
}

// ── Factory ───────────────────────────────────────────────────────────────────

private object TestIosWorkerFactory : IosWorkerFactory {
    override fun create(workerClass: String, context: WorkerContext): CoroutineWorker = when (workerClass) {
        SuccessIosWorker::class.simpleName -> SuccessIosWorker(context)
        FailIosWorker::class.simpleName -> FailIosWorker(context)
        SlowIosWorker::class.simpleName -> SlowIosWorker(context)
        ProgressIosWorker::class.simpleName -> ProgressIosWorker(context)
        RetryThenSucceedIosWorker::class.simpleName -> RetryThenSucceedIosWorker(context)
        else -> throw IllegalArgumentException("Unknown worker: $workerClass")
    }
}

// ── Test helper ───────────────────────────────────────────────────────────────

private suspend fun eventually(timeoutMs: Long = 2_000, intervalMs: Long = 30, condition: suspend () -> Boolean) {
    withContext(Dispatchers.Default) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        while (mark.elapsedNow().inWholeMilliseconds < timeoutMs) {
            if (condition()) return@withContext
            delay(intervalMs)
        }
    }
}
