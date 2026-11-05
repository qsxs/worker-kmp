package io.github.mobilebytelabs.worker.android

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import io.github.mobilebytelabs.worker.ExperimentalForegroundApi
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

    private fun activeBridgeWorkers(): Map<Uuid, KmpAndroidWorker> {
        val field = AndroidForegroundBridge::class.java.getDeclaredField("activeWorkers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(AndroidForegroundBridge) as Map<Uuid, KmpAndroidWorker>
    }
}
