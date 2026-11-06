package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkEnqueueException
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.UniqueWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.WorkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class IosWorkRecordCodecTest {
    @Test
    fun oneTimeRequestRestoresFullPayloadAndExecutionMetadata() {
        val retry = RetryConfig(7, 15.seconds, 2.minutes, BackoffPolicy.LINEAR, 3.5)
        val id = Uuid.random()
        val constraints = Constraints {
            setRequiredNetworkType(NetworkType.UNMETERED)
            setRequiresCharging(true)
            setRequiresDeviceIdle(true)
            setRequiresBatteryNotLow(true)
            setRequiresStorageNotLow(true)
            addContentUriTrigger("content://tasks/a|b", triggerForDescendants = true)
        }
        val input = WorkData(
            "string" to "a|b,c\n日",
            "boolean" to true,
            "byte" to Byte.MIN_VALUE,
            "short" to Short.MAX_VALUE,
            "int" to Int.MIN_VALUE,
            "long" to Long.MAX_VALUE,
            "float" to -0.0f,
            "double" to Double.POSITIVE_INFINITY,
            "booleans" to booleanArrayOf(false, true),
            "bytes" to byteArrayOf(Byte.MIN_VALUE, Byte.MAX_VALUE),
            "shorts" to shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE),
            "ints" to intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE),
            "longs" to longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
            "floats" to floatArrayOf(Float.NEGATIVE_INFINITY, -0.0f),
            "doubles" to doubleArrayOf(-0.0, Double.POSITIVE_INFINITY),
            "strings" to arrayOf("x|y", null, ""),
            "null" to null,
        )
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("Backup|Worker").apply {
            setId(id)
            setInputData(input)
            setConstraints(constraints)
            setBackoffCriteria(retry.backoffPolicy, retry)
            setInitialDelay(31.nanoseconds)
            setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
            addTag("request|tag,1")
        }.build()
        val info = WorkInfo(
            id = id,
            state = WorkInfo.State.RUNNING,
            progress = WorkProgress(42, WorkData("message" to "pending|retry")),
            outputData = WorkData("result" to 99L),
            tags = setOf("observable|tag,2"),
            runAttemptCount = 4,
        )
        val unique = UniqueWork("backup|batch", WorkKind.ONE_TIME)

        val encoded = IosWorkRecordCodec.encode(StoredWork(info, unique, request))
        val decoded = assertNotNull(IosWorkRecordCodec.decode(encoded))

        assertTrue(encoded.startsWith("$id|2|"))
        assertEquals(info, decoded.info)
        assertEquals(unique, decoded.uniqueWork)
        val restored = assertIs<OneTimeWorkRequest>(decoded.request)
        assertEquals(request.id, restored.id)
        assertEquals(request.workerClass, restored.workerClass)
        assertEquals(request.tags, restored.tags)
        assertEquals(request.initialDelay, restored.initialDelay)
        assertEquals(request.expeditedPolicy, restored.expeditedPolicy)
        assertEquals(retry, restored.retryConfig)
        assertEquals(constraints.requiredNetworkType, restored.constraints.requiredNetworkType)
        assertEquals(constraints.contentUriTriggers, restored.constraints.contentUriTriggers)
        assertTrue(restored.constraints.requiresCharging)
        assertTrue(restored.constraints.requiresDeviceIdle)
        assertTrue(restored.constraints.requiresBatteryNotLow)
        assertTrue(restored.constraints.requiresStorageNotLow)
        assertDataEquals(input, restored.inputData)
    }

    @Test
    fun periodicRequestRestoresTimingAndQuickRefresh() {
        val request = periodicRequest()
        val unique = UniqueWork("nightly", WorkKind.PERIODIC)
        val info = WorkInfo(id = request.id, state = WorkInfo.State.SUCCEEDED)

        val encoded = IosWorkRecordCodec.encode(
            StoredWork(info, unique, request, nextRunAtEpochMillis = Long.MAX_VALUE),
        )
        val decoded = assertNotNull(IosWorkRecordCodec.decode(encoded))

        assertEquals(info, decoded.info)
        assertEquals(unique, decoded.uniqueWork)
        assertEquals(Long.MAX_VALUE, decoded.nextRunAtEpochMillis)
        val restored = assertIs<PeriodicWorkRequest>(decoded.request)
        assertEquals(request.id, restored.id)
        assertEquals(request.workerClass, restored.workerClass)
        assertEquals(request.tags, restored.tags)
        assertEquals(request.inputData, restored.inputData)
        assertEquals(request.initialDelay, restored.initialDelay)
        assertEquals(request.repeatInterval, restored.repeatInterval)
        assertEquals(request.flexTimeInterval, restored.flexTimeInterval)
        assertEquals(request.retryConfig, restored.retryConfig)
        assertEquals(request.quickRefresh, restored.quickRefresh)
    }

    @Test
    fun legacyRecordRestoresStatusWithoutInventingExecutableRequest() {
        val id = Uuid.random()
        val restored = assertNotNull(IosWorkRecordCodec.decode("$id|RUNNING|3|backup,nightly"))

        assertEquals(id, restored.info.id)
        assertEquals(WorkInfo.State.RUNNING, restored.info.state)
        assertEquals(3, restored.info.runAttemptCount)
        assertEquals(setOf("backup", "nightly"), restored.info.tags)
        assertNull(restored.request)
        assertNull(restored.uniqueWork)
        assertNull(restored.nextRunAtEpochMillis)
        assertNull(IosWorkRecordCodec.decode("$id|3|{}"))
        assertNull(IosWorkRecordCodec.decode("$id|2|{}"))
    }

    @Test
    fun unsupportedPayloadIsRejectedWithoutExecutableFallback() {
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInputData(WorkData("opaque" to listOf("unsupported")))
        }.build()
        val record = StoredWork(WorkInfo(request.id, WorkInfo.State.ENQUEUED), request = request)

        assertFails { IosWorkRecordCodec.encode(record) }
        val supported = periodicRequest()
        val encoded = IosWorkRecordCodec.encode(
            StoredWork(WorkInfo(supported.id, WorkInfo.State.ENQUEUED), request = supported),
        )
        val corrupted = encoded.replace("\"type\":\"string\"", "\"type\":\"unknown\"")
        assertNull(IosWorkRecordCodec.decode(corrupted))
    }

    @Test
    fun unsupportedReplacementLeavesPreviouslyAcceptedUniqueWorkIntact() {
        val persistence = object : IosWorkPersistence by InMemoryIosWorkPersistence() {
            override fun validateRequest(request: WorkRequest) = IosWorkRecordCodec.validateRequest(request)
        }
        val stateStore = IosWorkStateStore(persistence)
        val original = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").build()
        val unsupported = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInputData(WorkData("opaque" to listOf("unsupported")))
        }.build()

        stateStore.enqueueUniqueOneTime(
            "backup",
            ExistingWorkPolicy.KEEP,
            original.id,
            original.tags,
            request = original,
        )
        assertFailsWith<IllegalArgumentException> {
            stateStore.enqueueUniqueOneTime(
                "backup",
                ExistingWorkPolicy.REPLACE,
                unsupported.id,
                unsupported.tags,
                request = unsupported,
            )
        }

        val retained = stateStore.snapshot().values.single()
        assertEquals(original.id, retained.info.id)
        assertEquals(WorkInfo.State.ENQUEUED, retained.info.state)
    }

    @Test
    fun stateStorePersistsPeriodicWorkBetweenRunsAndRemovesItOnCancellation() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val request = periodicRequest()
        val unique = UniqueWork("nightly", WorkKind.PERIODIC)
        val original = IosWorkStateStore(persistence, backgroundScope)
        original.initWork(request.id, request.tags, unique, request, nextRunAtEpochMillis = 100L)
        assertTrue(original.transitionToRunning(request.id, periodic = true))
        assertNull(original.snapshot()[request.id]?.nextRunAtEpochMillis)
        val nextDue = iosDueAtEpochMillis(1_000L, request.repeatInterval)
        assertTrue(original.finishWork(request.id, succeeded = true, WorkData("written" to 1), nextDue))
        runCurrent()

        val saved = persistence.loadRecords().single()
        val reloaded = assertNotNull(IosWorkRecordCodec.decode(IosWorkRecordCodec.encode(saved)))
        val reopenedPersistence = InMemoryIosWorkPersistence().apply { saveRecord(reloaded) }
        val reopened = IosWorkStateStore(reopenedPersistence, backgroundScope)
        reopened.restoreFromPersistence()

        val restored = assertNotNull(reopened.snapshot()[request.id])
        assertEquals(WorkInfo.State.SUCCEEDED, restored.info.state)
        assertEquals(nextDue, restored.nextRunAtEpochMillis)
        assertEquals(1, restored.info.outputData.getInt("written"))
        assertEquals(unique, restored.uniqueWork)
        assertEquals(request.repeatInterval, assertIs<PeriodicWorkRequest>(restored.request).repeatInterval)
        assertTrue(reopened.transitionToRunning(request.id, periodic = true))
        assertNull(reopened.snapshot()[request.id]?.nextRunAtEpochMillis)
        val afterFailure = iosDueAtEpochMillis(nextDue + 500L, request.repeatInterval)
        assertTrue(reopened.finishWork(request.id, succeeded = false, WorkData.EMPTY, afterFailure))
        runCurrent()
        val failed = reopenedPersistence.loadRecords().single()
        assertEquals(WorkInfo.State.FAILED, failed.info.state)
        assertEquals(afterFailure, failed.nextRunAtEpochMillis)
        assertTrue(reopened.cancelWork(request.id, periodic = true))
        assertNull(reopened.snapshot()[request.id]?.nextRunAtEpochMillis)
        runCurrent()
        assertTrue(reopenedPersistence.loadRecords().isEmpty())
    }

    @Test
    fun dueTimeArithmeticSaturatesAndRetainsRetryPolicy() {
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInitialDelay(3.seconds)
        }.build()
        assertEquals(4_000L, iosInitialDueAtEpochMillis(1_000L, request))
        assertEquals(10L, iosDueAtEpochMillis(10L, (-5).milliseconds))
        assertEquals(Long.MAX_VALUE, iosDueAtEpochMillis(Long.MAX_VALUE - 5L, 10.milliseconds))
        assertEquals(Long.MAX_VALUE, iosRemainingDelayMillis(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(200L, iosRemainingDelayMillis(300L, 500L))
        assertEquals(0L, iosRemainingDelayMillis(500L, 499L))
        assertEquals(0L, iosRemainingDelayMillis(500L, null))

        val retry = RetryConfig(initialDelay = 2.seconds, maxDelay = 5.seconds, multiplier = 2.0)
        assertEquals(4.seconds, iosBackoffDelay(retry, 1))
        assertEquals(5.seconds, iosBackoffDelay(retry, 2))
        val linear = retry.copy(backoffPolicy = BackoffPolicy.LINEAR)
        assertEquals(4.seconds, iosBackoffDelay(linear, 1))
        assertEquals(5.seconds, iosBackoffDelay(linear, 2))
        assertEquals(Duration.ZERO, iosBackoffDelay(linear.copy(maxDelay = (-1).seconds), 2))
        assertEquals(
            Duration.INFINITE,
            iosBackoffDelay(linear.copy(initialDelay = Duration.INFINITE, maxDelay = Duration.INFINITE), Int.MAX_VALUE),
        )
    }

    @Test
    fun duplicateUniqueEnqueueKeepsItsOriginallyPersistedDueTime() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val original = IosWorkStateStore(persistence, backgroundScope)
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").apply {
            setInitialDelay(2.seconds)
        }.build()
        val dueAt = iosInitialDueAtEpochMillis(1_000L, request)
        assertTrue(
            original.enqueueUniqueOneTime(
                "backup",
                ExistingWorkPolicy.KEEP,
                request.id,
                request.tags,
                request = request,
                nextRunAtEpochMillis = dueAt,
            ).enqueued,
        )
        runCurrent()
        val reopened = IosWorkStateStore(persistence)
        reopened.restoreFromPersistence()
        assertFalse(
            reopened.enqueueUniqueOneTime(
                "backup",
                ExistingWorkPolicy.KEEP,
                request.id,
                request.tags,
                request = request,
                nextRunAtEpochMillis = 8_000L,
            ).enqueued,
        )

        assertEquals(dueAt, reopened.snapshot()[request.id]?.nextRunAtEpochMillis)
        assertEquals(dueAt, persistence.loadRecords().single().nextRunAtEpochMillis)
    }

    @Test
    fun explicitReattachmentCanFillMissingV2DueWithoutInventingALegacyRequest() = runTest {
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").build()
        val encoded = IosWorkRecordCodec.encode(
            StoredWork(WorkInfo(request.id, WorkInfo.State.ENQUEUED), request = request),
        )
        val legacyId = Uuid.random()
        val persistence = InMemoryIosWorkPersistence().apply {
            saveRecord(assertNotNull(IosWorkRecordCodec.decode(encoded)))
            saveRecord(assertNotNull(IosWorkRecordCodec.decode("$legacyId|ENQUEUED|0|")))
        }
        val reopened = IosWorkStateStore(persistence, backgroundScope)
        reopened.restoreFromPersistence()
        assertNull(reopened.snapshot()[request.id]?.nextRunAtEpochMillis)

        reopened.initWork(request.id, request.tags, request = request, nextRunAtEpochMillis = 700L)
        reopened.initWork(legacyId, emptySet(), nextRunAtEpochMillis = 700L)
        runCurrent()

        assertEquals(700L, persistence.loadRecords().single { it.info.id == request.id }.nextRunAtEpochMillis)
        val legacy = persistence.loadRecords().single { it.info.id == legacyId }
        assertNull(legacy.request)
        assertNull(legacy.nextRunAtEpochMillis)
    }

    @Test
    fun retryDueIsPersistedBeforeReturningAndClearedOnNextAttempt() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").build()
        val original = IosWorkStateStore(persistence)
        original.initWork(request.id, request.tags, request = request, nextRunAtEpochMillis = 100L)
        assertTrue(original.transitionToRunning(request.id, periodic = false))
        assertTrue(original.incrementAttempt(request.id))
        assertTrue(persistence.loadRecords().isEmpty())
        assertTrue(original.scheduleRetry(request.id, nextRunAtEpochMillis = 900L))

        val saved = persistence.loadRecords().single()
        assertEquals(900L, saved.nextRunAtEpochMillis)
        assertEquals(1, saved.info.runAttemptCount)
        assertTrue(original.incrementAttempt(request.id))
        assertNull(original.snapshot()[request.id]?.nextRunAtEpochMillis)
        val reopenedPersistence = InMemoryIosWorkPersistence().apply {
            saveRecord(assertNotNull(IosWorkRecordCodec.decode(IosWorkRecordCodec.encode(saved))))
        }
        val reopened = IosWorkStateStore(reopenedPersistence, backgroundScope)
        reopened.restoreFromPersistence()
        assertEquals(WorkInfo.State.ENQUEUED, reopened.snapshot()[request.id]?.info?.state)
        assertEquals(900L, reopened.snapshot()[request.id]?.nextRunAtEpochMillis)
        assertTrue(reopened.transitionToRunning(request.id, periodic = false))
        assertTrue(reopened.incrementAttempt(request.id))
        runCurrent()
        val retried = reopenedPersistence.loadRecords().single()
        assertEquals(2, retried.info.runAttemptCount)
        assertNull(retried.nextRunAtEpochMillis)
        assertTrue(reopened.cancelWork(request.id))
        assertFalse(reopened.scheduleRetry(request.id, nextRunAtEpochMillis = 1_000L))
        runCurrent()
        assertTrue(reopenedPersistence.loadRecords().isEmpty())
    }

    @Test
    fun stateStoreRestoresInterruptedRequestWithoutLosingUniqueIdentity() = runTest {
        val persistence = InMemoryIosWorkPersistence()
        val request = OneTimeWorkRequestBuilder<CoroutineWorker>("BackupWorker").build()
        val unique = UniqueWork("recoverable", WorkKind.ONE_TIME)
        val original = IosWorkStateStore(persistence, backgroundScope)
        original.initWork(request.id, request.tags, unique, request)
        assertTrue(original.transitionToRunning(request.id, periodic = false))
        assertTrue(original.incrementAttempt(request.id))
        runCurrent()

        val saved = persistence.loadRecords().single()
        val reloaded = assertNotNull(IosWorkRecordCodec.decode(IosWorkRecordCodec.encode(saved)))
        val reopenedPersistence = InMemoryIosWorkPersistence().apply { saveRecord(reloaded) }
        val reopened = IosWorkStateStore(reopenedPersistence, backgroundScope)
        reopened.restoreFromPersistence()

        val restored = assertNotNull(reopened.snapshot()[request.id])
        assertEquals(WorkInfo.State.ENQUEUED, restored.info.state)
        assertEquals(1, restored.info.runAttemptCount)
        assertEquals(unique, restored.uniqueWork)
        assertEquals(request.workerClass, assertIs<OneTimeWorkRequest>(restored.request).workerClass)
        assertTrue(reopened.transitionToRunning(request.id, periodic = false))
        assertTrue(reopened.finishWork(request.id, succeeded = true, WorkData.EMPTY))
        runCurrent()
        assertTrue(reopenedPersistence.loadRecords().isEmpty())
    }

    @Test
    fun restoredSameIdAcceptsEquivalentRequestButRejectsConflictingPayload() = runTest {
        val id = Uuid.random()
        val unique = UniqueWork("nightly", WorkKind.PERIODIC)
        fun request(requestId: Uuid, values: LongArray, tags: List<String>): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<CoroutineWorker>("BackupWorker", 4.hours).apply {
                setId(requestId)
                setConstraints(Constraints { setRequiresCharging(true) })
                setInputData(WorkData("values" to values))
                tags.forEach { addTag(it) }
            }.build()

        val original = request(id, longArrayOf(1, 2), listOf("first", "second"))
        val sibling = request(Uuid.random(), longArrayOf(3), emptyList())
        val persistence = InMemoryIosWorkPersistence()
        listOf(original, sibling).forEach {
            val record = StoredWork(WorkInfo(it.id, WorkInfo.State.ENQUEUED), unique, it)
            persistence.saveRecord(assertNotNull(IosWorkRecordCodec.decode(IosWorkRecordCodec.encode(record))))
        }
        val reopened = IosWorkStateStore(persistence)
        reopened.restoreFromPersistence()
        val before = reopened.snapshot()
        val restored = assertNotNull(before[id])

        restored.requireMatchingRequest(request(id, longArrayOf(1, 2), listOf("second", "first")))
        assertFailsWith<WorkEnqueueException> {
            restored.requireMatchingRequest(request(id, longArrayOf(1, 9), listOf("first", "second")))
        }
        assertEquals(before, reopened.snapshot())
        assertEquals(before.values.toList(), persistence.loadRecords())
    }

    private fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CoroutineWorker>("BackupWorker", 4.hours, 15.minutes).apply {
            setInitialDelay(20.minutes)
            setQuickRefresh(true)
            setInputData(WorkData("path" to "backups"))
            addTag("periodic")
        }.build()

    private fun assertDataEquals(expected: WorkData, actual: WorkData) {
        val expectedMap = expected.keyValueMap()
        val actualMap = actual.keyValueMap()
        assertEquals(expectedMap.keys, actualMap.keys)
        expectedMap.forEach { (key, value) ->
            val restored = actualMap[key]
            when (value) {
                is BooleanArray -> assertContentEquals(value, assertIs<BooleanArray>(restored), key)
                is ByteArray -> assertContentEquals(value, assertIs<ByteArray>(restored), key)
                is ShortArray -> assertContentEquals(value, assertIs<ShortArray>(restored), key)
                is IntArray -> assertContentEquals(value, assertIs<IntArray>(restored), key)
                is LongArray -> assertContentEquals(value, assertIs<LongArray>(restored), key)
                is FloatArray -> assertContentEquals(value, assertIs<FloatArray>(restored), key)
                is DoubleArray -> assertContentEquals(value, assertIs<DoubleArray>(restored), key)
                is Array<*> -> assertEquals(value.toList(), assertIs<Array<*>>(restored).toList(), key)
                else -> assertEquals(value, restored, key)
            }
        }
    }
}
