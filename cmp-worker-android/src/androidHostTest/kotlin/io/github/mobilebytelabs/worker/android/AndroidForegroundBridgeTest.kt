@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalForegroundApi::class)

package io.github.mobilebytelabs.worker.android

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExperimentalForegroundApi
import io.github.mobilebytelabs.worker.ForegroundInfo
import io.github.mobilebytelabs.worker.ForegroundServiceType
import io.github.mobilebytelabs.worker.ForegroundWorker
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import androidx.work.ForegroundInfo as AndroidForegroundInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 31])
class AndroidForegroundBridgeTest {
    private lateinit var context: Context
    private var previousFactory: KmpAndroidWorkerFactory? = null
    private val info = ForegroundInfo(41, "前台任务", "处理中", serviceType = ForegroundServiceType.DATA_SYNC)

    @Before
    fun setUp() {
        previousFactory = KmpWorkerFactory.instance
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        KmpWorkerFactory.instance = previousFactory
        AndroidForegroundBridge.notificationCustomizer = null
    }

    @Test
    fun bridge_isAccessible() {
        // AndroidForegroundBridge is an object — verify it can be referenced
        assertNotNull(AndroidForegroundBridge, "AndroidForegroundBridge object must be accessible")
    }

    @Test
    @Config(sdk = [33])
    fun notificationCustomizer_appliesIconChannelAndActions_withTheWorkerId() {
        val workerId = kotlin.uuid.Uuid.random()
        var seenId: kotlin.uuid.Uuid? = null
        AndroidForegroundBridge.notificationCustomizer = AndroidForegroundBridge.NotificationCustomizer { _, id, _, b ->
            seenId = id
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setChannelId("app.downloads")
                .addAction(0, "Pause", null)
        }
        try {
            val n = AndroidForegroundBridge.buildNotification(
                context,
                workerId,
                io.github.mobilebytelabs.worker.ForegroundInfo(notificationId = 1, title = "t", message = "m"),
            )
            kotlin.test.assertEquals(workerId, seenId)
            kotlin.test.assertEquals(android.R.drawable.stat_sys_download, n.smallIcon.resId)
            kotlin.test.assertEquals("app.downloads", n.channelId)
            kotlin.test.assertEquals("Pause", n.actions.single().title)
        } finally {
            AndroidForegroundBridge.notificationCustomizer = null
        }
    }

    @Test
    @Config(sdk = [33])
    fun noCustomizer_keepsTheDefaults() {
        val n = AndroidForegroundBridge.buildNotification(
            context,
            kotlin.uuid.Uuid.random(),
            io.github.mobilebytelabs.worker.ForegroundInfo(notificationId = 1, title = "t", message = "m"),
        )
        kotlin.test.assertEquals(AndroidForegroundBridge.DEFAULT_CHANNEL_ID, n.channelId)
        kotlin.test.assertEquals(android.R.drawable.stat_notify_sync, n.smallIcon.resId)
    }

    @Test
    fun defaultChannelId_isNonEmpty() {
        val channelId = AndroidForegroundBridge.DEFAULT_CHANNEL_ID
        assertNotNull(channelId)
        assert(channelId.isNotEmpty()) { "DEFAULT_CHANNEL_ID must be non-empty" }
    }

    @Test
    fun promote_waitsForNativeCompletionThenWorkerFinallyUnregisters() = runTest {
        val fixture = ForegroundFixture()
        val execution = async(start = CoroutineStart.UNDISPATCHED) { fixture.nativeWorker.doWork() }
        assertFalse(execution.isCompleted)
        assertFalse(fixture.promotion.isDone)
        assertFalse(fixture.continued)
        assertEquals(fixture.nativeWorker.id, fixture.updater.workerId)
        val nativeInfo = assertNotNull(fixture.updater.info)
        assertEquals(info.notificationId, nativeInfo.notificationId)
        assertEquals(
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            nativeInfo.foregroundServiceType,
        )

        fixture.updater.completer.set(null)

        assertIs<ListenableWorker.Result.Success>(execution.await())
        assertTrue(fixture.promotion.isDone)
        assertFalse(fixture.promotion.isCancelled)
        assertTrue(fixture.continued)
        fixture.assertUnregistered()
    }

    @Test
    fun promote_nativeFailurePropagatesOriginalCauseAndWorkerFinallyUnregisters() = runTest {
        val fixture = ForegroundFixture()
        val execution = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.nativeWorker.doWork() } }
        val failure = IllegalStateException("foreground restricted")

        fixture.updater.completer.setException(failure)

        val observed = assertIs<IllegalStateException>(execution.await().exceptionOrNull())
        assertEquals(failure.message, observed.message)
        // 协程恢复堆栈时允许同类型副本，但原异常必须是自身或直接 cause。
        assertTrue(observed === failure || observed.cause === failure)
        assertFalse(fixture.continued)
        fixture.assertUnregistered()
    }

    @Test
    fun promote_nativeCancellationCancelsCompletionAndWorkerFinallyUnregisters() = runTest {
        val fixture = ForegroundFixture()
        val execution = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.nativeWorker.doWork() } }

        fixture.updater.future.cancel(false)

        assertIs<CancellationException>(execution.await().exceptionOrNull())
        assertTrue(fixture.promotion.isCancelled)
        assertFalse(fixture.continued)
        fixture.assertUnregistered()
    }

    @Test
    fun promote_callerCancellationCancelsNativeWithoutInterruptingAndWorkerFinallyUnregisters() = runTest {
        val fixture = ForegroundFixture()
        val execution = async(start = CoroutineStart.UNDISPATCHED) { fixture.nativeWorker.doWork() }

        execution.cancel()
        execution.join()

        assertTrue(fixture.promotion.isCancelled)
        assertTrue(fixture.updater.future.isCancelled)
        assertEquals(false, fixture.updater.mayInterrupt)
        assertFalse(fixture.continued)
        fixture.assertUnregistered()
    }

    @Test
    fun promote_customizerFailureIsNotSwallowedAndWorkerFinallyUnregisters() = runTest {
        val fixture = ForegroundFixture()
        val failure = IllegalArgumentException("invalid notification")
        AndroidForegroundBridge.notificationCustomizer = AndroidForegroundBridge.NotificationCustomizer { _, _, _, _ ->
            throw failure
        }

        assertSame(failure, assertFailsWith<IllegalArgumentException> { fixture.nativeWorker.doWork() })

        assertEquals(null, fixture.updater.workerId)
        assertFalse(fixture.continued)
        fixture.assertUnregistered()
    }

    @Test
    fun promote_withoutActiveWorkerFails() {
        val worker = object : ForegroundWorker(object : WorkerContext {
            override val id = Uuid.random()
            override val inputData = WorkData.EMPTY
            override val tags = emptySet<String>()
            override suspend fun setProgress(progress: WorkProgress) = Unit
        }) {
            override suspend fun doWork(): WorkResult = WorkResult.success()
        }

        assertFailsWith<IllegalStateException> { AndroidForegroundBridge.promote(worker, info) }
    }

    private inner class ForegroundFixture {
        val updater = ControlledForegroundUpdater()
        var continued = false
        lateinit var foregroundWorker: ForegroundWorker
        lateinit var promotion: CompletableFuture<Void>
        val nativeWorker = TestListenableWorkerBuilder<KmpAndroidWorker>(
            context,
            inputData = workDataOf(KEY_KMP_CLASS to "foreground-test-worker"),
        ).setForegroundUpdater(updater).setWorkerFactory(
            KmpWorkerFactory(object : KmpAndroidWorkerFactory {
                override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                    foregroundWorker = object : ForegroundWorker(context) {
                        override suspend fun doWork(): WorkResult {
                            // Android 完成链与 JVM 反射分别验证，不修改运行时系统属性。
                            promotion = AndroidForegroundBridge.promote(this, info)
                            promotion.await()
                            continued = true
                            return WorkResult.success()
                        }
                    }
                    return foregroundWorker
                }
            }),
        ).build()

        fun assertUnregistered() {
            assertFailsWith<IllegalStateException> { AndroidForegroundBridge.promote(foregroundWorker, info) }
        }
    }

    private class ControlledForegroundUpdater : ForegroundUpdater {
        lateinit var completer: CallbackToFutureAdapter.Completer<Void>
        var workerId: UUID? = null
        var info: AndroidForegroundInfo? = null
        var mayInterrupt: Boolean? = null
        private val source = CallbackToFutureAdapter.getFuture<Void> {
            completer = it
            "controlled foreground update"
        }
        val future = object : ListenableFuture<Void> by source {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                mayInterrupt = mayInterruptIfRunning
                return source.cancel(mayInterruptIfRunning)
            }
        }

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: AndroidForegroundInfo,
        ): ListenableFuture<Void> {
            workerId = id
            info = foregroundInfo
            return future
        }
    }
}
