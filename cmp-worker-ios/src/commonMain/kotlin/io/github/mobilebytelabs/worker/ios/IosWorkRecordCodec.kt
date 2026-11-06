package io.github.mobilebytelabs.worker.ios

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.CoroutineWorker
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.OneTimeWorkRequestBuilder
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.PeriodicWorkRequestBuilder
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkInfo
import io.github.mobilebytelabs.worker.WorkProgress
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.StoredWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.UniqueWork
import io.github.mobilebytelabs.worker.ios.IosWorkStateStore.WorkKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** 新记录保留 UUID 前缀，以便与旧记录使用同一条替换和删除路径。 */
internal object IosWorkRecordCodec {
    private const val VERSION = "2"

    fun validateRequest(request: WorkRequest) {
        encodeRequest(request)
    }

    fun validateData(data: WorkData) {
        encodeData(data)
    }

    fun matchesRequest(saved: WorkRequest, incoming: WorkRequest): Boolean {
        if (saved === incoming) return true
        if (saved.id != incoming.id) return false
        return try {
            encodeRequest(saved) == encodeRequest(incoming)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun encode(record: StoredWork): String {
        val info = record.info
        require(record.request == null || record.request.id == info.id) { "Mismatched iOS work request ID" }
        requireMatchingKind(record.request, record.uniqueWork)
        val body = buildJsonObject {
            put("state", info.state.name)
            put("attempt", info.runAttemptCount)
            put("tags", strings(info.tags))
            put(
                "progress",
                buildJsonObject {
                    put("percent", info.progress.progress)
                    put("data", encodeData(info.progress.data))
                },
            )
            put("output", encodeData(info.outputData))
            put(
                "unique",
                record.uniqueWork?.let { unique ->
                    buildJsonObject {
                        put("name", unique.name)
                        put("kind", unique.kind.name)
                    }
                } ?: JsonNull,
            )
            put("request", record.request?.let(::encodeRequest) ?: JsonNull)
            record.nextRunAtEpochMillis?.let { put("nextRunAtEpochMillis", it) }
        }
        return "${info.id}|$VERSION|$body"
    }

    fun decode(encoded: String): StoredWork? {
        val parts = encoded.split('|', limit = 3)
        if (parts.size != 3) return null
        return try {
            val id = Uuid.parse(parts[0])
            if (parts[1] == VERSION) {
                decodeRecord(id, Json.parseToJsonElement(parts[2]).jsonObject)
            } else {
                decodeLegacy(id, parts[1], parts[2])
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeLegacy(id: Uuid, state: String, payload: String): StoredWork? {
        val fields = payload.split('|')
        if (fields.size < 2) return null
        val tags = if (fields[1].isEmpty()) emptySet() else fields[1].split(',').toSet()
        val info = WorkInfo(
            id = id,
            state = WorkInfo.State.valueOf(state),
            runAttemptCount = fields[0].toIntOrNull() ?: 0,
            tags = tags,
        )
        // 旧格式没有请求或唯一工作身份，只恢复可观察的状态。
        return StoredWork(info)
    }

    private fun decodeRecord(id: Uuid, body: JsonObject): StoredWork {
        val progress = body.getValue("progress").jsonObject
        val info = WorkInfo(
            id = id,
            state = WorkInfo.State.valueOf(body.value("state")),
            progress = WorkProgress(
                progress.value("percent").toInt(),
                decodeData(progress.getValue("data").jsonObject),
            ),
            outputData = decodeData(body.getValue("output").jsonObject),
            tags = readStrings(body.getValue("tags")),
            runAttemptCount = body.value("attempt").toInt(),
        )
        val unique = body.optional("unique")?.jsonObject?.let {
            UniqueWork(it.value("name"), WorkKind.valueOf(it.value("kind")))
        }
        val request = body.optional("request")?.jsonObject?.let { decodeRequest(id, it) }
        // v2 的早期记录没有截止时间，仍可恢复原请求及状态。
        val dueAt = body["nextRunAtEpochMillis"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toLong()
        requireMatchingKind(request, unique)
        return StoredWork(info, unique, request, nextRunAtEpochMillis = dueAt)
    }

    private fun requireMatchingKind(request: WorkRequest?, unique: UniqueWork?) {
        if (request != null && unique != null) {
            val expected = if (request is PeriodicWorkRequest) WorkKind.PERIODIC else WorkKind.ONE_TIME
            require(unique.kind == expected) { "Mismatched iOS unique work kind" }
        }
    }

    private fun encodeRequest(request: WorkRequest): JsonObject = buildJsonObject {
        put("worker", request.workerClass)
        put("input", encodeData(request.inputData))
        put("constraints", encodeConstraints(request.constraints))
        put("retry", encodeRetry(request.retryConfig))
        put("tags", strings(request.tags))
        when (request) {
            is OneTimeWorkRequest -> {
                put("kind", WorkKind.ONE_TIME.name)
                put("delay", request.initialDelay.toIsoString())
                put("expedited", request.expeditedPolicy?.let { JsonPrimitive(it.name) } ?: JsonNull)
            }

            is PeriodicWorkRequest -> {
                require(request.retryConfig == RetryConfig.DEFAULT) { "Unsupported periodic retry configuration" }
                put("kind", WorkKind.PERIODIC.name)
                put("delay", request.initialDelay.toIsoString())
                put("interval", request.repeatInterval.toIsoString())
                put("flex", request.flexTimeInterval.toIsoString())
                put("quickRefresh", request.quickRefresh)
            }
        }
    }

    private fun decodeRequest(id: Uuid, body: JsonObject): WorkRequest {
        val worker = body.value("worker")
        val input = decodeData(body.getValue("input").jsonObject)
        val constraints = decodeConstraints(body.getValue("constraints").jsonObject)
        val retry = decodeRetry(body.getValue("retry").jsonObject)
        val tags = readStrings(body.getValue("tags"))
        val delay = Duration.parseIsoString(body.value("delay"))
        return when (WorkKind.valueOf(body.value("kind"))) {
            WorkKind.ONE_TIME -> OneTimeWorkRequestBuilder<CoroutineWorker>(worker).apply {
                setId(id)
                setInputData(input)
                setConstraints(constraints)
                setBackoffCriteria(retry.backoffPolicy, retry)
                setInitialDelay(delay)
                tags.forEach { addTag(it) }
                body.optional("expedited")?.let { setExpedited(OutOfQuotaPolicy.valueOf(it.jsonPrimitive.content)) }
            }.build()

            WorkKind.PERIODIC -> {
                // 周期请求的公开构造入口只支持默认重试策略。
                require(retry == RetryConfig.DEFAULT) { "Unsupported periodic retry configuration" }
                PeriodicWorkRequestBuilder<CoroutineWorker>(
                    worker,
                    Duration.parseIsoString(body.value("interval")),
                    Duration.parseIsoString(body.value("flex")),
                ).apply {
                    setId(id)
                    setInputData(input)
                    setConstraints(constraints)
                    setInitialDelay(delay)
                    setQuickRefresh(body.value("quickRefresh").toBooleanStrict())
                    tags.forEach { addTag(it) }
                }.build()
            }
        }
    }

    private fun encodeConstraints(constraints: Constraints): JsonObject = buildJsonObject {
        put("network", constraints.requiredNetworkType.name)
        put("charging", constraints.requiresCharging)
        put("idle", constraints.requiresDeviceIdle)
        put("battery", constraints.requiresBatteryNotLow)
        put("storage", constraints.requiresStorageNotLow)
        put(
            "uris",
            JsonArray(
                constraints.contentUriTriggers.map { trigger ->
                    buildJsonObject {
                        put("uri", trigger.uriString)
                        put("descendants", trigger.triggerForDescendants)
                    }
                },
            ),
        )
    }

    private fun decodeConstraints(body: JsonObject): Constraints = Constraints {
        setRequiredNetworkType(NetworkType.valueOf(body.value("network")))
        setRequiresCharging(body.value("charging").toBooleanStrict())
        setRequiresDeviceIdle(body.value("idle").toBooleanStrict())
        setRequiresBatteryNotLow(body.value("battery").toBooleanStrict())
        setRequiresStorageNotLow(body.value("storage").toBooleanStrict())
        body.getValue("uris").jsonArray.forEach { element ->
            val trigger = element.jsonObject
            addContentUriTrigger(trigger.value("uri"), trigger.value("descendants").toBooleanStrict())
        }
    }

    private fun encodeRetry(retry: RetryConfig): JsonObject = buildJsonObject {
        put("attempts", retry.maxAttempts)
        put("initial", retry.initialDelay.toIsoString())
        put("max", retry.maxDelay.toIsoString())
        put("policy", retry.backoffPolicy.name)
        put("multiplierBits", retry.multiplier.toRawBits())
    }

    private fun decodeRetry(body: JsonObject): RetryConfig = RetryConfig(
        maxAttempts = body.value("attempts").toInt(),
        initialDelay = Duration.parseIsoString(body.value("initial")),
        maxDelay = Duration.parseIsoString(body.value("max")),
        backoffPolicy = BackoffPolicy.valueOf(body.value("policy")),
        multiplier = Double.fromBits(body.value("multiplierBits").toLong()),
    )

    private fun encodeData(data: WorkData): JsonObject = buildJsonObject {
        data.keyValueMap().forEach { (key, value) ->
            put(key, encodeValue(value))
        }
    }

    private fun decodeData(body: JsonObject): WorkData =
        WorkData(*body.map { (key, value) -> key to decodeValue(value) }.toTypedArray())

    private fun encodeValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull

        is String -> typed("string", JsonPrimitive(value))

        is Boolean -> typed("boolean", JsonPrimitive(value))

        is Byte -> typed("byte", JsonPrimitive(value))

        is Short -> typed("short", JsonPrimitive(value))

        is Int -> typed("int", JsonPrimitive(value))

        is Long -> typed("long", JsonPrimitive(value))

        is Float -> typed("float", JsonPrimitive(value.toRawBits()))

        is Double -> typed("double", JsonPrimitive(value.toRawBits()))

        is BooleanArray -> typed("booleans", JsonArray(value.map(::JsonPrimitive)))

        is ByteArray -> typed("bytes", JsonArray(value.map(::JsonPrimitive)))

        is ShortArray -> typed("shorts", JsonArray(value.map(::JsonPrimitive)))

        is IntArray -> typed("ints", JsonArray(value.map(::JsonPrimitive)))

        is LongArray -> typed("longs", JsonArray(value.map(::JsonPrimitive)))

        is FloatArray -> typed("floats", JsonArray(value.map { JsonPrimitive(it.toRawBits()) }))

        is DoubleArray -> typed("doubles", JsonArray(value.map { JsonPrimitive(it.toRawBits()) }))

        is Array<*> -> {
            require(value.all { it == null || it is String }) { "Unsupported iOS WorkData array" }
            typed("strings", JsonArray(value.map { JsonPrimitive(it as String?) }))
        }

        else -> throw IllegalArgumentException("Unsupported iOS WorkData type: ${value::class.simpleName}")
    }

    private fun decodeValue(element: JsonElement): Any? {
        if (element == JsonNull) return null
        val body = element.jsonObject
        val value = body.getValue("value")
        return when (body.value("type")) {
            "string" -> value.jsonPrimitive.content
            "boolean" -> value.jsonPrimitive.content.toBooleanStrict()
            "byte" -> value.jsonPrimitive.content.toByte()
            "short" -> value.jsonPrimitive.content.toShort()
            "int" -> value.jsonPrimitive.content.toInt()
            "long" -> value.jsonPrimitive.content.toLong()
            "float" -> Float.fromBits(value.jsonPrimitive.content.toInt())
            "double" -> Double.fromBits(value.jsonPrimitive.content.toLong())
            "booleans" -> value.jsonArray.map { it.jsonPrimitive.content.toBooleanStrict() }.toBooleanArray()
            "bytes" -> value.jsonArray.map { it.jsonPrimitive.content.toByte() }.toByteArray()
            "shorts" -> value.jsonArray.map { it.jsonPrimitive.content.toShort() }.toShortArray()
            "ints" -> value.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
            "longs" -> value.jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray()
            "floats" -> value.jsonArray.map { Float.fromBits(it.jsonPrimitive.content.toInt()) }.toFloatArray()
            "doubles" -> value.jsonArray.map { Double.fromBits(it.jsonPrimitive.content.toLong()) }.toDoubleArray()
            "strings" -> value.jsonArray.map { if (it == JsonNull) null else it.jsonPrimitive.content }.toTypedArray()
            else -> error("Unknown iOS WorkData type")
        }
    }

    private fun typed(type: String, value: JsonElement): JsonObject = buildJsonObject {
        put("type", type)
        put("value", value)
    }

    private fun strings(values: Set<String>): JsonArray = JsonArray(values.sorted().map(::JsonPrimitive))

    private fun readStrings(element: JsonElement): Set<String> = element.jsonArray.map {
        it.jsonPrimitive.content
    }.toSet()

    private fun JsonObject.optional(key: String): JsonElement? = getValue(key).takeUnless { it == JsonNull }

    private fun JsonObject.value(key: String): String = getValue(key).jsonPrimitive.content
}
