package io.github.mobilebytelabs.worker.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

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
}
