package io.github.mobilebytelabs.worker

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForegroundApi::class)
class ForegroundInfoProviderTest {

    @Test
    fun defaultProvider_failsExplicitlyWithoutRunningWork() = runTest {
        var workCalls = 0
        // 仅覆写 doWork 的既有子类继续保持源码兼容。
        val worker = object : ForegroundWorker(TestWorkerContext()) {
            override suspend fun doWork(): WorkResult {
                workCalls++
                return WorkResult.success()
            }
        }

        val failure = assertFailsWith<ForegroundNotSupportedException> { worker.getForegroundInfo() }

        assertTrue(failure.reason.contains("getForegroundInfo()"))
        assertEquals(0, workCalls)
        assertIs<WorkResult.Success>(worker.doWork())
        assertEquals(1, workCalls)
    }

    @Test
    fun overriddenProvider_returnsDescriptorWithoutRunningWork() = runTest {
        val expected = ForegroundInfo(71, "初始通知", "等待执行", serviceType = ForegroundServiceType.DATA_SYNC)
        var providerCalls = 0
        var workCalls = 0
        val worker = object : ForegroundWorker(TestWorkerContext()) {
            override suspend fun getForegroundInfo(): ForegroundInfo {
                providerCalls++
                return expected
            }

            override suspend fun doWork(): WorkResult {
                workCalls++
                return WorkResult.success()
            }
        }

        assertSame(expected, worker.getForegroundInfo())
        assertSame(expected, worker.getForegroundInfo())
        assertEquals(2, providerCalls)
        assertEquals(0, workCalls)
    }
}
