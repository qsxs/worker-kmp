package io.github.mobilebytelabs.worker.android

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ProgressUpdater
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExperimentalForegroundApi
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.github.mobilebytelabs.worker.workDataOf as kmpWorkDataOf

@OptIn(ExperimentalForegroundApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KmpAndroidWorkerTest {
    private lateinit var fixture: NativeIdentityFixture

    @Before
    fun setUp() {
        fixture = NativeIdentityFixture { context ->
            // 在真实 doWork 生命周期内核对 bridge 注册键，结束后还要验证 finally 清理。
            val active = activeBridgeWorkers()
            assertEquals(setOf(context.id), active.keys)
            assertEquals(context.id.nativeId(), active[context.id]?.id)
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun kmpAndroidWorker_classNameIsNonNull() {
        assertNotNull(KmpAndroidWorker::class.qualifiedName)
    }

    @Test
    fun doWork_usesNativeIdEvenWhenInputAndTagClaimAnotherId() = runTest {
        val metadataId = Uuid.random()
        val request = OneTimeWorkRequestBuilder<KmpAndroidWorker>()
            .setInputData(
                workDataOf(
                    KEY_KMP_CLASS to SimpleSuccessWorker::class.qualifiedName,
                    KEY_KMP_ID to metadataId.toString(),
                    "payload" to "native-id",
                ),
            )
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag(metadataId.toTag())
            .addTag("bridge-tag")
            .build()
        fixture.native.enqueue(request).result.get()
        fixture.driver.setInitialDelayMet(request.id)

        val nativeId = Uuid.parse(request.id.toString())
        fixture.assertExecution(nativeId, "native-id", "bridge-tag")
        assertNull(fixture.executions.single().inputData.getString(KEY_KMP_ID))
        assertNull(fixture.executions.single().inputData.getString(KEY_KMP_CLASS))
        assertEquals(WorkInfo.State.SUCCEEDED, fixture.native.getWorkInfoById(request.id).get()?.state)
        assertNotNull(KmpWorkerFactory.instance)
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_doesNotRequireValidInputIdOrInternalTag() = runTest {
        listOf(null, "not-a-uuid").forEach { metadataId ->
            val input = androidx.work.Data.Builder()
                .putString(KEY_KMP_CLASS, SimpleSuccessWorker::class.qualifiedName)
                .putString("payload", "without-id")
            if (metadataId != null) input.putString(KEY_KMP_ID, metadataId)
            val request = OneTimeWorkRequestBuilder<KmpAndroidWorker>()
                .setInputData(input.build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .addTag("bridge-tag")
                .build()
            fixture.native.enqueue(request).result.get()
            fixture.driver.setInitialDelayMet(request.id)

            fixture.assertExecution(Uuid.parse(request.id.toString()), "without-id", "bridge-tag")
            assertEquals(WorkInfo.State.SUCCEEDED, fixture.native.getWorkInfoById(request.id).get()?.state)
            assertTrue(activeBridgeWorkers().isEmpty())
        }
        assertEquals(2, fixture.executions.size)
    }

    @Test
    fun doWork_forwardsFullProgressPayloadAndAwaitsNativeUpdaterThenPreservesSuccessOutput() = runTest {
        val business = kmpWorkDataOf(
            "INDEX" to 2, "TOTAL" to 8, "MESSAGE" to "备份中",
            "LAST_RESULT_CODE" to 0, "LAST_RESULT_MESSAGE" to "完成",
            "progress" to 777, KEY_KMP_CLASS to "business-class", KEY_KMP_ID to "business-id",
            "byte" to Byte.MIN_VALUE, "long" to Long.MAX_VALUE, "double" to 2.5,
            "bytes" to byteArrayOf(1, 2), "empty" to emptyArray<Double>(), "null" to null,
        )
        val output = kmpWorkDataOf(
            *business.keyValueMap().map { it.toPair() }.toTypedArray(),
            KEY_KMP_PROGRESS to 19,
            "booleans" to booleanArrayOf(true, false),
            "nullable" to arrayOf<String?>("结果", null),
        )
        var captured: Data? = null
        var updatedId: java.util.UUID? = null
        lateinit var completion: CallbackToFutureAdapter.Completer<Void>
        val updater = ProgressUpdater { _, id, data ->
            updatedId = id
            captured = data
            CallbackToFutureAdapter.getFuture<Void> { completer ->
                completion = completer
                "captured progress update"
            }
        }
        var continued = false
        val worker = testWorker(updater) { context ->
            assertEquals("allowed", context.inputData.getString("kmp_business"))
            assertNull(context.inputData.getString(KEY_KMP_CLASS))
            assertNull(context.inputData.getString(KEY_KMP_ID))
            context.setProgress(WorkProgress(37, business))
            continued = true
            WorkResult.success(output)
        }
        val execution = async(start = CoroutineStart.UNDISPATCHED) { worker.doWork() }
        val actualProgress = assertNotNull(captured)
        assertEquals(worker.id, updatedId)
        assertEquals(Data.Builder().putAll(business.keyValueMap()).putInt(KEY_KMP_PROGRESS, 37).build(), actualProgress)
        assertEquals<Class<*>?>(Array<Byte>::class.java, actualProgress.keyValueMap["bytes"]?.javaClass)
        assertEquals<Class<*>?>(Array<Double>::class.java, actualProgress.keyValueMap["empty"]?.javaClass)
        assertFalse(continued)
        assertFalse(execution.isCompleted)

        completion.set(null)
        val result = assertIs<ListenableWorker.Result.Success>(execution.await())
        assertTrue(continued)
        assertEquals(Data.Builder().putAll(output.keyValueMap()).build(), result.outputData)
        assertEquals<Class<*>?>(Array<Boolean>::class.java, result.outputData.keyValueMap["booleans"]?.javaClass)
        val observed = androidx.work.WorkInfo(
            worker.id,
            WorkInfo.State.SUCCEEDED,
            emptySet(),
            result.outputData,
            actualProgress,
        ).toKmp()
        assertEquals(37, observed.progress.progress)
        assertEquals(Data.Builder().putAll(business.keyValueMap()).build(), observed.progress.data.toAndroid())
        assertFalse(observed.progress.data.hasKey(KEY_KMP_PROGRESS))
        assertEquals(result.outputData, observed.outputData.toAndroid())
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_rejectsOnlyReservedProgressPayloadKeyBeforeCallingUpdater() = runTest {
        var updaterCalls = 0
        val updater = ProgressUpdater { _, _, _ ->
            updaterCalls++
            CallbackToFutureAdapter.getFuture<Void> { completer ->
                completer.set(null)
                "unexpected progress update"
            }
        }
        val worker = testWorker(updater) { context ->
            context.setProgress(WorkProgress(25, kmpWorkDataOf(KEY_KMP_PROGRESS to null)))
            WorkResult.success()
        }
        assertFailsWith<IllegalArgumentException> { worker.doWork() }
        assertEquals(0, updaterCalls)
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_failurePreservesPayloadIncludingBusinessErrorAndMetadataNamedFields() = runTest {
        val output = kmpWorkDataOf(
            "error" to "business-error",
            KEY_KMP_CLASS to "business-class",
            KEY_KMP_ID to "business-id",
            KEY_KMP_PROGRESS to 19,
            "long" to Long.MIN_VALUE,
            "bytes" to byteArrayOf(1, 2),
            "empty" to emptyArray<Double>(),
            "null" to null,
        )
        val worker = testWorker { WorkResult.Failure("message-must-not-overwrite-payload", output) }

        val result = assertIs<ListenableWorker.Result.Failure>(worker.doWork())

        val expected = Data.Builder().putAll(output.keyValueMap()).build()
        assertEquals(expected, result.outputData)
        assertEquals("business-error", result.outputData.getString("error"))
        assertEquals<Class<*>?>(Array<Byte>::class.java, result.outputData.keyValueMap["bytes"]?.javaClass)
        val observed = androidx.work.WorkInfo(
            worker.id,
            WorkInfo.State.FAILED,
            emptySet(),
            result.outputData,
        ).toKmp()
        assertEquals(expected, observed.outputData.toAndroid())
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_failureWithEmptyPayloadPreservesLegacyMessageAsError() = runTest {
        val worker = testWorker { WorkResult.failure("legacy-message") }

        val result = assertIs<ListenableWorker.Result.Failure>(worker.doWork())

        assertEquals(workDataOf("error" to "legacy-message"), result.outputData)
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_defaultFailureHasEmptyNativeOutputData() = runTest {
        val worker = testWorker { WorkResult.Failure() }

        val result = assertIs<ListenableWorker.Result.Failure>(worker.doWork())

        assertEquals(Data.EMPTY, result.outputData)
        assertTrue(result.outputData.keyValueMap.isEmpty())
        assertTrue(activeBridgeWorkers().isEmpty())
    }

    @Test
    fun doWork_passesNativeRunAttemptCountToActualFactoryContext() = runTest {
        listOf(0, 3).forEach { attempt ->
            var factoryContext: WorkerContext? = null
            val worker = testWorker(
                runAttemptCount = attempt,
                onContextCreated = { factoryContext = it },
            ) { context ->
                assertSame(factoryContext, context)
                assertEquals(attempt, context.runAttemptCount)
                WorkResult.success()
            }
            assertEquals(attempt, worker.runAttemptCount)

            assertIs<ListenableWorker.Result.Success>(worker.doWork())

            val context = assertIs<AndroidWorkerContext>(factoryContext)
            assertEquals(worker.id, context.id.nativeId())
            assertEquals(worker.runAttemptCount, context.runAttemptCount)
            assertTrue(activeBridgeWorkers().isEmpty())
        }
    }

    private fun testWorker(
        updater: ProgressUpdater? = null,
        runAttemptCount: Int = 0,
        onContextCreated: (WorkerContext) -> Unit = {},
        execute: suspend (WorkerContext) -> WorkResult,
    ): KmpAndroidWorker = TestListenableWorkerBuilder<KmpAndroidWorker>(
        fixture.context,
        inputData = workDataOf(
            KEY_KMP_CLASS to "payload-worker",
            KEY_KMP_ID to "metadata-id",
            "kmp_business" to "allowed",
        ),
    ).setRunAttemptCount(runAttemptCount).setWorkerFactory(
        KmpWorkerFactory(object : KmpAndroidWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker {
                onContextCreated(context)
                return object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult = execute(context)
                }
            }
        }),
    ).apply { updater?.let { setProgressUpdater(it) } }.build()

    private fun activeBridgeWorkers(): Map<Uuid, KmpAndroidWorker> {
        val field = AndroidForegroundBridge::class.java.getDeclaredField("activeWorkers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(AndroidForegroundBridge) as Map<Uuid, KmpAndroidWorker>
    }
}
