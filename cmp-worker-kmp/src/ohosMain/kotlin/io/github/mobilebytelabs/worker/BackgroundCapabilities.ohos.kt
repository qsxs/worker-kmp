package io.github.mobilebytelabs.worker

actual fun platformBackgroundCapabilities() = BackgroundCapabilities(
    // 这些平台能力由使用 OhosWorkManagerConfiguration 的后端提供。
    supportsPersistence = true,
    supportsOsScheduling = true,
)
