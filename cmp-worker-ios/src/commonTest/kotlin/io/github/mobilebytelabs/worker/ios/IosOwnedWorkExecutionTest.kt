package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class IosOwnedWorkExecutionTest {
    @Test
    fun successWaitsForChildrenAndBodyFinallyEvenAfterTheStateFinishes() = runTest {
        val harness = Harness(this)
        val request = request()
        val releaseChild = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()
        val finallyStarted = CompletableDeferred<Unit>()
        val caller = async {
            harness.enqueue(request) {
                harness.store.transitionToRunning(request.id, periodic = false)
                launch { releaseChild.await() }
                try {
                    harness.store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
                } finally {
                    withContext(NonCancellable) {
                        finallyStarted.complete(Unit)
                        releaseFinally.await()
                    }
                }
            }
        }
        try {
            runCurrent()
            assertTrue(finallyStarted.isCompleted)
            assertEquals(WorkInfo.State.SUCCEEDED, harness.store.getById(request.id)?.state)
            assertFalse(caller.isCompleted)
            releaseFinally.complete(Unit)
            runCurrent()
            assertFalse(caller.isCompleted)
            releaseChild.complete(Unit)
            assertEquals(WorkInfo.State.SUCCEEDED, caller.await().state)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            releaseChild.complete(Unit)
            releaseFinally.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun aBusinessFailureReturnsItsFinalWorkInfo() = runTest {
        val harness = Harness(this)
        val request = request()
        try {
            val info = harness.enqueue(request) {
                harness.store.transitionToRunning(request.id, periodic = false)
                harness.store.incrementAttempt(request.id)
                harness.store.finishWork(request.id, succeeded = false, WorkData.EMPTY)
            }
            assertEquals(WorkInfo.State.FAILED, info.state)
            assertEquals(1, info.runAttemptCount)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun executionExceptionWaitsForItsChildFinallyBeforeBeingRethrown() = runTest {
        val harness = Harness(this)
        val request = request()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            supervisorScope {
                try {
                    val caller = async {
                        harness.enqueue(request) {
                            launch {
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        cleanupStarted.complete(Unit)
                                        releaseCleanup.await()
                                    }
                                }
                            }
                            yield()
                            error("Owned execution failed")
                        }
                    }
                    runCurrent()
                    assertTrue(cleanupStarted.isCompleted)
                    assertFalse(caller.isCompleted)
                    releaseCleanup.complete(Unit)
                    val failure = assertFailsWith<IllegalStateException> { caller.await() }
                    assertEquals("Owned execution failed", failure.message)
                    assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
                    assertTrue(harness.jobs.isEmpty())
                } finally {
                    releaseCleanup.complete(Unit)
                }
            }
        } finally {
            releaseCleanup.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun retryAndItsLastCleanupRemainPartOfTheSameExecution() = runTest {
        val harness = Harness(this)
        val request = request()
        val releaseFinally = CompletableDeferred<Unit>()
        val caller = async {
            harness.enqueue(request) {
                harness.store.transitionToRunning(request.id, periodic = false)
                harness.store.incrementAttempt(request.id)
                harness.store.scheduleRetry(request.id, 200)
                delay(100)
                harness.store.incrementAttempt(request.id)
                harness.store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
                withContext(NonCancellable) { releaseFinally.await() }
            }
        }
        try {
            runCurrent()
            val actualJob = harness.jobs.getValue(request.id)
            assertEquals(1, harness.store.getById(request.id)?.runAttemptCount)
            assertFalse(caller.isCompleted)
            advanceTimeBy(100)
            runCurrent()
            assertSame(actualJob, harness.jobs[request.id])
            assertEquals(2, harness.store.getById(request.id)?.runAttemptCount)
            assertFalse(caller.isCompleted)
            releaseFinally.complete(Unit)
            assertEquals(WorkInfo.State.SUCCEEDED, caller.await().state)
        } finally {
            releaseFinally.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun externalCancellationDoesNotUseTheCancelledStateOrMissingRegistryAsCompletion() = runTest {
        val harness = Harness(this)
        val request = request()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val caller = async {
            harness.enqueue(request) {
                harness.store.transitionToRunning(request.id, periodic = false)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        try {
            runCurrent()
            val actualJob = harness.mutex.withLock {
                harness.store.cancelWork(request.id)
                harness.jobs.remove(request.id)!!
            }
            actualJob.cancel()
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
            assertFalse(actualJob.isCompleted)
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            assertTrue(actualJob.isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun callerCancellationWaitsForTheOwnedChildCleanupOutsideTheMutex() = runTest {
        val harness = Harness(this)
        val request = request()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val caller = async {
            harness.enqueue(request) {
                harness.store.transitionToRunning(request.id, periodic = false)
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            harness.mutex.withLock { cleanupStarted.complete(Unit) }
                            releaseCleanup.await()
                        }
                    }
                }
                awaitCancellation()
            }
        }
        try {
            runCurrent()
            val actualJob = harness.jobs.getValue(request.id)
            caller.cancel()
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            caller.join()
            assertTrue(actualJob.isCompleted)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun cancellationDuringRegistrationKeepsTheExactJobReferenceAndNeverStartsItsBody() = runTest {
        val persistence = GatedPersistence()
        val harness = Harness(this, persistence)
        val request = request()
        var ran = false
        val caller = async { harness.enqueue(request) { ran = true } }
        try {
            runCurrent()
            assertTrue(persistence.saveStarted.isCompleted)
            val actualJob = harness.jobs.getValue(request.id)
            caller.cancel()
            runCurrent()
            assertFalse(caller.isCompleted)
            persistence.releaseSave.complete(Unit)
            caller.join()
            assertFalse(ran)
            assertTrue(actualJob.isCompleted)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
            assertTrue(persistence.memory.loadRecords().isEmpty())
        } finally {
            persistence.releaseSave.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun cancellationDuringFailedRegistrationStaysPrimaryAndRetainsTheSaveFailure() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val memory = InMemoryIosWorkPersistence()
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))
            override suspend fun loadAll(): List<WorkInfo> = memory.loadAll()
            override suspend fun delete(id: Uuid) = memory.delete(id)
            override suspend fun saveRecord(record: StoredWork) {
                memory.saveRecord(record)
                saveStarted.complete(Unit)
                releaseSave.await()
                error("Registration save failed")
            }
        }
        val harness = Harness(this, persistence)
        val request = request()
        var ran = false
        var completion: Throwable? = null
        val caller = async { harness.enqueue(request) { ran = true } }
        caller.invokeOnCompletion { completion = it }
        try {
            runCurrent()
            assertTrue(saveStarted.isCompleted)
            caller.cancel(CancellationException("Caller cancelled registration"))
            releaseSave.complete(Unit)
            caller.join()
            val primary = assertIs<CancellationException>(completion)
            assertEquals("Caller cancelled registration", primary.message)
            assertTrue(primary.suppressedExceptions.any { it.message == "Registration save failed" })
            assertFalse(ran)
            assertTrue(memory.loadRecords().isEmpty())
            assertTrue(harness.jobs.isEmpty())
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
        } finally {
            releaseSave.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun alreadyCancelledCallerDoesNotRegisterAnything() = runTest {
        val harness = Harness(this)
        val request = request()
        var ran = false
        try {
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                harness.enqueue(request) { ran = true }
            }
            caller.join()
            assertFalse(ran)
            assertNull(harness.store.getById(request.id))
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cancellationWhileWaitingForRegistrationMutexDoesNotClaimTheId() = runTest {
        val harness = Harness(this)
        val request = request()
        var ran = false
        harness.mutex.lock()
        var locked = true
        val caller = async { harness.enqueue(request) { ran = true } }
        try {
            runCurrent()
            caller.cancel()
            harness.mutex.unlock()
            locked = false
            caller.join()
            assertFalse(ran)
            assertNull(harness.store.getById(request.id))
            assertTrue(harness.jobs.isEmpty())
        } finally {
            if (locked) harness.mutex.unlock()
            harness.shutdown()
        }
    }

    @Test
    fun aFactoryReturningAfterCancellationNeverEntersBusinessAndStillWaitsForFinally() = runTest {
        val harness = Harness(this)
        val request = request()
        val factoryReturned = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var ran = false
        val caller = async {
            harness.enqueue(request) {
                try {
                    val business = createActiveIosWorker {
                        harness.jobs.getValue(request.id).cancel()
                        factoryReturned.complete(Unit)
                        val work = { ran = true }
                        work
                    }
                    business()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
        }
        try {
            runCurrent()
            assertTrue(factoryReturned.isCompleted)
            assertFalse(ran)
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
        } finally {
            releaseCleanup.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun validationFailureDoesNotCreateAJobOrState() = runTest {
        val harness = Harness(this, validate = { error("Invalid owned request") })
        val request = request()
        try {
            val failure = assertFailsWith<IllegalStateException> { harness.enqueue(request) {} }
            assertEquals("Invalid owned request", failure.message)
            assertNull(harness.store.getById(request.id))
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun failedRegistrationDeletesThePartialPersistenceAndCancelsTheUnstartedJob() = runTest {
        val persistence = object : IosWorkPersistence {
            val memory = InMemoryIosWorkPersistence()
            override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))
            override suspend fun loadAll(): List<WorkInfo> = memory.loadAll()
            override suspend fun delete(id: Uuid) = memory.delete(id)
            override suspend fun saveRecord(record: StoredWork) {
                memory.saveRecord(record)
                error("Registration save failed")
            }
        }
        val harness = Harness(this, persistence)
        val request = request()
        var ran = false
        try {
            val failure = assertFailsWith<IllegalStateException> { harness.enqueue(request) { ran = true } }
            assertEquals("Registration save failed", failure.message)
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
            assertTrue(persistence.memory.loadRecords().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun retryPersistenceIsAwaitedAndItsFailureCannotEscapeToTheManagerScope() = runTest {
        val retrySaveStarted = CompletableDeferred<Unit>()
        val releaseRetrySave = CompletableDeferred<Unit>()
        val memory = InMemoryIosWorkPersistence()
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = saveRecord(StoredWork(info))
            override suspend fun loadAll(): List<WorkInfo> = memory.loadAll()
            override suspend fun delete(id: Uuid) = memory.delete(id)
            override suspend fun saveRecord(record: StoredWork) {
                memory.saveRecord(record)
                if (record.info.runAttemptCount > 0) {
                    retrySaveStarted.complete(Unit)
                    releaseRetrySave.await()
                    error("Retry save failed")
                }
            }
        }
        val harness = Harness(this, persistence)
        val request = request()
        try {
            supervisorScope {
                try {
                    val caller = async {
                        harness.enqueue(request) {
                            harness.store.transitionToRunning(request.id, periodic = false)
                            harness.store.incrementAttempt(request.id)
                            harness.store.scheduleRetry(request.id, 200)
                            error("Retry save should have failed")
                        }
                    }
                    runCurrent()
                    assertTrue(retrySaveStarted.isCompleted)
                    assertFalse(caller.isCompleted)
                    releaseRetrySave.complete(Unit)
                    val failure = assertFailsWith<IllegalStateException> { caller.await() }
                    assertEquals("Retry save failed", failure.message)
                    runCurrent()
                    assertTrue(memory.loadRecords().isEmpty())
                    assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
                    assertTrue(harness.jobs.isEmpty())
                } finally {
                    releaseRetrySave.complete(Unit)
                }
            }
        } finally {
            releaseRetrySave.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun finalDeleteFailureIsReturnedAfterSuccessAndDoesNotStartADetachedWrite() = runTest {
        var deletes = 0
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = Unit
            override suspend fun loadAll(): List<WorkInfo> = emptyList()
            override suspend fun delete(id: Uuid) {
                deletes++
                error("Final delete failed")
            }
        }
        val harness = Harness(this, persistence)
        val request = request()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                harness.enqueue(request) {
                    harness.store.transitionToRunning(request.id, periodic = false)
                    harness.store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
                }
            }
            assertEquals("Final delete failed", failure.message)
            runCurrent()
            assertEquals(1, deletes)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun callerCancellationKeepsWorkerAndPersistenceCleanupFailuresSuppressed() = runTest {
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = Unit
            override suspend fun loadAll(): List<WorkInfo> = emptyList()
            override suspend fun delete(id: Uuid): Unit = error("Final delete failed")
        }
        val harness = Harness(this, persistence)
        val request = request()
        val cancellation = CancellationException("Caller cancelled")
        var completion: Throwable? = null
        val caller = async {
            harness.enqueue(request) {
                try {
                    awaitCancellation()
                } finally {
                    error("Worker cleanup failed")
                }
            }
        }
        caller.invokeOnCompletion { completion = it }
        try {
            runCurrent()
            caller.cancel(cancellation)
            caller.join()
            val primary = assertIs<CancellationException>(completion)
            assertEquals(cancellation.message, primary.message)
            val workerFailure = primary.suppressedExceptions.single()
            assertEquals("Worker cleanup failed", workerFailure.message)
            assertEquals("Final delete failed", workerFailure.suppressedExceptions.single().message)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun existingActiveOrFinishedIdsAreRejectedWithoutCancellingTheirJobs() = runTest {
        for (finished in listOf(false, true)) {
            val harness = Harness(this)
            val request = request()
            val previousJob = Job()
            try {
                harness.store.initWork(request.id, request.tags, request = request)
                if (finished) {
                    harness.store.transitionToRunning(request.id, periodic = false)
                    harness.store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
                } else {
                    harness.jobs[request.id] = previousJob
                }
                val before = harness.store.getById(request.id)
                assertFailsWith<WorkEnqueueException> { harness.enqueue(request) {} }
                assertEquals(before, harness.store.getById(request.id))
                assertTrue(previousJob.isActive)
                if (!finished) assertSame(previousJob, harness.jobs[request.id])
            } finally {
                previousJob.cancel()
                harness.shutdown()
            }
        }
    }

    @Test
    fun cleanupDoesNotRemoveOrCancelAnUnrelatedRegistryReference() = runTest {
        val harness = Harness(this)
        val request = request()
        val unrelatedJob = Job()
        val caller = async { harness.enqueue(request) { awaitCancellation() } }
        try {
            runCurrent()
            val actualJob = harness.jobs.getValue(request.id)
            harness.mutex.withLock { harness.jobs[request.id] = unrelatedJob }
            caller.cancel()
            caller.join()
            assertTrue(actualJob.isCompleted)
            assertSame(unrelatedJob, harness.jobs[request.id])
            assertTrue(unrelatedJob.isActive)
        } finally {
            unrelatedJob.cancel()
            harness.shutdown()
        }
    }

    @Test
    fun shutdownBeforeStartingTheLazyJobStillSettlesCancelledAndRemovesRegistration() = runTest {
        val persistence = GatedPersistence()
        val harness = Harness(this, persistence)
        val request = request()
        var ran = false
        val caller = async { harness.enqueue(request) { ran = true } }
        try {
            runCurrent()
            val actualJob = harness.jobs.getValue(request.id)
            harness.shutdown()
            persistence.releaseSave.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            assertFalse(ran)
            assertTrue(actualJob.isCompleted)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
            assertTrue(persistence.memory.loadRecords().isEmpty())
        } finally {
            persistence.releaseSave.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun alreadyShutdownScopeDoesNotHangOrRunTheBody() = runTest {
        val harness = Harness(this)
        val request = request()
        var ran = false
        try {
            harness.shutdown()
            assertFailsWith<CancellationException> { harness.enqueue(request) { ran = true } }
            assertFalse(ran)
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
            assertTrue(harness.jobs.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun shutdownAfterStartingWaitsForCleanupInsteadOfReturningAtCancellation() = runTest {
        val harness = Harness(this)
        val request = request()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val caller = async {
            harness.enqueue(request) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        try {
            runCurrent()
            harness.shutdown()
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertFalse(caller.isCompleted)
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            assertTrue(harness.jobs.isEmpty())
            assertEquals(WorkInfo.State.CANCELLED, harness.store.getById(request.id)?.state)
        } finally {
            releaseCleanup.complete(Unit)
            harness.shutdown()
        }
    }

    @Test
    fun returnAlsoWaitsForPersistenceCleanupAfterTheActualJobCompletes() = runTest {
        val releaseDelete = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) = Unit
            override suspend fun loadAll(): List<WorkInfo> = emptyList()
            override suspend fun delete(id: Uuid) {
                deleteStarted.complete(Unit)
                releaseDelete.await()
            }
        }
        val harness = Harness(this, persistence)
        val request = request()
        lateinit var actualJob: Job
        val caller = async {
            harness.enqueue(request) {
                actualJob = currentCoroutineContext()[Job]!!
                harness.store.transitionToRunning(request.id, periodic = false)
                harness.store.finishWork(request.id, succeeded = true, WorkData.EMPTY)
            }
        }
        try {
            runCurrent()
            assertTrue(actualJob.isCompleted)
            assertTrue(deleteStarted.isCompleted)
            assertFalse(caller.isCompleted)
            releaseDelete.complete(Unit)
            assertEquals(WorkInfo.State.SUCCEEDED, caller.await().state)
        } finally {
            releaseDelete.complete(Unit)
            harness.shutdown()
        }
    }

    private class Harness(
        testScope: TestScope,
        persistence: IosWorkPersistence = InMemoryIosWorkPersistence(),
        validate: (WorkRequest) -> Unit = {},
    ) {
        private val owner = SupervisorJob()
        val scope = CoroutineScope(testScope.coroutineContext + owner)
        val store = IosWorkStateStore(persistence, scope)
        val mutex = Mutex()
        val jobs = mutableMapOf<Uuid, Job>()
        private val execution = IosOwnedWorkExecution(scope, store, mutex, jobs, validate, { 100 }, {})

        suspend fun enqueue(request: OneTimeWorkRequest, execute: suspend CoroutineScope.() -> Unit): WorkInfo =
            execution.enqueueAndAwait(request, execute)

        fun shutdown() = owner.cancel()
    }

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

    private fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CoroutineWorker>("OwnedWorker").build()
}
