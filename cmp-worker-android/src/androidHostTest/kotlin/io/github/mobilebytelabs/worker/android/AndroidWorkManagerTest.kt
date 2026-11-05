package io.github.mobilebytelabs.worker.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.oneTimeWorkRequest
import io.github.mobilebytelabs.worker.periodicWorkRequest
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import io.github.mobilebytelabs.worker.ExistingWorkPolicy as KmpExistingWorkPolicy
import io.github.mobilebytelabs.worker.WorkInfo as KmpWorkInfo

/** 模拟旧 BackupWorker 类的普通原生任务，不包含任何 KMP 输入或内部标签。 */
class LegacyNativeBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidWorkManagerTest {
    private lateinit var fixture: NativeIdentityFixture

    @Before
    fun setUp() {
        fixture = NativeIdentityFixture()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun constructor_createsValidWorkManager() {
        assertNotNull(fixture.manager, "AndroidWorkManager must be non-null after construction")
    }

    @Test
    fun getWorkInfoById_withNonexistentId_returnsNull() = runTest {
        assertNull(fixture.manager.getWorkInfoById(Uuid.random()))
    }

    @Test
    fun getWorkInfoByIdFlow_continuousCollectorObservesNativeInsertAndCancellation() =
        runBlocking(Dispatchers.Default) {
            // 使用真实时钟等待 Room 的异步通知；每次收到状态后才允许下一次数据库变更。
            withTimeout(10.seconds) {
                val id = Uuid.parse("d0295c50-d418-4f28-b58f-f23105004530")
                val events = Channel<KmpWorkInfo?>(Channel.RENDEZVOUS)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.manager.getWorkInfoByIdFlow(id).collect { events.send(it) }
                }
                try {
                    assertNull(events.receive())
                    val request = OneTimeWorkRequestBuilder<LegacyNativeBackupWorker>()
                        .setId(id.nativeId())
                        .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .addTag("continuous-native")
                        .build()
                    fixture.native.enqueue(request).result.await()

                    val enqueued = assertNotNull(events.receive())
                    assertEquals(id, enqueued.id)
                    assertEquals(KmpWorkInfo.State.ENQUEUED, enqueued.state)
                    assertTrue("continuous-native" in enqueued.tags)
                    assertTrue(collector.isActive)

                    fixture.native.cancelWorkById(request.id).result.await()

                    val cancelled = assertNotNull(events.receive())
                    assertEquals(id, cancelled.id)
                    assertEquals(KmpWorkInfo.State.CANCELLED, cancelled.state)
                    assertEquals(WorkInfo.State.CANCELLED, fixture.native.getWorkInfoById(request.id).await()?.state)
                    assertTrue(collector.isActive)
                    assertTrue(fixture.executions.isEmpty())
                } finally {
                    collector.cancelAndJoin()
                    events.close()
                }
            }
        }

    @Test
    fun cancelUniqueWork_cancelsKeepAppendChainWithoutCancellingSameTagOrMissingName() = runTest {
        val uniqueName = "cancel-unique-chain"
        val head = delayedOneTime("head", uniqueName)
        val ignored = delayedOneTime("ignored", "ignored-keep")
        val child = delayedOneTime("child", uniqueName)
        val tail = delayedOneTime("tail", uniqueName)
        val unrelated = delayedOneTime("unrelated", uniqueName)
        fixture.manager.enqueueUniqueWork(uniqueName, KmpExistingWorkPolicy.KEEP, head)
        fixture.manager.enqueueUniqueWork(uniqueName, KmpExistingWorkPolicy.KEEP, ignored)
        fixture.manager.enqueueUniqueWork(uniqueName, KmpExistingWorkPolicy.APPEND, child)
        fixture.manager.enqueueUniqueWork(uniqueName, KmpExistingWorkPolicy.APPEND, tail)
        fixture.manager.enqueueUniqueWork("unrelated-unique-chain", KmpExistingWorkPolicy.KEEP, unrelated)
        val chainIds = setOf(head.id.nativeId(), child.id.nativeId(), tail.id.nativeId())
        val initialChain = fixture.native.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(chainIds, initialChain.map { it.id }.toSet())
        assertEquals(WorkInfo.State.ENQUEUED, initialChain.single { it.id == head.id.nativeId() }.state)
        assertEquals(WorkInfo.State.BLOCKED, initialChain.single { it.id == child.id.nativeId() }.state)
        assertEquals(WorkInfo.State.BLOCKED, initialChain.single { it.id == tail.id.nativeId() }.state)
        assertNull(fixture.native.getWorkInfoById(ignored.id.nativeId()).get())
        assertNull(fixture.manager.getWorkInfoById(ignored.id))
        val originalStates = listOf(head, child, tail, unrelated).associate { request ->
            request.id to assertNotNull(fixture.native.getWorkInfoById(request.id.nativeId()).get()).state
        }

        fixture.manager.cancelUniqueWork("missing-unique-chain")

        originalStates.forEach { (id, state) ->
            assertEquals(state, fixture.native.getWorkInfoById(id.nativeId()).get()?.state)
        }
        fixture.manager.cancelUniqueWork(uniqueName)

        val cancelledChain = fixture.native.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(chainIds, cancelledChain.map { it.id }.toSet())
        assertTrue(cancelledChain.all { it.state == WorkInfo.State.CANCELLED })
        listOf(head, child, tail).forEach { request ->
            assertEquals(KmpWorkInfo.State.CANCELLED, fixture.manager.getWorkInfoById(request.id)?.state)
        }
        val survivor = assertNotNull(fixture.native.getWorkInfoById(unrelated.id.nativeId()).get())
        assertTrue(uniqueName in survivor.tags)
        assertEquals(WorkInfo.State.ENQUEUED, survivor.state)
        assertEquals(KmpWorkInfo.State.ENQUEUED, fixture.manager.getWorkInfoById(unrelated.id)?.state)
        assertNull(fixture.native.getWorkInfoById(ignored.id.nativeId()).get())
        assertTrue(fixture.executions.isEmpty())
    }

    @Test
    fun legacyNativeWork_withoutKmpMetadata_remainsVisibleAndCancellable() = runTest {
        val request = OneTimeWorkRequestBuilder<LegacyNativeBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag("legacy-backup")
            .build()
        assertTrue(request.tags.none { it.startsWith(TAG_KMP_ID_PREFIX) })
        fixture.native.enqueueUniqueWork("legacy-backup-unique", ExistingWorkPolicy.KEEP, request).result.get()
        val id = Uuid.parse(request.id.toString())

        val byId = assertNotNull(fixture.manager.getWorkInfoById(id))
        assertEquals(id, byId.id)
        assertEquals(io.github.mobilebytelabs.worker.WorkInfo.State.ENQUEUED, byId.state)
        assertTrue("legacy-backup" in byId.tags)
        assertEquals(listOf(id), fixture.manager.getWorkInfosByTag("legacy-backup").first().map { it.id })
        assertEquals(
            listOf(id),
            fixture.manager.getWorkInfosForUniqueWorkFlow("legacy-backup-unique").first().map { it.id },
        )

        fixture.manager.cancelWorkById(id)
        assertEquals(WorkInfo.State.CANCELLED, fixture.native.getWorkInfoById(request.id).get()?.state)
        assertEquals(
            io.github.mobilebytelabs.worker.WorkInfo.State.CANCELLED,
            fixture.manager.getWorkInfoById(id)?.state,
        )
    }

    @Test
    fun cancelWorkById_doesNotCancelAnotherNativeRecordWithMatchingInternalTag() = runTest {
        val request = delayedOneTime("target", "target-tag")
        fixture.manager.enqueue(request)
        val unrelated = OneTimeWorkRequestBuilder<LegacyNativeBackupWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag(request.id.toTag())
            .build()
        fixture.native.enqueue(unrelated).result.get()

        fixture.manager.cancelWorkById(request.id)

        assertEquals(WorkInfo.State.CANCELLED, fixture.native.getWorkInfoById(request.id.nativeId()).get()?.state)
        assertEquals(WorkInfo.State.ENQUEUED, fixture.native.getWorkInfoById(unrelated.id).get()?.state)
        assertEquals(
            Uuid.parse(unrelated.id.toString()),
            fixture.manager.getWorkInfoById(Uuid.parse(unrelated.id.toString()))?.id,
        )
    }

    @Test
    fun enqueue_rejectsActualInputMetadataKeysIncludingNullBeforeDatabaseInsert() = runTest {
        listOf(KEY_KMP_CLASS, KEY_KMP_ID).forEach { key ->
            listOf(null, "forged").forEach { value ->
                assertRejected<IllegalArgumentException>(workDataOf(key to value))
            }
        }
    }

    @Test
    fun enqueue_rejectsInternalTagPrefixBeforeDatabaseInsert() = runTest {
        listOf(TAG_KMP_ID_PREFIX, "${TAG_KMP_ID_PREFIX}forged").forEach { tag ->
            assertRejected<IllegalArgumentException>(WorkData.EMPTY, tag)
        }
    }

    @Test
    fun enqueue_rejectsUnknownDataTypeBeforeDatabaseInsert() = runTest {
        assertRejected<IllegalArgumentException>(workDataOf("unknown" to Any()))
    }

    @Test
    fun enqueue_validatesSizeAfterAddingMetadataBeforeDatabaseInsert() = runTest {
        val userData = workDataOf("payload" to "x".repeat(Data.MAX_DATA_BYTES - 32))
        val userOnly = Data.Builder().putAll(userData.keyValueMap()).build()
        assertTrue(Data.toByteArrayInternalV1(userOnly).size <= Data.MAX_DATA_BYTES)
        assertRejected<IllegalStateException>(userData)
    }

    @Test
    fun enqueue_preservesOtherKmpKeysTagsAndNativeArrayTypesInDatabase() = runTest {
        val input = workDataOf(
            "kmp_business" to "allowed",
            KEY_KMP_PROGRESS to 12,
            "bytes" to byteArrayOf(1, 2),
            "empty" to emptyArray<Double>(),
            "null" to null,
        )
        val oneTime = oneTimeWorkRequest<SimpleSuccessWorker> {
            setInputData(input)
            setInitialDelay(1.hours)
            addTag("kmp_business")
        }
        val periodic = periodicWorkRequest<SimpleSuccessWorker>(15.minutes) {
            setInputData(input)
            setInitialDelay(1.hours)
            addTag("kmp_business")
        }
        fixture.manager.enqueue(oneTime)
        fixture.manager.enqueueUniquePeriodicWork(
            "accepted-data",
            io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        listOf(oneTime, periodic).forEach { request ->
            val record = assertNotNull(fixture.native.getWorkInfoById(request.id.nativeId()).get())
            assertEquals(WorkInfo.State.ENQUEUED, record.state)
            assertTrue("kmp_business" in record.tags)
            fixture.runFirst(request.id)
            val nativeWorker = fixture.nativeWorkers.last()
            assertEquals(request.id.nativeId(), nativeWorker.id)
            // 直接核对原生 WorkManager 从数据库传给真实 Worker 的输入，不依赖 Room 内部 API。
            val stored = nativeWorker.inputData
            val expected = Data.Builder().putAll(input.keyValueMap())
                .putString(KEY_KMP_CLASS, request.workerClass).putString(KEY_KMP_ID, request.id.toString()).build()
            assertEquals(expected, stored)
        }
    }

    private suspend inline fun <reified T : Throwable> assertRejected(input: WorkData, tag: String = "rejected") {
        val oneTime = oneTimeWorkRequest<SimpleSuccessWorker> {
            setInputData(input)
            setInitialDelay(1.hours)
            addTag(tag)
        }
        val periodic = periodicWorkRequest<SimpleSuccessWorker>(15.minutes) {
            setInputData(input)
            setInitialDelay(1.hours)
            addTag(tag)
        }
        assertFailsWith<T> { fixture.manager.enqueue(oneTime) }
        assertFailsWith<T> {
            fixture.manager.enqueueUniqueWork(
                "rejected-one-time",
                io.github.mobilebytelabs.worker.ExistingWorkPolicy.KEEP,
                oneTime,
            )
        }
        assertFailsWith<T> {
            fixture.manager.enqueueUniquePeriodicWork(
                "rejected-periodic",
                io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }
        listOf(oneTime, periodic).forEach { request ->
            assertNull(fixture.manager.getWorkInfoById(request.id))
            assertNull(
                fixture.native.getWorkInfoById(request.id.nativeId()).get(),
                "Rejected request must not exist in the native database",
            )
        }
        assertTrue(fixture.native.getWorkInfosForUniqueWork("rejected-one-time").get().isEmpty())
        assertTrue(fixture.native.getWorkInfosForUniqueWork("rejected-periodic").get().isEmpty())
    }
}
