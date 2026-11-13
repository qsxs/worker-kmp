package io.github.mobilebytelabs.worker.ohos

import kotlin.uuid.Uuid

/** 需要原样传给 WorkScheduler 并在扩展回调中带回的周期登记。 */
public data class OhosPeriodicRegistration(
    val workId: Int,
    val bundleName: String,
    val abilityName: String,
    val requestId: Uuid,
    val generation: Long,
    val repeatIntervalMillis: Int,
    val requiresNetwork: Boolean,
)

/**
 * 宿主的 WorkScheduler 桥接。返回前须向系统回查精确登记；同 ID 的其他登记须报错。
 * 一旦发出了系统操作，必须等它结束再返回或抛错，避免晚到操作越过库持有的登记锁。
 */
public interface OhosWorkScheduler {
    public suspend fun ensure(registration: OhosPeriodicRegistration)

    public suspend fun cancel(registration: OhosPeriodicRegistration)
}
