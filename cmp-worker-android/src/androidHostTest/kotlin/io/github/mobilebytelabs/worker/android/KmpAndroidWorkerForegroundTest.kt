@file:OptIn(io.github.mobilebytelabs.worker.ExperimentalForegroundApi::class)

package io.github.mobilebytelabs.worker.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ForegroundInfo
import io.github.mobilebytelabs.worker.ForegroundNotSupportedException
import io.github.mobilebytelabs.worker.ForegroundServiceType
import io.github.mobilebytelabs.worker.ForegroundWorker
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 31])
class KmpAndroidWorkerForegroundTest {
    private lateinit var context: Context
    private var previousFactory: KmpAndroidWorkerFactory? = null
    private val info = ForegroundInfo(71, "初始通知", "等待执行", WorkProgress(37), ForegroundServiceType.DATA_SYNC)

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
    fun injectedFactory_isCapturedAndSharesOneDelegateAndContextAcrossProviderAndWork() = runTest {
        var factoryCalls = 0
        var providerCalls = 0
        var workCalls = 0
        lateinit var factoryContext: WorkerContext
        lateinit var delegate: ForegroundWorker
        lateinit var native: KmpAndroidWorker
        native = buildWorker(
            factory { workerContext ->
                factoryCalls++
                factoryContext = workerContext
                object : ForegroundWorker(workerContext) {
                    override suspend fun getForegroundInfo(): ForegroundInfo {
                        assertSame(delegate, this)
                        assertSame(factoryContext, this.context)
                        providerCalls++
                        return info
                    }

                    override suspend fun doWork(): WorkResult {
                        assertSame(delegate, this)
                        assertSame(factoryContext, this.context)
                        assertSame(native, nativeWorkerFor(id))
                        workCalls++
                        return WorkResult.success()
                    }
                }.also { delegate = it }
            },
        )
        assertEquals(0, factoryCalls)
        KmpWorkerFactory.instance = unexpectedFactory()

        assertEquals(info.notificationId, native.getForegroundInfo().notificationId)
        assertUnregistered(native)
        KmpWorkerFactory.instance = unexpectedFactory()
        assertEquals(info.notificationId, native.getForegroundInfo().notificationId)
        assertEquals(0, workCalls)
        assertEquals(native.id.toString(), factoryContext.id.toString())
        assertEquals("payload", factoryContext.inputData.getString("business"))
        assertEquals(3, factoryContext.runAttemptCount)

        assertIs<ListenableWorker.Result.Success>(native.doWork())
        assertEquals(1, factoryCalls)
        assertEquals(2, providerCalls)
        assertEquals(1, workCalls)
        assertUnregistered(native)
    }

    @Test
    fun publicTwoArgumentConstructor_capturesLegacyFactoryBeforeLazyInitialization() = runTest {
        var factoryCalls = 0
        var workCalls = 0
        KmpWorkerFactory.instance = factory { workerContext ->
            factoryCalls++
            object : ForegroundWorker(workerContext) {
                override suspend fun getForegroundInfo(): ForegroundInfo = info
                override suspend fun doWork(): WorkResult {
                    workCalls++
                    return WorkResult.success()
                }
            }
        }
        // 默认 TestBuilder 通过反射调用保留的 public (Context, WorkerParameters) 构造器。
        val native = buildWorker()
        KmpWorkerFactory.instance = unexpectedFactory()

        assertEquals(info.notificationId, native.getForegroundInfo().notificationId)
        KmpWorkerFactory.instance = unexpectedFactory()
        assertIs<ListenableWorker.Result.Success>(native.doWork())
        assertEquals(1, factoryCalls)
        assertEquals(1, workCalls)
        assertUnregistered(native)
    }

    @Test
    fun ordinaryWorker_executesWithoutForegroundProvider() = runTest {
        var workCalls = 0
        val native = buildWorker(
            factory { workerContext ->
                object : CoroutineWorker(workerContext) {
                    override suspend fun doWork(): WorkResult {
                        workCalls++
                        return WorkResult.success()
                    }
                }
            },
        )

        assertFailsWith<ForegroundNotSupportedException> { native.getForegroundInfo() }
        assertEquals(0, workCalls)
        assertUnregistered(native)
        assertIs<ListenableWorker.Result.Success>(native.doWork())
        assertEquals(1, workCalls)
        assertUnregistered(native)
    }

    @Test
    fun providerFailure_doesNotRunBusinessOrRegisterBridge() = runTest {
        val failure = IllegalStateException("initial notification unavailable")
        var workCalls = 0
        val native = buildWorker(
            factory { workerContext ->
                object : ForegroundWorker(workerContext) {
                    override suspend fun getForegroundInfo(): ForegroundInfo = throw failure
                    override suspend fun doWork(): WorkResult {
                        workCalls++
                        return WorkResult.success()
                    }
                }
            },
        )

        assertSame(failure, assertFailsWith<IllegalStateException> { native.getForegroundInfo() })
        assertEquals(0, workCalls)
        assertUnregistered(native)
    }

    @Test
    fun defaultForegroundProvider_failsWithoutRegisteringOrRunningWork() = runTest {
        var workCalls = 0
        val native = buildWorker(
            factory { workerContext ->
                object : ForegroundWorker(workerContext) {
                    override suspend fun doWork(): WorkResult {
                        workCalls++
                        return WorkResult.success()
                    }
                }
            },
        )

        assertFailsWith<ForegroundNotSupportedException> { native.getForegroundInfo() }
        assertEquals(0, workCalls)
        assertUnregistered(native)
    }

    @Test
    fun factoryInitializationFailure_canRetryWithTheCapturedFactory() = runTest {
        var factoryCalls = 0
        val native = buildWorker(
            factory { workerContext ->
                factoryCalls++
                check(factoryCalls > 1) { "first initialization failed" }
                foregroundWorker(workerContext)
            },
        )

        assertFailsWith<IllegalStateException> { native.getForegroundInfo() }
        assertUnregistered(native)
        KmpWorkerFactory.instance = unexpectedFactory()
        assertEquals(info.notificationId, native.getForegroundInfo().notificationId)
        assertIs<ListenableWorker.Result.Success>(native.doWork())
        assertEquals(2, factoryCalls)
        assertUnregistered(native)
    }

    @Test
    fun provider_preservesNotificationDefaultsChannelAndSupportedServiceTypes() = runTest {
        val types = listOf(
            ForegroundServiceType.DATA_SYNC to ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ForegroundServiceType.CAMERA to ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ForegroundServiceType.HEALTH to 0,
        )
        types.forEach { (type, constant) ->
            val native = buildWorker(factory { foregroundWorker(it, info.copy(serviceType = type)) })

            val actual = native.getForegroundInfo()
            val notification = actual.notification
            assertEquals(info.notificationId, actual.notificationId)
            assertEquals(if (Build.VERSION.SDK_INT >= 30) constant else 0, actual.foregroundServiceType)
            assertEquals(info.title, notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(info.message, notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(37, notification.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertEquals(android.R.drawable.stat_notify_sync, notification.smallIcon.resId)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(AndroidForegroundBridge.DEFAULT_CHANNEL_ID, notification.channelId)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = assertNotNull(manager.getNotificationChannel(notification.channelId))
                assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            }
            assertUnregistered(native)
        }
    }

    @Test
    fun provider_appliesCustomizerWithNativeIdAndDescriptorWithoutPromoting() = runTest {
        var customizerCalls = 0
        val native = buildWorker(factory { foregroundWorker(it) })
        AndroidForegroundBridge.notificationCustomizer =
            AndroidForegroundBridge.NotificationCustomizer { ctx, id, value, b ->
                assertSame(context, ctx)
                assertEquals(native.id.toString(), id.toString())
                assertSame(info, value)
                customizerCalls++
                b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setChannelId("app.downloads")
                    .setContentText("定制文案")
                    .addAction(0, "Pause", null)
            }

        val actual = native.getForegroundInfo()

        assertEquals(1, customizerCalls)
        assertEquals(info.notificationId, actual.notificationId)
        assertEquals(android.R.drawable.stat_sys_download, actual.notification.smallIcon.resId)
        assertEquals("定制文案", actual.notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Pause", actual.notification.actions.single().title)
        if (Build.VERSION.SDK_INT >= 26) assertEquals("app.downloads", actual.notification.channelId)
        assertUnregistered(native)
    }

    @Test
    fun customizerFailure_doesNotRegisterBridge() = runTest {
        val native = buildWorker(factory { foregroundWorker(it) })
        val failure = IllegalArgumentException("invalid notification")
        AndroidForegroundBridge.notificationCustomizer = AndroidForegroundBridge.NotificationCustomizer { _, _, _, _ ->
            throw failure
        }

        assertSame(failure, assertFailsWith<IllegalArgumentException> { native.getForegroundInfo() })
        assertUnregistered(native)
    }

    @Test
    fun bridge_unregisterReleasesOnlyTheExpectedOwner() {
        val id = Uuid.random()
        val first = buildWorker()
        val owner = buildWorker()
        // 仅验证注册资源的配对释放，不模拟 SDK 并发执行同一任务。
        AndroidForegroundBridge.register(id, first)
        AndroidForegroundBridge.register(id, owner)
        try {
            AndroidForegroundBridge.unregister(id, first)
            assertSame(owner, nativeWorkerFor(id))
        } finally {
            AndroidForegroundBridge.unregister(id, owner)
        }
        assertEquals(null, nativeWorkerFor(id))
    }

    private fun foregroundWorker(workerContext: WorkerContext, value: ForegroundInfo = info): ForegroundWorker =
        object : ForegroundWorker(workerContext) {
            override suspend fun getForegroundInfo(): ForegroundInfo = value
            override suspend fun doWork(): WorkResult = WorkResult.success()
        }

    private fun buildWorker(workerFactory: KmpAndroidWorkerFactory? = null): KmpAndroidWorker =
        TestListenableWorkerBuilder<KmpAndroidWorker>(
            context,
            inputData = workDataOf(KEY_KMP_CLASS to "foreground-provider-test", "business" to "payload"),
            runAttemptCount = 3,
        ).setForegroundUpdater(ForegroundUpdater { _, _, _ -> error("provider must not promote") }).apply {
            workerFactory?.let { setWorkerFactory(KmpWorkerFactory(it)) }
        }.build()

    private fun factory(createWorker: (WorkerContext) -> CoroutineWorker): KmpAndroidWorkerFactory =
        object : KmpAndroidWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                assertEquals("foreground-provider-test", workerClass)
                return createWorker(context)
            }
        }

    private fun unexpectedFactory(): KmpAndroidWorkerFactory = factory { error("captured factory must be retained") }

    private fun assertUnregistered(native: KmpAndroidWorker) {
        assertEquals(null, nativeWorkerFor(Uuid.parse(native.id.toString())))
    }

    private fun nativeWorkerFor(id: Uuid): KmpAndroidWorker? {
        val field = AndroidForegroundBridge::class.java.getDeclaredField("activeWorkers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(AndroidForegroundBridge) as Map<Uuid, KmpAndroidWorker>)[id]
    }
}
