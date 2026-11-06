@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.WorkInfo
import platform.Foundation.NSUserDefaults
import kotlin.uuid.Uuid

// ── NSUserDefaults implementation ─────────────────────────────────────────────

// Encodes WorkInfo as "<id>|<state>|<runAttemptCount>|<tag1>,<tag2>,..."
internal class NSUserDefaultsWorkPersistence(private val key: String) : IosWorkPersistence {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun save(info: WorkInfo) {
        val items = loadEncoded().toMutableList()
        items.removeAll { it.startsWith("${info.id}|") }
        items.add(encode(info))
        @Suppress("UNCHECKED_CAST")
        defaults.setObject(items as List<*>, forKey = key)
    }

    override suspend fun loadAll(): List<WorkInfo> = loadEncoded().mapNotNull { decode(it) }

    override suspend fun delete(id: Uuid) {
        val items = loadEncoded().filterNot { it.startsWith("$id|") }
        @Suppress("UNCHECKED_CAST")
        defaults.setObject(items as List<*>, forKey = key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadEncoded(): List<String> =
        (defaults.arrayForKey(key) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun encode(info: WorkInfo): String {
        val tags = info.tags.joinToString(",")
        return "${info.id}|${info.state.name}|${info.runAttemptCount}|$tags"
    }

    private fun decode(encoded: String): WorkInfo? {
        val parts = encoded.split("|")
        if (parts.size < 4) return null
        val id = runCatching { Uuid.parse(parts[0]) }.getOrNull() ?: return null
        val state = WorkInfo.State.entries.firstOrNull { it.name == parts[1] } ?: return null
        val attempt = parts[2].toIntOrNull() ?: 0
        val tags = if (parts[3].isEmpty()) emptySet() else parts[3].split(",").toSet()
        return WorkInfo(id = id, state = state, runAttemptCount = attempt, tags = tags)
    }
}

internal fun createIosWorkPersistence(config: IosWorkManagerConfig): IosWorkPersistence =
    if (config.enablePersistence) {
        NSUserDefaultsWorkPersistence(config.persistenceKey)
    } else {
        NoOpIosWorkPersistence
    }
