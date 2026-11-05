@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalForegroundApi::class)

package io.github.mobilebytelabs.worker.android

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ForegroundInfo
import io.github.mobilebytelabs.worker.ForegroundWorker
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import androidx.work.ForegroundInfo as AndroidForegroundInfo
import androidx.work.OutOfQuotaPolicy as AndroidOutOfQuotaPolicy

/** 官方内存数据库、TestDriver 和 WorkerWrapper 的真实启动链；不直接调用 provider 或 doWork。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30])
class AndroidExpeditedWorkTest {
    @Test
    fun expedited_waitsForNativeForegroundCompletionAndSharesDelegateAndContext() = nativeTest {
        OutOfQuotaPolicy.entries.forEach { policy ->
            withFixture(policy) { fixture ->
                fixture.enqueueAndStart()
                fixture.awaitUpdater()
                assertEquals(listOf("provider", "updater"), fixture.events.toList())
                assertFalse(fixture.businessEntered.isCompleted)
                assertFalse(fixture.updater.future.isDone)
                fixture.assertUnregistered()

                fixture.updater.complete()
                fixture.businessEntered.await()
                assertEquals(listOf("provider", "updater", "complete", "business"), fixture.events.toList())
                fixture.assertIdentity(providerExpected = true)
                assertSame(fixture.nativeWorker, fixture.registeredWorker())
                fixture.businessRelease.complete(Unit)

                fixture.awaitState(WorkInfo.State.SUCCEEDED)
                fixture.businessJobEnded.await()
                assertFalse(fixture.updater.future.isCancelled)
                fixture.assertUnregistered()
            }
        }
    }

    @Test
    fun providerFailure_reachesNativeFailureWithoutUpdaterOrBusiness() = nativeTest {
        val failure = IllegalStateException("initial notification unavailable")
        withFixture(providerFailure = failure) { fixture ->
            fixture.enqueueAndStart()
            assertOriginalFailure(failure, fixture.errors.receive())
            fixture.awaitState(WorkInfo.State.FAILED)
            assertEquals(listOf("provider"), fixture.events.toList())
            assertFalse(fixture.updater.entered.isCompleted)
            assertFalse(fixture.businessEntered.isCompleted)
            assertEquals(1, fixture.contexts.size)
            fixture.assertUnregistered()
        }
    }

    @Test
    fun updaterFailure_reachesNativeFailureWithoutBusiness() = nativeTest {
        val failure = IllegalArgumentException("foreground update rejected")
        withFixture { fixture ->
            fixture.enqueueAndStart()
            fixture.awaitUpdater()
            fixture.updater.fail(failure)
            assertOriginalFailure(failure, fixture.errors.receive())
            fixture.awaitState(WorkInfo.State.FAILED)
            assertEquals(listOf("provider", "updater"), fixture.events.toList())
            assertFalse(fixture.businessEntered.isCompleted)
            fixture.assertUnregistered()
        }
    }

    @Test
    fun updaterCancellation_followsNativeSelfCancellationFailureWithoutBusiness() = nativeTest {
        withFixture { fixture ->
            fixture.enqueueAndStart()
            fixture.awaitUpdater()
            assertTrue(fixture.updater.future.cancel(false))
            fixture.awaitState(WorkInfo.State.FAILED)
            assertTrue(fixture.updater.future.isCancelled)
            assertFalse(fixture.businessEntered.isCompleted)
            fixture.assertUnregistered()
        }
    }

    @Test
    fun nativeCancellationWhileWaiting_cancelsUpdaterAndNeverRegistersBusiness() = nativeTest {
        withFixture { fixture ->
            fixture.enqueueAndStart()
            fixture.awaitUpdater()
            fixture.manager.cancelWorkById(fixture.request.id)
            fixture.updater.cancelled.await()
            fixture.awaitState(WorkInfo.State.CANCELLED)
            assertTrue(fixture.updater.future.isCancelled)
            assertEquals(false, fixture.updater.mayInterrupt)
            assertFalse(fixture.businessEntered.isCompleted)
            assertEquals(listOf("provider", "updater"), fixture.events.toList())
            fixture.assertUnregistered()
        }
    }

    @Test
    fun nativeCancellationDuringBusiness_propagatesAndFinallyReleasesBridge() = nativeTest {
        withFixture { fixture ->
            fixture.enqueueAndStart()
            fixture.awaitUpdater()
            fixture.updater.complete()
            fixture.businessEntered.await()
            assertSame(fixture.nativeWorker, fixture.registeredWorker())

            fixture.manager.cancelWorkById(fixture.request.id)
            assertIs<CancellationException>(fixture.businessCancellation.await())
            fixture.businessJobEnded.await()
            fixture.awaitState(WorkInfo.State.CANCELLED)
            assertTrue(fixture.nativeWorker.isStopped)
            assertFalse(fixture.updater.future.isCancelled)
            assertFalse(fixture.businessRelease.isCompleted)
            fixture.assertUnregistered()
        }
    }

    @Test
    fun businessFailure_reachesNativeFailureAndFinallyReleasesBridge() = nativeTest {
        val failure = IllegalStateException("business failed")
        withFixture(businessFailure = failure) { fixture ->
            fixture.enqueueAndStart()
            fixture.awaitUpdater()
            fixture.updater.complete()
            assertOriginalFailure(failure, fixture.errors.receive())
            fixture.businessJobEnded.await()
            fixture.awaitState(WorkInfo.State.FAILED)
            fixture.assertIdentity(providerExpected = true)
            fixture.assertUnregistered()
        }
    }

    @Test
    @Config(sdk = [31])
    fun api31_preservesBothNativePoliciesAndExecutesOrdinaryWorkerWithoutProvider() = nativeTest {
        OutOfQuotaPolicy.entries.forEach { policy ->
            withFixture(policy, foreground = false) { fixture ->
                fixture.enqueueAndStart()
                fixture.businessEntered.await()
                assertFalse(fixture.updater.entered.isCompleted)
                assertEquals(listOf("business"), fixture.events.toList())
                fixture.assertIdentity(providerExpected = false)
                fixture.businessRelease.complete(Unit)
                fixture.awaitState(WorkInfo.State.SUCCEEDED)
                fixture.businessJobEnded.await()
                fixture.assertUnregistered()
            }
        }
    }

    @Test
    fun ordinaryNonExpeditedWork_doesNotRequireForegroundProvider() = nativeTest {
        withFixture(policy = null, foreground = false) { fixture ->
            fixture.enqueueAndStart()
            fixture.businessEntered.await()
            assertFalse(fixture.updater.entered.isCompleted)
            assertEquals(listOf("business"), fixture.events.toList())
            fixture.businessRelease.complete(Unit)
            fixture.awaitState(WorkInfo.State.SUCCEEDED)
            fixture.businessJobEnded.await()
            fixture.assertUnregistered()
        }
    }

    private fun nativeTest(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun withFixture(
        policy: OutOfQuotaPolicy? = OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
        foreground: Boolean = true,
        providerFailure: Throwable? = null,
        businessFailure: Throwable? = null,
        block: suspend (NativeFixture) -> Unit,
    ) {
        val fixture = NativeFixture(policy, foreground, providerFailure, businessFailure)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        // 协程栈恢复只允许同类型副本且原异常为自身或直接 cause。
        assertTrue(actual === expected || actual.cause === expected)
    }

    private class NativeFixture(
        private val policy: OutOfQuotaPolicy?,
        foreground: Boolean,
        providerFailure: Throwable?,
        private val businessFailure: Throwable?,
    ) {
        private val context: Context = ApplicationProvider.getApplicationContext()
        private val previousFactory = KmpWorkerFactory.instance
        private val created = CompletableDeferred<Unit>()
        private val updaterField = WorkerParameters::class.java.getDeclaredField("mForegroundUpdater")
            .apply { isAccessible = true }
        val events = CopyOnWriteArrayList<String>()
        val contexts = CopyOnWriteArrayList<WorkerContext>()
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val businessEntered = CompletableDeferred<Unit>()
        val businessRelease = CompletableDeferred<Unit>()
        val businessCancellation = CompletableDeferred<CancellationException>()
        val businessJobEnded = CompletableDeferred<Unit>()
        private var providerContext: WorkerContext? = null
        private var businessContext: WorkerContext? = null
        private lateinit var delegate: CoroutineWorker
        lateinit var nativeWorker: KmpAndroidWorker
        lateinit var updater: GatedUpdater
        private var nativeFactoryCalls = 0
        private val native: WorkManager
        val manager: AndroidWorkManager
        private val driver: TestDriver
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("native-expedited-test")
            .setInputData(workDataOf("business" to "payload"))
            .setConstraints(Constraints { setRequiredNetworkType(NetworkType.CONNECTED) })
            .apply { policy?.let { setExpedited(it) } }
            .build()

        init {
            val factory = KmpWorkerFactory(object : KmpAndroidWorkerFactory {
                override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                    assertEquals("native-expedited-test", workerClass)
                    contexts += context
                    val worker = if (foreground) {
                        object : ForegroundWorker(context) {
                            override suspend fun getForegroundInfo(): ForegroundInfo {
                                assertSame(delegate, this)
                                providerContext = this.context
                                events += "provider"
                                providerFailure?.let { throw it }
                                return ForegroundInfo(81, "加急任务", "准备执行")
                            }

                            override suspend fun doWork(): WorkResult = runBusiness(this, this.context)
                        }
                    } else {
                        object : CoroutineWorker(context) {
                            override suspend fun doWork(): WorkResult = runBusiness(this, this.context)
                        }
                    }
                    delegate = worker
                    return worker
                }
            })
            val nativeFactory = object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? {
                    nativeFactoryCalls++
                    // 固定 2.11.2：替换 WorkerWrapper 创建的原参数字段，不能复制参数或使用 TestBuilder。
                    val original = workerParameters.foregroundUpdater
                    assertEquals("androidx.work.impl.utils.WorkForegroundUpdater", original.javaClass.name)
                    updater = GatedUpdater(original, events)
                    updaterField.set(workerParameters, updater)
                    assertSame(updater, workerParameters.foregroundUpdater)
                    return factory.createWorker(appContext, workerClassName, workerParameters).also {
                        nativeWorker = assertIs<KmpAndroidWorker>(it)
                        created.complete(Unit)
                    }
                }
            }
            val config = Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(nativeFactory)
                .setWorkerInitializationExceptionHandler { created.completeExceptionally(it.throwable) }
                .setWorkerExecutionExceptionHandler { errors.trySend(it.throwable) }
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
            native = WorkManager.getInstance(context)
            manager = AndroidWorkManager(context)
            driver = assertNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        }

        suspend fun enqueueAndStart() {
            assertEquals(request.id, manager.enqueue(request))
            assertEquals(WorkInfo.State.ENQUEUED, native.getWorkInfoById(request.id.nativeId()).await()?.state)
            assertNativeFlags()
            driver.setAllConstraintsMet(request.id.nativeId())
            created.await()
        }

        suspend fun awaitUpdater() {
            updater.entered.await()
            updater.nativeCompleted.await()
            assertEquals(request.id.nativeId(), updater.id)
            assertEquals(81, assertNotNull(updater.info).notificationId)
        }

        private suspend fun runBusiness(worker: CoroutineWorker, workerContext: WorkerContext): WorkResult {
            assertSame(delegate, worker)
            businessContext = workerContext
            currentCoroutineContext()[Job]!!.invokeOnCompletion { businessJobEnded.complete(Unit) }
            assertSame(nativeWorker, registeredWorker())
            events += "business"
            businessEntered.complete(Unit)
            try {
                businessFailure?.let { throw it }
                businessRelease.await()
                return WorkResult.success()
            } catch (cancelled: CancellationException) {
                businessCancellation.complete(cancelled)
                throw cancelled
            }
        }

        fun assertIdentity(providerExpected: Boolean) {
            assertEquals(1, nativeFactoryCalls)
            val actual = contexts.single()
            assertSame(actual, businessContext)
            if (providerExpected) assertSame(actual, providerContext) else assertEquals(null, providerContext)
            assertEquals(request.id, actual.id)
            assertEquals(request.id.nativeId(), nativeWorker.id)
            assertEquals("payload", actual.inputData.getString("business"))
        }

        suspend fun awaitState(expected: WorkInfo.State) {
            val info = native.getWorkInfoByIdFlow(request.id.nativeId()).filterNotNull().first { it.state == expected }
            assertEquals(expected, info.state)
        }

        private fun assertNativeFlags() {
            // 只在测试反射读取真实 WorkSpec，避免为了检查标志引入 Room 编译依赖。
            val database = native.javaClass.getMethod("getWorkDatabase").invoke(native)
            val dao = database.javaClass.getMethod("workSpecDao").invoke(database)
            val spec =
                assertNotNull(
                    dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, request.id.toString()),
                )
            assertEquals(policy != null, spec.javaClass.getField("expedited").getBoolean(spec))
            val expected = when (policy) {
                OutOfQuotaPolicy.DROP_WORK_REQUEST -> AndroidOutOfQuotaPolicy.DROP_WORK_REQUEST
                else -> AndroidOutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
            }
            assertEquals(expected, spec.javaClass.getField("outOfQuotaPolicy").get(spec))
        }

        fun registeredWorker(): KmpAndroidWorker? {
            val field = AndroidForegroundBridge::class.java.getDeclaredField("activeWorkers").apply {
                isAccessible =
                    true
            }
            return (field.get(AndroidForegroundBridge) as Map<*, *>)[request.id] as KmpAndroidWorker?
        }

        fun assertUnregistered() = assertEquals(null, registeredWorker())

        fun close() {
            try {
                native.cancelAllWork().result.get(5, TimeUnit.SECONDS)
                WorkManagerTestInitHelper.closeWorkDatabase()
            } finally {
                KmpWorkerFactory.instance = previousFactory
            }
        }
    }

    private class GatedUpdater(private val original: ForegroundUpdater, private val events: MutableList<String>) :
        ForegroundUpdater {
        val entered = CompletableDeferred<Unit>()
        val nativeCompleted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var id: UUID? = null
        var info: AndroidForegroundInfo? = null
        var mayInterrupt: Boolean? = null
        private val direct = Executor { it.run() }
        private lateinit var completer: CallbackToFutureAdapter.Completer<Void>
        private val source = CallbackToFutureAdapter.getFuture<Void> {
            completer = it
            "native prestart gate"
        }
        val future = object : ListenableFuture<Void> by source {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                mayInterrupt = mayInterruptIfRunning
                return source.cancel(mayInterruptIfRunning)
            }
        }

        init {
            source.addListener({ if (source.isCancelled) cancelled.complete(Unit) }, direct)
        }

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: AndroidForegroundInfo,
        ): ListenableFuture<Void> {
            this.id = id
            info = foregroundInfo
            events += "updater"
            entered.complete(Unit)
            val nativeFuture = original.setForegroundAsync(context, id, foregroundInfo)
            nativeFuture.addListener({
                try {
                    nativeFuture.get()
                    nativeCompleted.complete(Unit)
                } catch (failure: ExecutionException) {
                    val cause = assertNotNull(failure.cause)
                    nativeCompleted.completeExceptionally(cause)
                    completer.setException(cause)
                } catch (failure: CancellationException) {
                    nativeCompleted.cancel(failure)
                    completer.setCancelled()
                }
            }, direct)
            return future
        }

        fun complete() {
            assertTrue(nativeCompleted.isCompleted)
            events += "complete"
            assertTrue(completer.set(null))
        }

        fun fail(failure: Throwable) {
            assertTrue(completer.setException(failure))
        }
    }
}
