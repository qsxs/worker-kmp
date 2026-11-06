package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        val info = s.snapshot().getValue(id)
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
        val cancelled = s.snapshot().getValue(id)
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
        val queued = s.snapshot().getValue(id)
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
            val cancelled = s.snapshot().getValue(id)
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
}
