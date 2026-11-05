@file:OptIn(ExperimentalForegroundApi::class)

package io.github.mobilebytelabs.worker

import io.github.mobilebytelabs.worker.android.AndroidForegroundBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForegroundBridgeDispatchTest {
    private val worker = object : ForegroundWorker(TestWorkerContext()) {
        override suspend fun doWork(): WorkResult = WorkResult.success()
    }
    private val info = ForegroundInfo(42, "前台任务", "处理中")

    @BeforeTest
    fun setUp() {
        AndroidForegroundBridge.reset()
    }

    @AfterTest
    fun tearDown() {
        AndroidForegroundBridge.reset()
    }

    @Test
    fun dispatch_waitsForBridgeCompletionAndPreservesArguments() = runTest {
        val completion = CompletableFuture<Void>()
        var calls = 0
        AndroidForegroundBridge.action = { actualWorker, actualInfo ->
            assertSame(worker, actualWorker)
            assertSame(info, actualInfo)
            calls++
            completion
        }
        val execution = async(start = CoroutineStart.UNDISPATCHED) { dispatchToAndroidBridge(worker, info) }

        assertEquals(1, calls)
        assertFalse(execution.isCompleted)
        completion.complete(null)

        assertTrue(execution.await())
    }

    @Test
    fun dispatch_asyncFailurePropagatesOriginalCause() = runTest {
        val completion = CompletableFuture<Void>()
        AndroidForegroundBridge.action = { _, _ -> completion }
        val execution =
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatchToAndroidBridge(worker, info) } }
        val failure = IllegalStateException("native foreground rejected")

        completion.completeExceptionally(failure)

        val observed = assertIs<IllegalStateException>(execution.await().exceptionOrNull())
        assertEquals(failure.message, observed.message)
        // 协程恢复堆栈时允许同类型副本，但原异常必须是自身或直接 cause。
        assertTrue(observed === failure || observed.cause === failure)
    }

    @Test
    fun dispatch_nativeCancellationPropagatesCancellation() = runTest {
        val completion = CompletableFuture<Void>()
        AndroidForegroundBridge.action = { _, _ -> completion }
        val execution =
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatchToAndroidBridge(worker, info) } }

        completion.cancel(false)

        assertIs<CancellationException>(execution.await().exceptionOrNull())
    }

    @Test
    fun dispatch_callerCancellationCancelsBridgeFutureWithoutInterrupting() = runTest {
        var mayInterrupt: Boolean? = null
        val completion = object : CompletableFuture<Void>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                mayInterrupt = mayInterruptIfRunning
                return super.cancel(mayInterruptIfRunning)
            }
        }
        AndroidForegroundBridge.action = { _, _ -> completion }
        val execution = async(start = CoroutineStart.UNDISPATCHED) { dispatchToAndroidBridge(worker, info) }

        execution.cancel()
        execution.join()

        assertTrue(completion.isCancelled)
        assertEquals(false, mayInterrupt)
    }

    @Test
    fun dispatch_unwrapsSynchronousInvocationFailureIncludingClassNotFound() = runTest {
        // 已找到 bridge 后抛出的 CNFE 不能误判为缺少 bridge 并降级至桌面。
        listOf(IllegalArgumentException("invalid notification"), ClassNotFoundException("customizer dependency"))
            .forEach { failure ->
                AndroidForegroundBridge.action = { _, _ -> throw failure }
                assertSame(failure, assertFailsWith<Exception> { dispatchToAndroidBridge(worker, info) })
            }
    }

    @Test
    fun dispatch_linkageFailurePropagates() = runTest {
        val failure = NoClassDefFoundError("missing bridge dependency")
        AndroidForegroundBridge.action = { _, _ -> throw failure }

        assertSame(failure, assertFailsWith<NoClassDefFoundError> { dispatchToAndroidBridge(worker, info) })
    }

    @Test
    fun dispatch_rejectsMissingOrInvalidCompletionStage() = runTest {
        listOf(null, Unit).forEach { result ->
            AndroidForegroundBridge.action = { _, _ -> result }
            assertFailsWith<IllegalStateException> { dispatchToAndroidBridge(worker, info) }
        }
    }
}
