package io.github.mobilebytelabs.worker.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.ExistingPeriodicWorkPolicy
import io.github.mobilebytelabs.worker.ExistingWorkPolicy
import io.github.mobilebytelabs.worker.WorkResult
import io.github.mobilebytelabs.worker.WorkerContext
import io.github.mobilebytelabs.worker.oneTimeWorkRequest
import io.github.mobilebytelabs.worker.periodicWorkRequest
import io.github.mobilebytelabs.worker.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class SimpleSuccessWorker(context: WorkerContext) : CoroutineWorker(context) {
    override suspend fun doWork() = WorkResult.success()
}

/** 使用官方内存 WorkDatabase 与 TestDriver；记录的对象只观察真实执行，不模拟调度状态。 */
internal class NativeIdentityFixture(private val onExecution: (WorkerContext) -> Unit = {}) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val executions = mutableListOf<WorkerContext>()
    val nativeWorkers = mutableListOf<KmpAndroidWorker>()
    private val previousFactory = KmpWorkerFactory.instance
    val native: WorkManager
    val manager: AndroidWorkManager
    val driver: androidx.work.testing.TestDriver

    init {
        val delegate = KmpWorkerFactory(object : KmpAndroidWorkerFactory {
            override fun create(workerClass: String, context: WorkerContext): CoroutineWorker =
                object : CoroutineWorker(context) {
                    override suspend fun doWork(): WorkResult {
                        executions += context
                        onExecution(context)
                        return WorkResult.success(workDataOf("executed_id" to id.toString()))
                    }
                }
        })
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = delegate.createWorker(appContext, workerClassName, workerParameters)
                .also { if (it is KmpAndroidWorker) nativeWorkers += it }
        }
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        native = WorkManager.getInstance(context)
        manager = AndroidWorkManager(context)
        driver = assertNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    }

    fun runFirst(id: Uuid) {
        driver.setAllConstraintsMet(id.nativeId())
        driver.setInitialDelayMet(id.nativeId())
    }

    fun runPeriod(id: Uuid) {
        driver.setAllConstraintsMet(id.nativeId())
        driver.setPeriodDelayMet(id.nativeId())
    }

    fun assertExecution(id: Uuid, payload: String, tag: String) {
        val execution = executions.last()
        assertEquals(id, execution.id)
        assertEquals(id.nativeId(), nativeWorkers.last().id)
        assertEquals(payload, execution.inputData.getString("payload"))
        assertEquals(setOf(tag, KmpAndroidWorker::class.java.name), execution.tags)
        assertTrue(execution.tags.none { it.startsWith(TAG_KMP_ID_PREFIX) })
    }

    fun close() {
        try {
            native.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        } finally {
            KmpWorkerFactory.instance = previousFactory
        }
    }
}

internal fun Uuid.nativeId(): UUID = UUID.fromString(toString())

internal fun delayedOneTime(payload: String, tag: String) = oneTimeWorkRequest<SimpleSuccessWorker> {
    setInputData(workDataOf("payload" to payload))
    setConstraints(Constraints { setRequiresCharging(true) })
    setInitialDelay(1.hours)
    addTag(tag)
}

internal fun delayedPeriodic(payload: String, tag: String) = periodicWorkRequest<SimpleSuccessWorker>(15.minutes) {
    setInputData(workDataOf("payload" to payload))
    setConstraints(Constraints { setRequiresCharging(true) })
    setInitialDelay(1.hours)
    addTag(tag)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidWorkManagerIntegrationTest {
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
    fun enqueue_drivesToSucceeded_underTestDriver() = runTest {
        val request = delayedOneTime("one-time", "one-time-tag")
        assertEquals(request.id, fixture.manager.enqueue(request))
        assertEquals(request.id.nativeId(), fixture.native.getWorkInfoById(request.id.nativeId()).get()?.id)

        fixture.runFirst(request.id)

        val info = assertNotNull(fixture.native.getWorkInfoById(request.id.nativeId()).get())
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(request.id.toString(), info.outputData.getString("executed_id"))
        fixture.assertExecution(request.id, "one-time", "one-time-tag")
        assertEquals(1, fixture.executions.size)
        assertEquals(request.id, fixture.manager.getWorkInfoById(request.id)?.id)
    }

    @Test
    fun periodicUpdate_replacesInputAndTagsButKeepsNativeExecutionIdentity() = runTest {
        val first = delayedPeriodic("A", "tag-A")
        fixture.manager.enqueueUniquePeriodicWork("periodic-update", ExistingPeriodicWorkPolicy.KEEP, first)
        assertEquals(first.id.nativeId(), fixture.native.getWorkInfoById(first.id.nativeId()).get()?.id)
        fixture.runFirst(first.id)
        fixture.assertExecution(first.id, "A", "tag-A")
        assertEquals(1, fixture.executions.size)
        assertEquals(WorkInfo.State.ENQUEUED, fixture.native.getWorkInfoById(first.id.nativeId()).get()?.state)

        val replacements = listOf(delayedPeriodic("B", "tag-B"), delayedPeriodic("C", "tag-C"))
        assertEquals(3, (listOf(first.id) + replacements.map { it.id }).toSet().size)
        replacements.forEachIndexed { index, replacement ->
            val label = if (index == 0) "B" else "C"
            fixture.manager.enqueueUniquePeriodicWork(
                "periodic-update",
                ExistingPeriodicWorkPolicy.UPDATE,
                replacement,
            )
            val native = assertNotNull(fixture.native.getWorkInfoById(first.id.nativeId()).get())
            assertEquals(first.id.nativeId(), native.id)
            assertEquals(index + 1, native.generation)
            assertEquals(WorkInfo.State.ENQUEUED, native.state)
            assertEquals(
                setOf(replacement.id.toTag(), "tag-$label", KmpAndroidWorker::class.java.name),
                native.tags,
            )
            assertNull(fixture.native.getWorkInfoById(replacement.id.nativeId()).get())
            assertNull(fixture.manager.getWorkInfoById(replacement.id))
            assertEquals(first.id, fixture.manager.getWorkInfoById(first.id)?.id)
            assertEquals(listOf(first.id), fixture.manager.getWorkInfosByTag("tag-$label").first().map { it.id })
            assertEquals(
                listOf(first.id),
                fixture.manager.getWorkInfosForUniqueWorkFlow("periodic-update").first().map { it.id },
            )

            fixture.runPeriod(first.id)
            fixture.assertExecution(first.id, label, "tag-$label")
            assertEquals(index + 2, fixture.executions.size)
            assertEquals(WorkInfo.State.ENQUEUED, fixture.native.getWorkInfoById(first.id.nativeId()).get()?.state)
        }
        assertTrue(fixture.manager.getWorkInfosByTag("tag-A").first().isEmpty())
        assertTrue(fixture.manager.getWorkInfosByTag("tag-B").first().isEmpty())
        replacements.forEach { assertNull(fixture.native.getWorkInfoById(it.id.nativeId()).get()) }

        fixture.manager.cancelWorkById(first.id)
        assertEquals(WorkInfo.State.CANCELLED, fixture.native.getWorkInfoById(first.id.nativeId()).get()?.state)
        assertEquals(
            io.github.mobilebytelabs.worker.WorkInfo.State.CANCELLED,
            fixture.manager.getWorkInfosByTag("tag-C").first().single().state,
        )
    }

    @Test
    fun uniqueOneTimeKeep_keepsAAndDoesNotInsertB() = runTest {
        val first = delayedOneTime("A", "keep-A")
        val ignored = delayedOneTime("B", "keep-B")
        fixture.manager.enqueueUniqueWork("one-time-keep", ExistingWorkPolicy.KEEP, first)
        fixture.manager.enqueueUniqueWork("one-time-keep", ExistingWorkPolicy.KEEP, ignored)
        assertNull(fixture.native.getWorkInfoById(ignored.id.nativeId()).get())
        assertNull(fixture.manager.getWorkInfoById(ignored.id))
        assertEquals(
            listOf(first.id),
            fixture.manager.getWorkInfosForUniqueWorkFlow("one-time-keep").first().map {
                it.id
            },
        )
        assertTrue(fixture.manager.getWorkInfosByTag("keep-B").first().isEmpty())
        fixture.runFirst(first.id)
        fixture.assertExecution(first.id, "A", "keep-A")
        assertEquals(WorkInfo.State.SUCCEEDED, fixture.native.getWorkInfoById(first.id.nativeId()).get()?.state)
        assertEquals(1, fixture.executions.size)
    }

    @Test
    fun uniquePeriodicKeep_keepsAAndDoesNotInsertB() = runTest {
        val first = delayedPeriodic("A", "keep-A")
        val ignored = delayedPeriodic("B", "keep-B")
        fixture.manager.enqueueUniquePeriodicWork("periodic-keep", ExistingPeriodicWorkPolicy.KEEP, first)
        fixture.manager.enqueueUniquePeriodicWork("periodic-keep", ExistingPeriodicWorkPolicy.KEEP, ignored)
        assertNull(fixture.native.getWorkInfoById(ignored.id.nativeId()).get())
        assertNull(fixture.manager.getWorkInfoById(ignored.id))
        assertEquals(
            listOf(first.id),
            fixture.manager.getWorkInfosForUniqueWorkFlow("periodic-keep").first().map {
                it.id
            },
        )
        assertTrue(fixture.manager.getWorkInfosByTag("keep-B").first().isEmpty())
        fixture.runFirst(first.id)
        fixture.assertExecution(first.id, "A", "keep-A")
        assertEquals(WorkInfo.State.ENQUEUED, fixture.native.getWorkInfoById(first.id.nativeId()).get()?.state)
        assertEquals(1, fixture.executions.size)
    }
}
