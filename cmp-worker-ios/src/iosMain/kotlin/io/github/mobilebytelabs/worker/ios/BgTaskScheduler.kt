@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.NetworkType
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateByAddingTimeInterval
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlin.time.Clock

// Must be called before applicationDidFinishLaunching returns.
// Safe to call with empty identifier (no-op guard).
internal fun registerBgProcessingTask(identifier: String, scope: CoroutineScope, onTask: suspend () -> Boolean) {
    if (identifier.isEmpty()) return
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier = identifier,
        usingQueue = null,
    ) { bgTask ->
        val task = bgTask as? BGProcessingTask
        if (task == null) {
            bgTask?.setTaskCompletedWithSuccess(false)
            return@registerForTaskWithIdentifier
        }
        launchBgTask(
            scope,
            installExpirationHandler = { task.expirationHandler = it },
            complete = { task.setTaskCompletedWithSuccess(it) },
        ) {
            onTask()
        }
    }
}

/** 返回系统拒绝信息，工作记录保留供下一次重新申请。 */
internal fun scheduleBgProcessingTask(
    identifier: String,
    requiresNetwork: Boolean,
    requiresCharging: Boolean,
    earliestAtEpochMillis: Long? = null,
): String? {
    if (identifier.isEmpty()) return null
    return runCatching {
        val request = BGProcessingTaskRequest(identifier = identifier)
        request.requiresNetworkConnectivity = requiresNetwork
        request.requiresExternalPower = requiresCharging
        if (earliestAtEpochMillis != null) {
            val seconds =
                iosRemainingDelayMillis(Clock.System.now().toEpochMilliseconds(), earliestAtEpochMillis) / 1000.0
            request.earliestBeginDate = NSDate().dateByAddingTimeInterval(seconds)
        }
        memScoped {
            val error = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            error.value = null
            if (BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) {
                null
            } else {
                error.value?.localizedDescription ?: "BGTaskScheduler rejected the request"
            }
        }
    }.getOrElse { it.message ?: "BGTaskScheduler request failed" }
}

/**
 * 回调到达只表示系统提供执行窗口，逐项检查当前联网和供电快照。
 * 网络接口可在检查后变化；设备空闲、可用空间及低电量条件继续沿用 iOS 的尽力语义。
 */
internal suspend fun iosBackgroundConstraintsSatisfied(constraints: Constraints): Boolean {
    if (constraints.requiredNetworkType != NetworkType.NOT_REQUIRED) {
        val networkReady = memScoped {
            val reference = SCNetworkReachabilityCreateWithName(null, "apple.com") ?: return@memScoped false
            try {
                val flags = alloc<UIntVar>()
                SCNetworkReachabilityGetFlags(reference, flags.ptr) &&
                    flags.value and kSCNetworkReachabilityFlagsReachable != 0u &&
                    flags.value and kSCNetworkReachabilityFlagsConnectionRequired == 0u &&
                    (
                        constraints.requiredNetworkType != NetworkType.UNMETERED ||
                            flags.value and kSCNetworkReachabilityFlagsIsWWAN == 0u
                        )
            } finally {
                CFRelease(reference)
            }
        }
        if (!networkReady) return false
    }
    if (!constraints.requiresCharging) return true
    return withContext(Dispatchers.Main) {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
    }
}

/**
 * Schedules a BGAppRefreshTask wake-up — used by periodic workers that opt-in via
 * `PeriodicWorkRequestBuilder.setQuickRefresh(true)`. App-refresh tasks are short
 * (few seconds) and frequent — ideal for keep-alive polling. Requires the
 * identifier in `Info.plist → BGTaskSchedulerPermittedIdentifiers` AND
 * `UIBackgroundModes` to include `"fetch"`.
 *
 * Added in v3.0.0-alpha04.X (Phase 7 alpha04.X).
 */
internal fun scheduleBgAppRefreshTask(identifier: String, earliestBeginInSeconds: Double) {
    if (identifier.isEmpty()) return
    runCatching {
        val request = BGAppRefreshTaskRequest(identifier = identifier)
        if (earliestBeginInSeconds > 0) {
            request.earliestBeginDate = NSDate().dateByAddingTimeInterval(earliestBeginInSeconds)
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }
}
