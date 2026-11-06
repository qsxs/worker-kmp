package io.github.mobilebytelabs.worker.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BgTaskExecutionTest {
    @Test
    fun normalCompletionReportsTheWorkResultOnce() = runTest {
        for (result in listOf(true, false)) {
            val reports = mutableListOf<Boolean>()
            lateinit var expire: () -> Unit
            var handlerInstalled = false
            launchBgTask(
                this,
                installExpirationHandler = {
                    expire = it
                    handlerInstalled = true
                },
                complete = { reports += it },
            ) {
                assertTrue(handlerInstalled)
                result
            }
            testScheduler.runCurrent()
            assertEquals(listOf(result), reports)
            expire()
            expire()
            testScheduler.runCurrent()
            assertEquals(listOf(result), reports)
        }
    }

    @Test
    fun expiryDuringHandlerInstallationSkipsTheBodyAndReportsFailureOnce() = runTest {
        val reports = mutableListOf<Boolean>()
        var ran = false
        launchBgTask(
            this,
            installExpirationHandler = { expire ->
                expire()
                expire()
            },
            complete = { reports += it },
        ) {
            ran = true
            true
        }
        testScheduler.runCurrent()

        assertFalse(ran)
        assertEquals(listOf(false), reports)
    }

    @Test
    fun successWaitsForTheOwnedChild() = runTest {
        val releaseChild = CompletableDeferred<Unit>()
        val reports = mutableListOf<Boolean>()
        launchBgTask(this, installExpirationHandler = {}, complete = { reports += it }) {
            launch { releaseChild.await() }
            true
        }
        testScheduler.runCurrent()
        assertTrue(reports.isEmpty())

        releaseChild.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(true), reports)
    }

    @Test
    fun repeatedExpiryWaitsForNonCancellableChildCleanup() = runTest {
        val reports = mutableListOf<Boolean>()
        val childStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        lateinit var expire: () -> Unit
        launchBgTask(this, installExpirationHandler = { expire = it }, complete = { reports += it }) {
            launch {
                try {
                    childStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            true
        }
        try {
            testScheduler.runCurrent()
            assertTrue(childStarted.isCompleted)
            expire()
            expire()
            testScheduler.runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertTrue(reports.isEmpty())
        } finally {
            releaseCleanup.complete(Unit)
        }
        testScheduler.runCurrent()
        expire()
        assertEquals(listOf(false), reports)
    }

    @Test
    fun failuresAndSelfCancellationReportFailureWithoutCancellingTheSupervisor() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val bodyFailure = mutableListOf<Boolean>()
        val childFailure = mutableListOf<Boolean>()
        val selfCancellation = mutableListOf<Boolean>()
        try {
            launchBgTask(scope, installExpirationHandler = {}, complete = { bodyFailure += it }) {
                error("Background task failed")
            }
            launchBgTask(scope, installExpirationHandler = {}, complete = { childFailure += it }) {
                launch { error("Background child failed") }
                true
            }
            launchBgTask(scope, installExpirationHandler = {}, complete = { selfCancellation += it }) {
                cancel()
                true
            }
            testScheduler.runCurrent()
            assertEquals(listOf(false), bodyFailure)
            assertEquals(listOf(false), childFailure)
            assertEquals(listOf(false), selfCancellation)
            assertTrue(scope.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scopeCancellationReportsFailureForActiveAndLaterTasks() = runTest {
        val owner = Job(coroutineContext.job)
        val scope = CoroutineScope(coroutineContext + owner)
        val started = CompletableDeferred<Unit>()
        val reports = mutableListOf<Boolean>()
        launchBgTask(scope, installExpirationHandler = {}, complete = { reports += it }) {
            started.complete(Unit)
            awaitCancellation()
        }
        try {
            testScheduler.runCurrent()
            assertTrue(started.isCompleted)
            assertTrue(reports.isEmpty())
        } finally {
            owner.cancel()
        }
        testScheduler.runCurrent()
        assertEquals(listOf(false), reports)

        var ran = false
        launchBgTask(scope, installExpirationHandler = {}, complete = { reports += it }) {
            ran = true
            true
        }
        testScheduler.runCurrent()
        assertFalse(ran)
        assertEquals(listOf(false, false), reports)
    }
}
