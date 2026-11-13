package io.github.mobilebytelabs.worker.ohos.internal.storage

import io.github.mobilebytelabs.worker.BackoffPolicy
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.NetworkType
import io.github.mobilebytelabs.worker.OneTimeWorkRequest
import io.github.mobilebytelabs.worker.OutOfQuotaPolicy
import io.github.mobilebytelabs.worker.PeriodicWorkRequest
import io.github.mobilebytelabs.worker.RetryConfig
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.WorkRequest
import io.github.mobilebytelabs.worker.ohos.internal.initialDelay
import okio.Buffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 工作库载荷以版本和种类字节开头。请求先写通用字段，再写请求种类独有的字段。
 * 数字使用大端固定宽度，字符串和集合带长度，固定标签与枚举声明顺序无关。
 */
internal object DurableWorkPayloadCodec {
    private const val VERSION = 1
    private const val ONE_TIME = 1
    private const val PERIODIC = 2
    private const val RESULT = 3

    private const val NULL = 0
    private const val BOOLEAN = 1
    private const val BYTE = 2
    private const val INT = 3
    private const val LONG = 4
    private const val FLOAT = 5
    private const val DOUBLE = 6
    private const val STRING = 7
    private const val BOOLEAN_ARRAY = 8
    private const val BYTE_ARRAY = 9
    private const val INT_ARRAY = 10
    private const val LONG_ARRAY = 11
    private const val FLOAT_ARRAY = 12
    private const val DOUBLE_ARRAY = 13
    private const val STRING_ARRAY = 14

    fun encodeRequest(request: WorkRequest): ByteArray = Buffer()
        .apply {
            writeByte(VERSION)
            writeByte(
                when (request) {
                    is OneTimeWorkRequest -> ONE_TIME
                    is PeriodicWorkRequest -> PERIODIC
                },
            )
            write(request.id.toByteArray())
            writeString(request.workerClass)
            // 其他约束在入队时被拒绝；工作库只保存鸿蒙后端实际实现的约束。
            writeNetwork(request.constraints.requiredNetworkType)
            val tags = request.tags
            writeInt(tags.size)
            tags.forEach { writeString(it) }
            writeDuration(request.initialDelay())
            writeRetry(request.retryConfig)
            writeData(request.inputData)
            when (request) {
                is OneTimeWorkRequest -> writeExpeditedPolicy(request.expeditedPolicy)

                is PeriodicWorkRequest -> {
                    writeDuration(request.repeatInterval)
                    writeDuration(request.flexTimeInterval)
                    writeFlag(request.quickRefresh)
                }
            }
        }.readByteArray()

    fun decodeRequest(bytes: ByteArray): WorkRequest = Buffer().write(bytes).run {
        val kind = readKind()
        require(kind == ONE_TIME || kind == PERIODIC) { "Unknown durable work request kind: $kind" }
        val id = Uuid.fromByteArray(readByteArray(16))
        val workerClass = readString()
        val constraints = Constraints { setRequiredNetworkType(readNetwork()) }
        val tags = mutableSetOf<String>()
        repeat(readCount(Int.SIZE_BYTES)) {
            require(tags.add(readString())) { "Duplicate durable work tag" }
        }
        val delay = readDuration()
        val retry = readRetry()
        val data = readData()
        val request =
            if (kind == ONE_TIME) {
                OneTimeWorkRequest(
                    id = id,
                    workerClass = workerClass,
                    inputData = data,
                    constraints = constraints,
                    retryConfig = retry,
                    tags = tags,
                    initialDelay = delay,
                    expeditedPolicy = readExpeditedPolicy(),
                )
            } else {
                PeriodicWorkRequest(
                    id = id,
                    workerClass = workerClass,
                    inputData = data,
                    constraints = constraints,
                    retryConfig = retry,
                    tags = tags,
                    repeatInterval = readDuration(),
                    flexTimeInterval = readDuration(),
                    initialDelay = delay,
                    quickRefresh = readFlag(),
                )
            }
        require(exhausted()) { "Trailing bytes in durable work request" }
        request
    }

    fun encodeResult(result: WorkData): ByteArray = Buffer()
        .apply {
            writeByte(VERSION)
            writeByte(RESULT)
            writeData(result)
        }.readByteArray()

    fun decodeResult(bytes: ByteArray): WorkData = Buffer().write(bytes).run {
        require(readKind() == RESULT) { "Expected durable work result payload" }
        val result = readData()
        require(exhausted()) { "Trailing bytes in durable work result" }
        result
    }

    private fun Buffer.readKind(): Int {
        val version = readByte().toInt()
        require(version == VERSION) { "Unsupported durable work payload version: $version" }
        return readByte().toInt()
    }

    private fun Buffer.writeNetwork(network: NetworkType) {
        writeByte(
            when (network) {
                NetworkType.NOT_REQUIRED -> 0
                NetworkType.CONNECTED -> 1
                NetworkType.UNMETERED -> 2
                NetworkType.NOT_ROAMING -> 3
                NetworkType.METERED -> 4
            },
        )
    }

    private fun Buffer.readNetwork(): NetworkType = when (val tag = readByte().toInt()) {
        0 -> NetworkType.NOT_REQUIRED
        1 -> NetworkType.CONNECTED
        2 -> NetworkType.UNMETERED
        3 -> NetworkType.NOT_ROAMING
        4 -> NetworkType.METERED
        else -> throw IllegalArgumentException("Unknown durable work network tag: $tag")
    }

    private fun Buffer.writeExpeditedPolicy(policy: OutOfQuotaPolicy?) {
        writeByte(
            when (policy) {
                null -> 0
                OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST -> 1
                OutOfQuotaPolicy.DROP_WORK_REQUEST -> 2
            },
        )
    }

    private fun Buffer.readExpeditedPolicy(): OutOfQuotaPolicy? = when (val tag = readByte().toInt()) {
        0 -> null
        1 -> OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
        2 -> OutOfQuotaPolicy.DROP_WORK_REQUEST
        else -> throw IllegalArgumentException("Unknown durable work expedited tag: $tag")
    }

    private fun Buffer.writeDuration(duration: Duration) {
        duration.toComponents { seconds, nanos ->
            writeLong(seconds)
            writeInt(nanos)
        }
    }

    private fun Buffer.readDuration(): Duration {
        val seconds = readLong()
        val nanos = readInt()
        require(seconds >= 0 && nanos in 0..999_999_999) { "Invalid durable work duration" }
        val duration = seconds.seconds + nanos.nanoseconds
        require(duration.isFinite() && duration.toComponents { s, ns -> s == seconds && ns == nanos }) {
            "Durable work duration cannot be represented exactly"
        }
        return duration
    }

    // 按码元写入，保留字符串中的未配对代理码元。
    private fun Buffer.writeString(value: String) {
        writeInt(value.length)
        value.forEach { writeShort(it.code) }
    }

    private fun Buffer.readString(): String =
        CharArray(readCount(Short.SIZE_BYTES)) { readShort().toUShort().toInt().toChar() }.concatToString()

    private fun Buffer.readCount(minItemBytes: Int): Int {
        val count = readInt()
        require(count >= 0 && count.toLong() <= size / minItemBytes) { "Invalid durable work payload length: $count" }
        return count
    }

    private fun Buffer.writeFlag(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    private fun Buffer.readFlag(): Boolean = when (val tag = readByte().toInt()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid durable work flag: $tag")
    }

    private fun Buffer.writeData(data: WorkData) {
        val entries = data.keyValueMap()
        writeInt(entries.size)
        entries.forEach { (key, value) ->
            writeString(key)
            writeValue(value)
        }
    }

    private fun Buffer.writeValue(value: Any?) {
        when (value) {
            null -> writeByte(NULL)

            is Boolean -> {
                writeByte(BOOLEAN)
                writeFlag(value)
            }

            is Byte -> writeByte(BYTE).writeByte(value.toInt())

            is Int -> writeByte(INT).writeInt(value)

            is Long -> writeByte(LONG).writeLong(value)

            is Float -> writeByte(FLOAT).writeInt(value.toRawBits())

            is Double -> writeByte(DOUBLE).writeLong(value.toRawBits())

            is String -> {
                writeByte(STRING)
                writeString(value)
            }

            is BooleanArray -> {
                writeByte(BOOLEAN_ARRAY).writeInt(value.size)
                value.forEach { writeFlag(it) }
            }

            is ByteArray -> writeByte(BYTE_ARRAY).writeInt(value.size).write(value)

            is IntArray -> {
                writeByte(INT_ARRAY).writeInt(value.size)
                value.forEach { writeInt(it) }
            }

            is LongArray -> {
                writeByte(LONG_ARRAY).writeInt(value.size)
                value.forEach { writeLong(it) }
            }

            is FloatArray -> {
                writeByte(FLOAT_ARRAY).writeInt(value.size)
                value.forEach { writeInt(it.toRawBits()) }
            }

            is DoubleArray -> {
                writeByte(DOUBLE_ARRAY).writeInt(value.size)
                value.forEach { writeLong(it.toRawBits()) }
            }

            is Array<*> -> {
                writeByte(STRING_ARRAY).writeInt(value.size)
                value.forEach { element ->
                    require(element == null || element is String) {
                        "Only nullable string arrays are supported in durable work data"
                    }
                    writeFlag(element != null)
                    if (element != null) writeString(element)
                }
            }

            else -> throw IllegalArgumentException("Unsupported durable work data value type")
        }
    }

    private fun Buffer.readData(): WorkData {
        val values = linkedMapOf<String, Any?>()
        repeat(readCount(Int.SIZE_BYTES + 1)) {
            val key = readString()
            require(key !in values) { "Duplicate durable work data key" }
            values[key] = when (val tag = readByte().toInt()) {
                NULL -> null
                BOOLEAN -> readFlag()
                BYTE -> readByte()
                INT -> readInt()
                LONG -> readLong()
                FLOAT -> Float.fromBits(readInt())
                DOUBLE -> Double.fromBits(readLong())
                STRING -> readString()
                BOOLEAN_ARRAY -> BooleanArray(readCount(1)) { readFlag() }
                BYTE_ARRAY -> readByteArray(readCount(1).toLong())
                INT_ARRAY -> IntArray(readCount(Int.SIZE_BYTES)) { readInt() }
                LONG_ARRAY -> LongArray(readCount(Long.SIZE_BYTES)) { readLong() }
                FLOAT_ARRAY -> FloatArray(readCount(Int.SIZE_BYTES)) { Float.fromBits(readInt()) }
                DOUBLE_ARRAY -> DoubleArray(readCount(Long.SIZE_BYTES)) { Double.fromBits(readLong()) }
                STRING_ARRAY -> Array<String?>(readCount(1)) { if (readFlag()) readString() else null }
                else -> throw IllegalArgumentException("Unknown durable work data tag: $tag")
            }
        }
        return if (values.isEmpty()) WorkData.EMPTY else WorkData(*values.toList().toTypedArray())
    }

    private fun Buffer.writeRetry(retry: RetryConfig) {
        writeInt(retry.maxAttempts)
        writeDuration(retry.initialDelay)
        writeDuration(retry.maxDelay)
        writeByte(if (retry.backoffPolicy == BackoffPolicy.LINEAR) 0 else 1)
        writeLong(retry.multiplier.toRawBits())
    }

    private fun Buffer.readRetry(): RetryConfig = RetryConfig(
        maxAttempts = readInt(),
        initialDelay = readDuration(),
        maxDelay = readDuration(),
        backoffPolicy = when (val tag = readByte().toInt()) {
            0 -> BackoffPolicy.LINEAR
            1 -> BackoffPolicy.EXPONENTIAL
            else -> throw IllegalArgumentException("Unknown durable work backoff policy: $tag")
        },
        multiplier = Double.fromBits(readLong()),
    )
}
