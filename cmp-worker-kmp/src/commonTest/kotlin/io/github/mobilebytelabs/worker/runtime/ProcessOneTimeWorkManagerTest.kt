package io.github.mobilebytelabs.worker.runtime

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo.State
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.registry.workerRegistry
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessOneTimeWorkManagerTest {
    @Test
    fun concurrentKeepStartsOnlyOneWorker() = runTest {
        var starts = 0
        val release = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope) {
            starts++
            release.await()
            WorkResult.Success()
        }
        val requests = List(8) { request().build() }
        val replies = requests.map { request ->
            async(Dispatchers.Default) { manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, request) }
        }.awaitAll()
        val owner = replies.first()

        assertEquals(setOf(owner), replies.toSet())
        runCurrent()
        assertEquals(1, starts)
        assertEquals(State.RUNNING, manager.getWorkInfoById(owner)?.state)
        requests.filter { it.id != owner }.forEach { assertNull(manager.getWorkInfoById(it.id)) }
        release.complete(Unit)
        runCurrent()
        assertEquals(State.SUCCEEDED, manager.getWorkInfoById(owner)?.state)
    }

    @Test
    fun cancellationRetainsOwnerUntilCleanupAndRejectsLateUpdates() = runTest {
        val release = CompletableDeferred<Unit>()
        val start = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope) {
            if (inputData.getInt("version") != 1) {
                WorkResult.Success(inputData)
            } else {
                setProgress(WorkProgress.of(30))
                start.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        release.await()
                        setProgress(WorkProgress.COMPLETE)
                        WorkResult.Success(workDataOf("late" to true))
                    }
                }
            }
        }
        val original = request().setInputData(workDataOf("version" to 1)).build()
        val replacement = request().setInputData(workDataOf("version" to 2)).build()
        try {
            assertEquals(original.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, original))
            start.await()
            manager.cancelWorkById(original.id)
            runCurrent()
            assertEquals(State.CANCELLED, manager.getWorkInfoById(original.id)?.state)
            assertEquals(original.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, replacement))
            assertNull(manager.getWorkInfoById(replacement.id))

            release.complete(Unit)
            runCurrent()
            val cancelled = assertNotNull(manager.getWorkInfoById(original.id))
            assertEquals(State.CANCELLED, cancelled.state)
            assertEquals(30, cancelled.progress.progress)
            assertEquals(WorkData.EMPTY, cancelled.outputData)
            assertEquals(replacement.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, replacement))
            runCurrent()
            assertEquals(2, manager.getWorkInfoById(replacement.id)?.outputData?.getInt("version"))
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun parentScopeCancellationRejectsProgressWhileCleanupStillOwnsName() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope) {
            setProgress(WorkProgress.of(25))
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    setProgress(WorkProgress.of(75))
                    cleanup.complete(Unit)
                    release.await()
                    setProgress(WorkProgress.COMPLETE)
                    WorkResult.Success(workDataOf("late" to true))
                }
            }
        }
        val original = request().build()
        val replacement = request().build()
        try {
            manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, original)
            runCurrent()
            assertEquals(25, manager.getWorkInfoById(original.id)?.progress?.progress)

            backgroundScope.cancel()
            cleanup.await()
            assertEquals(25, manager.getWorkInfoById(original.id)?.progress?.progress)
            assertEquals(original.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, replacement))
            assertNull(manager.getWorkInfoById(replacement.id))

            release.complete(Unit)
            runCurrent()
            val cancelled = assertNotNull(manager.getWorkInfoById(original.id))
            assertEquals(State.CANCELLED, cancelled.state)
            assertEquals(25, cancelled.progress.progress)
            assertEquals(WorkData.EMPTY, cancelled.outputData)
            assertFailsWith<WorkEnqueueException> {
                manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, replacement)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun cancellationBeforeWorkerStartsReleasesUniqueOwner() = runTest {
        val started = mutableListOf<Uuid>()
        val manager = manager(backgroundScope) {
            started += id
            WorkResult.Success()
        }
        val original = request().build()
        val canceller = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.getWorkInfoByIdFlow(original.id).first { it?.state == State.ENQUEUED }
            manager.cancelUniqueWork("backup")
        }

        manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, original)
        canceller.join()
        runCurrent()
        assertTrue(started.isEmpty())
        assertEquals(State.CANCELLED, manager.getWorkInfoById(original.id)?.state)

        val replacement = request().build()
        assertEquals(replacement.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, replacement))
        runCurrent()
        assertEquals(listOf(replacement.id), started)
    }

    @Test
    fun exposesRealProgressAndKeepsTagsSeparateFromUniqueNames() = runTest {
        val release = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope) {
            setProgress(WorkProgress.of(42, inputData))
            release.await()
            WorkResult.Success(inputData)
        }
        val plain = request().addTag("backup").setInputData(workDataOf("records" to 12)).build()
        val named = request().addTag("other").build()

        assertEquals(plain.id, manager.enqueue(plain))
        assertEquals(plain.id, manager.enqueue(plain))
        assertEquals(named.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, named))
        runCurrent()
        val progress = assertNotNull(manager.getWorkInfoByIdFlow(plain.id).first()).progress
        assertEquals(42, progress.progress)
        assertEquals(12, progress.data.getInt("records"))
        assertEquals(listOf(plain.id), manager.getWorkInfosByTag("backup").first().map { it.id })
        assertEquals(listOf(named.id), manager.getWorkInfosForUniqueWorkFlow("backup").first().map { it.id })

        manager.cancelAllWorkByTag("backup")
        assertEquals(State.CANCELLED, manager.getWorkInfoById(plain.id)?.state)
        assertEquals(State.RUNNING, manager.getWorkInfoById(named.id)?.state)
        release.complete(Unit)
        runCurrent()
        assertEquals(State.SUCCEEDED, manager.getWorkInfoById(named.id)?.state)
    }

    @Test
    fun honorsInitialDelayAndRetryBackoffWithFreshAttemptContext() = runTest {
        val attempts = mutableListOf<Pair<Int, Long>>()
        val manager = manager(backgroundScope) {
            attempts += runAttemptCount to testScheduler.currentTime
            if (runAttemptCount <
                2
            ) {
                WorkResult.Retry()
            } else {
                WorkResult.Success(workDataOf("attempt" to runAttemptCount))
            }
        }
        val retry = RetryConfig(maxAttempts = 3, initialDelay = 100.milliseconds, maxDelay = 150.milliseconds)
        val work = request()
            .setInitialDelay(50.milliseconds)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, retry)
            .build()
        manager.enqueue(work)
        advanceTimeBy(49)
        runCurrent()
        assertTrue(attempts.isEmpty())
        assertEquals(State.ENQUEUED, manager.getWorkInfoById(work.id)?.state)

        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf(0 to 50L, 1 to 150L, 2 to 300L), attempts)
        val result = assertNotNull(manager.getWorkInfoById(work.id))
        assertEquals(State.SUCCEEDED, result.state)
        assertEquals(3, result.runAttemptCount)
        assertEquals(2, result.outputData.getInt("attempt"))
    }

    @Test
    fun retryExhaustionFinishesWithLastReason() = runTest {
        val manager = manager(backgroundScope) { WorkResult.Retry("attempt $runAttemptCount") }
        val retry = RetryConfig(maxAttempts = 2, initialDelay = 10.milliseconds, backoffPolicy = BackoffPolicy.LINEAR)
        val work = request().setBackoffCriteria(BackoffPolicy.LINEAR, retry).build()
        manager.enqueue(work)
        runCurrent()
        assertEquals(State.ENQUEUED, manager.getWorkInfoById(work.id)?.state)
        advanceTimeBy(10)
        runCurrent()

        val result = assertNotNull(manager.getWorkInfoById(work.id))
        assertEquals(State.FAILED, result.state)
        assertEquals(2, result.runAttemptCount)
        assertEquals("attempt 1", result.outputData.getString("error"))
    }

    @Test
    fun missingRegistryAndFactoryExceptionBecomeFailedAndReleaseName() = runTest {
        val registry = workerRegistry {
            register("broken") { error("Unable to create fixture.") }
            register("fixture") { context -> TestWorker(context) { WorkResult.Success() } }
        }
        val manager = ProcessOneTimeWorkManager(backgroundScope, registry)
        val missing = request("missing").build()
        val broken = request("broken").build()
        val valid = request().build()

        assertEquals(missing.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, missing))
        runCurrent()
        assertEquals(State.FAILED, manager.getWorkInfoById(missing.id)?.state)
        assertEquals(
            "No worker registered for 'missing'.",
            manager.getWorkInfoById(missing.id)?.outputData?.getString("error"),
        )
        assertEquals(broken.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, broken))
        runCurrent()
        assertEquals(State.FAILED, manager.getWorkInfoById(broken.id)?.state)
        assertEquals("Unable to create fixture.", manager.getWorkInfoById(broken.id)?.outputData?.getString("error"))
        assertEquals(valid.id, manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, valid))
        runCurrent()
        assertEquals(State.SUCCEEDED, manager.getWorkInfoById(valid.id)?.state)
    }

    @Test
    fun rejectsUnsupportedRequestsBeforeChangingQueue() = runTest {
        val manager = manager(backgroundScope) { WorkResult.Success() }
        val owner = request().setInitialDelay(1.minutes).build()
        manager.enqueueUniqueWork("backup", ExistingWorkPolicy.KEEP, owner)

        val candidate = request().build()
        ExistingWorkPolicy.entries.filter { it != ExistingWorkPolicy.KEEP }.forEach { policy ->
            assertFailsWith<WorkEnqueueException> { manager.enqueueUniqueWork("backup", policy, candidate) }
        }
        val periodic = PeriodicWorkRequestBuilder<TestWorker>("fixture", 15.minutes).build()
        assertFailsWith<WorkEnqueueException> {
            manager.enqueueUniquePeriodicWork("backup", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
        val constrained = request().setConstraints(Constraints { setRequiresCharging(true) }).build()
        val expedited = request().setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST).build()
        assertFailsWith<WorkEnqueueException> { manager.enqueue(constrained) }
        assertFailsWith<WorkEnqueueException> { manager.enqueue(expedited) }

        assertEquals(State.ENQUEUED, manager.getWorkInfoById(owner.id)?.state)
        listOf(candidate.id, periodic.id, constrained.id, expedited.id).forEach {
            assertNull(manager.getWorkInfoById(it))
        }
        manager.cancelWorkById(owner.id)
    }

    private fun request(key: String = "fixture") = OneTimeWorkRequestBuilder<TestWorker>(key)

    private fun manager(
        scope: CoroutineScope,
        execute: suspend WorkerContext.() -> WorkResult,
    ): ProcessOneTimeWorkManager = ProcessOneTimeWorkManager(
        scope,
        workerRegistry { register("fixture") { context -> TestWorker(context, execute) } },
    )

    private class TestWorker(context: WorkerContext, private val execute: suspend WorkerContext.() -> WorkResult) :
        CoroutineWorker(context) {
        override suspend fun doWork(): WorkResult = execute(context)
    }
}
