package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.PersistenceMode
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.UniqueWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.WorkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IosWorkStateStoreTest {

    private fun store() = IosWorkStateStore(persistence = InMemoryIosWorkPersistence())

    @Test
    fun initWork_surfacesEnqueuedState() = runTest {
        val s = store()
        val id = Uuid.random()
        s.initWork(id, emptySet())
        val info = s.getById(id)
        assertEquals(WorkInfo.State.ENQUEUED, info?.state)
    }

    @Test
    fun initWork_defaultStillSchedulesPersistenceOnTheExistingScope() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val s = IosWorkStateStore(persistence, backgroundScope)
        val id = Uuid.random()
        s.initWork(id, emptySet())
        assertTrue(persistence.loadAll().isEmpty())
        testScheduler.runCurrent()
        assertEquals(listOf(s.getById(id)), persistence.loadAll())
    }

    @Test
    fun initWork_ownedRegistrationLeavesPersistenceAndItsFailureToTheAwaitingCaller() = runTest {
        var saves = 0
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) {
                saves++
                error("Owned registration save failed")
            }

            override suspend fun loadAll(): List<WorkInfo> = emptyList()
            override suspend fun delete(id: Uuid) = Unit
        }
        val s = IosWorkStateStore(persistence, backgroundScope)
        val id = Uuid.random()
        s.initWork(id, emptySet(), persistenceMode = PersistenceMode.OWNED)
        testScheduler.runCurrent()
        assertEquals(0, saves)
        val failure = assertFailsWith<IllegalStateException> { s.persistCurrent(id) }
        assertEquals("Owned registration save failed", failure.message)
        assertEquals(1, saves)
    }

    @Test
    fun ownedModeSuppressesEveryAutomaticMutationButRetainsExplicitRetryAndFinalWrites() = runTest {
        var saves = 0
        var deletes = 0
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) {
                saves++
            }

            override suspend fun loadAll(): List<WorkInfo> = emptyList()

            override suspend fun delete(id: Uuid) {
                deletes++
            }
        }
        val s = IosWorkStateStore(persistence, backgroundScope)
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("OwnedWorker").build()
        val id = request.id
        s.initWork(id, emptySet(), request = request, persistenceMode = PersistenceMode.OWNED)
        s.prepareRestoredDeadlines(100)
        s.refreshEnqueuedWork(id)
        assertTrue(s.transitionToRunning(id, periodic = false))
        assertTrue(s.incrementAttempt(id))
        s.updateProgress(id, WorkProgress(50))
        assertTrue(s.requeueInterrupted(id, 100))
        assertTrue(s.transitionToRunning(id, periodic = false))
        testScheduler.runCurrent()
        assertEquals(0, saves)
        assertEquals(0, deletes)

        assertTrue(s.scheduleRetry(id, 200))
        assertEquals(1, saves)
        assertTrue(s.finishWork(id, succeeded = true, WorkData.EMPTY))
        assertTrue(s.cancelWork(id, periodic = true))
        testScheduler.runCurrent()
        assertEquals(1, saves)
        assertEquals(0, deletes)
        assertEquals(PersistenceMode.OWNED, s.snapshot()[id]?.persistenceMode)
        s.persistCurrent(id)
        assertEquals(1, deletes)
    }

    @Test
    fun restoreResetsOwnedModeEvenWhenPersistenceReturnsTheOriginalRecordObject() = runTest {
        val id = Uuid.random()
        val original = StoredWork(WorkInfo(id, WorkInfo.State.RUNNING), persistenceMode = PersistenceMode.OWNED)
        var saves = 0
        val persistence = object : IosWorkPersistence {
            override suspend fun save(info: WorkInfo) {
                saves++
            }

            override suspend fun loadAll(): List<WorkInfo> = listOf(original.info)
            override suspend fun loadRecords(): List<StoredWork> = listOf(original)
            override suspend fun delete(id: Uuid) = Unit
        }
        val s = IosWorkStateStore(persistence, backgroundScope)
        s.restoreFromPersistence()
        assertEquals(PersistenceMode.AUTOMATIC, s.snapshot()[id]?.persistenceMode)
        assertEquals(WorkInfo.State.ENQUEUED, s.getById(id)?.state)
        assertEquals(PersistenceMode.OWNED, original.persistenceMode)
        assertTrue(s.transitionToRunning(id, periodic = false))
        testScheduler.runCurrent()
        assertEquals(1, saves)
    }

    @Test
    fun getById_unknownId_returnsNull() = runTest {
        val s = store()
        assertNull(s.getById(Uuid.random()))
    }

    @Test
    fun finishWork_afterRunning_reflectsInSnapshot() = runTest {
        val s = store()
        val id = Uuid.random()
        s.initWork(id, emptySet())
        assertTrue(s.transitionToRunning(id, periodic = false))
        val output = WorkData("ready" to true)
        assertTrue(s.finishWork(id, succeeded = true, outputData = output))
        val info = assertNotNull(s.getById(id))
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(output, info.outputData)
        assertFalse(s.cancelWork(id))
        assertFalse(s.transitionToRunning(id, periodic = false))
        assertEquals(info, s.getById(id))
    }

    @Test
    fun cancelWork_rejectsAllLateUpdates() = runTest {
        val s = store()
        val id = Uuid.random()
        s.initWork(id, emptySet())
        assertTrue(s.transitionToRunning(id, periodic = false))
        assertTrue(s.incrementAttempt(id))
        s.updateProgress(id, WorkProgress(25))

        assertTrue(s.cancelWork(id))
        val cancelled = assertNotNull(s.getById(id))
        assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
        assertEquals(1, cancelled.runAttemptCount)
        assertEquals(WorkProgress(25), cancelled.progress)
        val lateData = WorkData("late" to true)
        assertFalse(s.finishWork(id, succeeded = true, outputData = lateData))
        assertFalse(s.finishWork(id, succeeded = false, outputData = lateData))
        assertFalse(s.incrementAttempt(id))
        s.updateProgress(id, WorkProgress.COMPLETE)
        s.refreshEnqueuedWork(id)
        assertFalse(s.transitionToRunning(id, periodic = true))
        assertFalse(s.cancelWork(id, periodic = true))
        assertEquals(cancelled, s.getById(id))
    }

    @Test
    fun queuedWork_cannotFinishOrUpdateProgressBeforeStarting() = runTest {
        val s = store()
        val id = Uuid.random()
        s.initWork(id, emptySet())
        val queued = assertNotNull(s.getById(id))
        assertFalse(s.finishWork(id, succeeded = false, outputData = WorkData.EMPTY))
        assertFalse(s.incrementAttempt(id))
        s.updateProgress(id, WorkProgress.COMPLETE)
        assertEquals(queued, s.getById(id))
        assertTrue(s.cancelWork(id))
        assertFalse(s.transitionToRunning(id, periodic = false))
    }

    @Test
    fun livePeriodicWork_canRerunAndCancelBetweenCycles() = runTest {
        for (succeeded in listOf(true, false)) {
            val s = store()
            val id = Uuid.random()
            s.initWork(id, emptySet())
            repeat(2) {
                assertTrue(s.transitionToRunning(id, periodic = true))
                assertFalse(s.transitionToRunning(id, periodic = true))
                assertTrue(s.incrementAttempt(id))
                assertTrue(s.finishWork(id, succeeded = succeeded, outputData = WorkData.EMPTY))
            }
            assertTrue(s.cancelWork(id, periodic = true))
            assertFalse(s.transitionToRunning(id, periodic = true))
            val cancelled = assertNotNull(s.getById(id))
            assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
            assertEquals(2, cancelled.runAttemptCount)
        }
    }

    @Test
    fun lateUpdates_neverCreateAnUnknownRecord() = runTest {
        val s = store()
        val id = Uuid.random()
        assertFalse(s.transitionToRunning(id, periodic = true))
        assertFalse(s.finishWork(id, succeeded = true, outputData = WorkData.EMPTY))
        assertFalse(s.cancelWork(id, periodic = true))
        assertFalse(s.incrementAttempt(id))
        s.updateProgress(id, WorkProgress.COMPLETE)
        s.refreshEnqueuedWork(id)
        assertNull(s.getById(id))
    }

    @Test
    fun cancellationAndCompletion_haveOnlyOneWinner() = runTest {
        repeat(20) {
            val s = store()
            val id = Uuid.random()
            s.initWork(id, emptySet())
            assertTrue(s.transitionToRunning(id, periodic = false))
            val start = CompletableDeferred<Unit>()
            val finish = async(Dispatchers.Default) {
                start.await()
                s.finishWork(id, succeeded = true, outputData = WorkData.EMPTY)
            }
            val cancel = async(Dispatchers.Default) {
                start.await()
                s.cancelWork(id)
            }
            start.complete(Unit)
            val finished = finish.await()
            assertNotEquals(finished, cancel.await())
            val expected = if (finished) WorkInfo.State.SUCCEEDED else WorkInfo.State.CANCELLED
            assertEquals(expected, s.getById(id)?.state)
        }
    }

    @Test
    fun uniqueKeep_concurrentRequestsReturnTheSameEffectiveId() = runTest {
        repeat(20) {
            val s = store()
            val firstId = Uuid.random()
            val secondId = Uuid.random()
            val start = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) {
                start.await()
                s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, firstId, emptySet())
            }
            val second = async(Dispatchers.Default) {
                start.await()
                s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, secondId, emptySet())
            }
            start.complete(Unit)
            val results = listOf(first.await(), second.await())
            val accepted = results.single { it.enqueued }
            assertEquals(listOf(accepted.id, accepted.id), results.map { it.id })
            assertEquals(setOf(accepted.id), s.snapshot().keys)
            assertEquals(listOf(accepted.id), s.observeByUniqueName("backup").first().map { it.id })
        }
    }

    @Test
    fun uniqueReplace_cancelsRunningWorkAndRejectsItsLateUpdates() = runTest {
        val s = store()
        val current = Uuid.random()
        val replacement = Uuid.random()
        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, current, emptySet())
        assertTrue(s.transitionToRunning(current, periodic = false))
        s.updateProgress(current, WorkProgress(25))

        val result = s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, replacement, setOf("manual"))

        assertEquals(replacement, result.id)
        assertEquals(setOf(current), result.replacedIds)
        assertEquals(WorkInfo.State.CANCELLED, s.getById(current)?.state)
        assertEquals(WorkInfo.State.ENQUEUED, s.getById(replacement)?.state)
        assertFalse(s.finishWork(current, succeeded = true, outputData = WorkData("late" to true)))
        assertFalse(s.incrementAttempt(current))
        s.updateProgress(current, WorkProgress.COMPLETE)
        assertEquals(WorkProgress(25), s.getById(current)?.progress)
        assertEquals(
            listOf(replacement),
            s.observeByUniqueName("backup").first().filterNot {
                it.isFinished
            }.map { it.id },
        )
    }

    @Test
    fun uniqueName_isReusableAfterCompletionAndKeepsOldTerminalState() = runTest {
        val s = store()
        val old = Uuid.random()
        val replacement = Uuid.random()
        val output = WorkData("done" to true)
        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, old, emptySet())
        assertTrue(s.transitionToRunning(old, periodic = false))
        assertTrue(s.finishWork(old, succeeded = true, outputData = output))
        val finished = s.getById(old)

        val result = s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, replacement, emptySet())

        assertTrue(result.enqueued)
        assertEquals(emptySet(), result.replacedIds)
        assertEquals(finished, s.getById(old))
        assertEquals(setOf(old, replacement), s.observeByUniqueName("backup").first().map { it.id }.toSet())
    }

    @Test
    fun uniqueReplace_replayOrConflictingIdCannotCancelCurrentWork() = runTest {
        val s = store()
        val current = Uuid.random()
        val replaced = Uuid.random()
        val other = Uuid.random()
        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, replaced, emptySet())
        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, current, setOf("existing"))
        s.enqueueUniqueOneTime("other", ExistingWorkPolicy.KEEP, other, emptySet())
        val before = s.snapshot()

        val replay = s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, current, setOf("ignored"))
        assertFalse(replay.enqueued)
        assertEquals(current, replay.id)
        assertFailsWith<WorkEnqueueException> {
            s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, replaced, emptySet())
        }
        assertFailsWith<WorkEnqueueException> {
            s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, other, emptySet())
        }
        assertEquals(before, s.snapshot())
    }

    @Test
    fun uniqueOneTime_rejectsActivePeriodicNameEvenBetweenCycles() = runTest {
        val s = store()
        val periodic = Uuid.random()
        val oneTime = Uuid.random()
        s.initWork(periodic, setOf("nightly"), UniqueWork("backup", WorkKind.PERIODIC))
        assertTrue(s.transitionToRunning(periodic, periodic = true))
        assertTrue(s.finishWork(periodic, succeeded = true, outputData = WorkData.EMPTY))
        val betweenCycles = s.getById(periodic)
        val livePeriodic = setOf(periodic)

        assertFailsWith<WorkEnqueueException> {
            s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, oneTime, emptySet(), livePeriodic)
        }
        assertEquals(betweenCycles, s.getById(periodic))
        assertNull(s.getById(oneTime))
        assertTrue(s.cancelWork(periodic, periodic = true))
        assertTrue(s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, oneTime, emptySet()).enqueued)
    }

    @Test
    fun uniqueAndTagFlowsKeepRestoredAndNamedWorkSeparate() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val legacy = Uuid.random()
        val unique = Uuid.random()
        persistence.save(WorkInfo(legacy, WorkInfo.State.ENQUEUED, tags = setOf("backup")))
        val s = IosWorkStateStore(persistence)
        s.restoreFromPersistence()
        assertTrue(s.observeByUniqueName("backup").first().isEmpty())
        assertEquals(listOf(legacy), s.observeByTag("backup").first().map { it.id })

        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.REPLACE, unique, setOf("manual"))

        assertEquals(WorkInfo.State.ENQUEUED, s.getById(legacy)?.state)
        assertEquals(listOf(legacy), s.observeByTag("backup").first().map { it.id })
        assertEquals(listOf(unique), s.observeByUniqueName("backup").first().map { it.id })
        assertEquals(listOf(unique), s.observeByTag("manual").first().map { it.id })
    }

    @Test
    fun unsupportedUniqueAppendPolicies_leaveStateUnchanged() = runTest {
        val s = store()
        val current = Uuid.random()
        s.enqueueUniqueOneTime("backup", ExistingWorkPolicy.KEEP, current, emptySet())
        val before = s.snapshot()
        for (policy in listOf(ExistingWorkPolicy.APPEND, ExistingWorkPolicy.APPEND_OR_REPLACE)) {
            assertFailsWith<WorkEnqueueException> {
                s.enqueueUniqueOneTime("backup", policy, Uuid.random(), emptySet())
            }
        }
        assertEquals(before, s.snapshot())
    }

    @Test
    fun persistence_serializesInFlightWritesAndReadsCurrentStateAfterWaiting() = runTest {
        val first = Uuid.random()
        val second = Uuid.random()
        val firstSaveEntered = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val saves = mutableListOf<WorkInfo>()
        val deletions = mutableListOf<Uuid>()
        val persistence = object : IosWorkPersistence {
            private var records = emptyMap<Uuid, WorkInfo>()

            override suspend fun save(info: WorkInfo) {
                val previous = records
                saves += info
                if (info.id == first && firstSaveEntered.complete(Unit)) {
                    releaseFirstSave.await()
                }
                records = previous + (info.id to info)
            }

            override suspend fun loadAll(): List<WorkInfo> = records.values.toList()

            override suspend fun delete(id: Uuid) {
                deletions += id
                records = records - id
            }
        }
        val s = IosWorkStateStore(persistence, backgroundScope)
        s.initWork(first, emptySet())
        testScheduler.runCurrent()
        assertTrue(firstSaveEntered.isCompleted)

        assertTrue(s.transitionToRunning(first, periodic = false))
        s.updateProgress(first, WorkProgress(20))
        assertTrue(s.cancelWork(first))
        s.initWork(second, setOf("independent"))
        assertTrue(s.transitionToRunning(second, periodic = false))
        assertTrue(s.incrementAttempt(second))
        s.updateProgress(second, WorkProgress(60))
        testScheduler.runCurrent()

        assertEquals(listOf(first), saves.map { it.id })
        assertTrue(deletions.isEmpty())
        assertEquals(WorkInfo.State.CANCELLED, s.getById(first)?.state)

        releaseFirstSave.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf(WorkInfo.State.ENQUEUED), saves.filter { it.id == first }.map { it.state })
        assertEquals(setOf(first), deletions.toSet())
        val latestSecond = assertNotNull(s.getById(second))
        assertEquals(WorkProgress(60), latestSecond.progress)
        assertEquals(1, latestSecond.runAttemptCount)
        assertEquals(setOf(latestSecond), saves.filter { it.id == second }.toSet())
        assertEquals(listOf(latestSecond), persistence.loadAll())
    }
}
