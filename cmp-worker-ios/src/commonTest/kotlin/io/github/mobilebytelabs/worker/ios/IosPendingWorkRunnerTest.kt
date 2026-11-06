package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class IosPendingWorkRunnerTest {
    @Test
    fun persistedRequestIsActuallyExecutedAndRemovedAfterSuccess() = runTest {
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInputData(WorkData("checkpoint" to Long.MAX_VALUE))
        }.build()
        val persistence = InMemoryIosWorkPersistence()
        val encoded = IosWorkRecordCodec.encode(
            IosWorkStateStore.StoredWork(
                WorkInfo(request.id, WorkInfo.State.ENQUEUED),
                request = request,
                nextRunAtEpochMillis = 100,
            ),
        )
        persistence.saveRecord(assertNotNull(IosWorkRecordCodec.decode(encoded)))
        val restored = IosWorkStateStore(persistence)
        restored.restoreFromPersistence()
        val executed = mutableListOf<Uuid>()
        val runner = IosPendingWorkRunner(restored, Mutex(), mutableMapOf(), { 100 }, { true }) {
            assertEquals(Long.MAX_VALUE, it.inputData.getLong("checkpoint"))
            executed += it.id
            restored.transitionToRunning(it.id, periodic = false)
            restored.finishWork(it.id, succeeded = true, WorkData.EMPTY)
        }

        assertTrue(runner.run())
        assertEquals(listOf(request.id), executed)
        assertEquals(WorkInfo.State.SUCCEEDED, restored.getById(request.id)?.state)
        assertTrue(persistence.loadRecords().isEmpty())
    }

    @Test
    fun restoredRunningAndAttemptedWorkDoesNotWaitItsInitialDelayAgain() = runTest {
        for ((savedState, attempts) in listOf(
            WorkInfo.State.RUNNING to 0,
            WorkInfo.State.RUNNING to 1,
            WorkInfo.State.ENQUEUED to 1,
        )) {
            val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
                setInitialDelay(24.hours)
            }.build()
            val encoded = IosWorkRecordCodec.encode(
                IosWorkStateStore.StoredWork(
                    WorkInfo(request.id, savedState, runAttemptCount = attempts),
                    request = request,
                ),
            )
            val persistence = InMemoryIosWorkPersistence()
            persistence.saveRecord(assertNotNull(IosWorkRecordCodec.decode(encoded)))
            val restored = IosWorkStateStore(persistence)
            restored.restoreFromPersistence()
            restored.prepareRestoredDeadlines(100)
            assertTrue(assertNotNull(restored.snapshot()[request.id]?.nextRunAtEpochMillis) <= 100)
            var executed = false
            val runner = IosPendingWorkRunner(restored, Mutex(), mutableMapOf(), { 100 }, { true }) {
                executed = true
                restored.transitionToRunning(it.id, periodic = false)
                restored.finishWork(it.id, succeeded = true, WorkData.EMPTY)
            }
            assertTrue(runner.run())
            assertTrue(executed)
            assertEquals(WorkInfo.State.SUCCEEDED, restored.getById(request.id)?.state)
        }
    }

    @Test
    fun restoredRunningBackoffRetainsTheFutureDeadlineDespiteLongInitialDelay() = runTest {
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInitialDelay(24.hours)
        }.build()
        val encoded = IosWorkRecordCodec.encode(
            IosWorkStateStore.StoredWork(
                WorkInfo(request.id, WorkInfo.State.RUNNING, runAttemptCount = 1),
                request = request,
                nextRunAtEpochMillis = 200,
            ),
        )
        val persistence = InMemoryIosWorkPersistence()
        persistence.saveRecord(assertNotNull(IosWorkRecordCodec.decode(encoded)))
        val restored = IosWorkStateStore(persistence)
        restored.restoreFromPersistence()
        restored.prepareRestoredDeadlines(100)
        assertEquals(200L, restored.snapshot()[request.id]?.nextRunAtEpochMillis)
        var now = 199L
        var executions = 0
        val runner = IosPendingWorkRunner(restored, Mutex(), mutableMapOf(), { now }, { true }) {
            executions++
            restored.transitionToRunning(it.id, periodic = false)
            restored.finishWork(it.id, succeeded = true, WorkData.EMPTY)
        }
        assertTrue(runner.run())
        assertEquals(0, executions)
        now = 200
        assertTrue(runner.run())
        assertEquals(1, executions)
    }

    @Test
    fun futureIneligibleLegacyAndLiveJobsAreNotExecuted() = runTest {
        val store = IosWorkStateStore()
        val due = oneTime()
        val future = oneTime()
        val gated = oneTime()
        val live = oneTime()
        store.initWork(due.id, emptySet(), request = due, nextRunAtEpochMillis = 100)
        store.initWork(future.id, emptySet(), request = future, nextRunAtEpochMillis = 101)
        store.initWork(gated.id, emptySet(), request = gated, nextRunAtEpochMillis = 100)
        store.initWork(live.id, emptySet(), request = live, nextRunAtEpochMillis = 100)
        store.initWork(Uuid.random(), emptySet())
        val existing = launch { awaitCancellation() }
        val jobs = mutableMapOf(live.id to existing)
        val executed = mutableListOf<Uuid>()
        try {
            val runner = IosPendingWorkRunner(store, Mutex(), jobs, { 100 }, { it.id != gated.id }) {
                executed += it.id
                store.transitionToRunning(it.id, periodic = false)
                store.finishWork(it.id, succeeded = true, WorkData.EMPTY)
            }
            assertTrue(runner.run())
            assertEquals(listOf(due.id), executed)
            assertTrue(jobs[live.id] === existing)
            assertTrue(existing.isActive)
        } finally {
            existing.cancelAndJoin()
        }
    }

    @Test
    fun expiryWaitsForCleanupAndRestoresTheDurableQueueBeforeCompletion() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val request = oneTime()
        val store = IosWorkStateStore(persistence)
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 100)
        store.flush()
        val jobs = mutableMapOf<Uuid, Job>()
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val results = mutableListOf<Boolean>()
        lateinit var expire: () -> Unit
        val runner = IosPendingWorkRunner(store, Mutex(), jobs, { 100 }, { true }) {
            store.transitionToRunning(it.id, periodic = false)
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        launchBgTask(this, installExpirationHandler = { expire = it }, complete = { results += it }) {
            runner.run()
        }
        started.await()
        expire()
        runCurrent()
        assertTrue(results.isEmpty())
        assertTrue(jobs.isNotEmpty())
        assertEquals(WorkInfo.State.RUNNING, store.getById(request.id)?.state)

        cleanup.complete(Unit)
        runCurrent()
        assertEquals(listOf(false), results)
        assertTrue(jobs.isEmpty())
        val reopened = IosWorkStateStore(persistence)
        reopened.restoreFromPersistence()
        assertEquals(WorkInfo.State.ENQUEUED, reopened.getById(request.id)?.state)
        val queued = assertNotNull(reopened.snapshot()[request.id])
        assertNotNull(queued.request)
        assertEquals(100L, queued.nextRunAtEpochMillis)
        var resumed = false
        val nextWindow = IosPendingWorkRunner(reopened, Mutex(), mutableMapOf(), { 100 }, { true }) {
            resumed = true
            reopened.transitionToRunning(it.id, periodic = false)
            reopened.finishWork(it.id, succeeded = true, WorkData.EMPTY)
        }
        assertTrue(nextWindow.run())
        assertTrue(resumed)
        assertEquals(WorkInfo.State.SUCCEEDED, reopened.getById(request.id)?.state)
    }

    @Test
    fun cancelledBeforeLazyStartLeavesNoRegistryEntryOrFalseCompletion() = runTest {
        val store = IosWorkStateStore()
        val request = oneTime()
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 100)
        lateinit var expire: () -> Unit
        val backing = mutableMapOf<Uuid, Job>()
        val jobs = object : MutableMap<Uuid, Job> by backing {
            override fun put(key: Uuid, value: Job): Job? {
                val previous = backing.put(key, value)
                expire()
                return previous
            }
        }
        var executed = false
        val results = mutableListOf<Boolean>()
        val runner = IosPendingWorkRunner(store, Mutex(), jobs, { 100 }, { true }) {
            executed = true
            true
        }
        launchBgTask(this, installExpirationHandler = { expire = it }, complete = { results += it }) {
            runner.run()
        }
        runCurrent()

        assertFalse(executed)
        assertTrue(jobs.isEmpty())
        assertEquals(listOf(false), results)
        assertEquals(WorkInfo.State.ENQUEUED, store.getById(request.id)?.state)
    }

    @Test
    fun explicitCancellationStaysTerminalAndAnotherWindowDoesNotRepeatIt() = runTest {
        val store = IosWorkStateStore()
        val request = oneTime()
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 100)
        val jobs = mutableMapOf<Uuid, Job>()
        val began = CompletableDeferred<Unit>()
        var executions = 0
        val runner = IosPendingWorkRunner(store, Mutex(), jobs, { 100 }, { true }) {
            executions++
            store.transitionToRunning(it.id, periodic = false)
            began.complete(Unit)
            awaitCancellation()
        }
        val window = async { runner.run() }
        began.await()
        assertTrue(store.cancelWork(request.id))
        jobs.getValue(request.id).cancel()
        assertFalse(window.await())
        assertEquals(WorkInfo.State.CANCELLED, store.getById(request.id)?.state)
        assertTrue(runner.run())
        assertEquals(1, executions)
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun periodicRequestRunsOncePerWindowAndResumesItsNextDueRound() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val request = PeriodicWorkRequestBuilder<CoroutineWorker>("BackupWorker", 2.hours).build()
        var now = 100L
        var executions = 0
        var store = IosWorkStateStore(persistence)
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = now)
        suspend fun window(): Boolean = IosPendingWorkRunner(store, Mutex(), mutableMapOf(), { now }, { true }) {
            assertIs<PeriodicWorkRequest>(it)
            executions++
            store.transitionToRunning(it.id, periodic = true)
            store.finishWork(it.id, succeeded = true, WorkData.EMPTY, iosDueAtEpochMillis(now, it.repeatInterval))
        }.run()
        assertTrue(window())
        assertEquals(1, executions)
        store = IosWorkStateStore(persistence).also { it.restoreFromPersistence() }
        assertTrue(window())
        assertEquals(1, executions)
        now += request.repeatInterval.inWholeMilliseconds
        assertTrue(window())
        assertEquals(2, executions)
    }

    @Test
    fun simultaneousWindowsUseTheSameJobRegistryAndDoNotDuplicateExecution() = runTest {
        val store = IosWorkStateStore()
        val request = oneTime()
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 100)
        val jobs = mutableMapOf<Uuid, Job>()
        val mutex = Mutex()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val runner = IosPendingWorkRunner(store, mutex, jobs, { 100 }, { true }) {
            executions++
            store.transitionToRunning(it.id, periodic = false)
            started.complete(Unit)
            release.await()
            store.finishWork(it.id, succeeded = true, WorkData.EMPTY)
        }
        val first = async { runner.run() }
        started.await()
        assertTrue(runner.run())
        assertEquals(1, executions)
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun interruptedBackoffKeepsItsFutureDeadlineAndCanRunInALaterWindow() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val store = IosWorkStateStore(persistence)
        val request = oneTime()
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 100)
        assertTrue(store.transitionToRunning(request.id, periodic = false))
        assertTrue(store.scheduleRetry(request.id, 200))
        assertTrue(store.requeueInterrupted(request.id, 150))
        store.flush()

        val reopened = IosWorkStateStore(persistence)
        reopened.restoreFromPersistence()
        assertEquals(200L, reopened.snapshot()[request.id]?.nextRunAtEpochMillis)
        var now = 199L
        var runs = 0
        val runner = IosPendingWorkRunner(reopened, Mutex(), mutableMapOf(), { now }, { true }) {
            runs++
            reopened.transitionToRunning(it.id, periodic = false)
            reopened.finishWork(it.id, succeeded = true, WorkData.EMPTY)
        }
        assertTrue(runner.run())
        assertEquals(0, runs)
        now = 200
        assertTrue(runner.run())
        assertEquals(1, runs)
    }

    @Test
    fun backgroundPlanKeepsRunningWorkAndItsRequiredConditionsUntilTerminal() = runTest {
        val store = IosWorkStateStore()
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setConstraints(
                Constraints {
                    setRequiredNetworkType(NetworkType.CONNECTED)
                    setRequiresCharging(true)
                },
            )
        }.build()
        store.initWork(request.id, emptySet(), request = request, nextRunAtEpochMillis = 50)
        assertTrue(store.transitionToRunning(request.id, periodic = false))
        assertEquals(null, store.snapshot()[request.id]?.nextRunAtEpochMillis)
        val runningPlan = assertNotNull(iosBackgroundWake(store.snapshot().values, 100))
        assertEquals(100L, runningPlan.earliestAtEpochMillis)
        assertTrue(runningPlan.requiresNetwork)
        assertTrue(runningPlan.requiresCharging)

        store.scheduleRetry(request.id, 200)
        assertEquals(200L, iosBackgroundWake(store.snapshot().values, 150)?.earliestAtEpochMillis)
        store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
        assertEquals(null, iosBackgroundWake(store.snapshot().values, 150))
    }

    private fun oneTime(): WorkRequest = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").build()
}
