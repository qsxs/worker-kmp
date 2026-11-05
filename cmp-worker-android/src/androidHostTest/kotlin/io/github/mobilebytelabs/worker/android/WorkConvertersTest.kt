package io.github.mobilebytelabs.worker.android

import androidx.work.Data
import io.github.mobilebytelabs.worker.Constraints
import io.github.mobilebytelabs.worker.WorkData
import io.github.mobilebytelabs.worker.workDataOf
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorkConvertersTest {

    // ── UUID tag helpers ──────────────────────────────────────────────────────

    @Test
    fun toTag_producesKmpIdPrefixedString() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        assertEquals("kmp_id:550e8400-e29b-41d4-a716-446655440000", id.toTag())
    }

    @Test
    fun extractKmpId_parsesTagCorrectly() {
        val id = Uuid.random()
        val tags = setOf(id.toTag(), "user-tag")
        assertEquals(id, tags.extractKmpId())
    }

    @Test
    fun extractKmpId_returnsNullWhenNoKmpTag() {
        assertNull(setOf("user-tag", "another-tag").extractKmpId())
    }

    @Test
    fun workInfoToKmp_usesNativeIdWhenMetadataTagDisagrees() {
        val nativeId = UUID.randomUUID()
        val requestedId = Uuid.random()
        val nativeInfo = androidx.work.WorkInfo(
            id = nativeId,
            state = androidx.work.WorkInfo.State.ENQUEUED,
            tags = setOf(requestedId.toTag(), "updated"),
        )
        val converted = nativeInfo.toKmp()
        assertEquals(Uuid.parse(nativeId.toString()), converted.id)
        assertEquals(setOf("updated"), converted.tags)
    }

    @Test
    fun workInfoToKmp_doesNotDropNativeRecordWithoutKmpTag() {
        val nativeId = UUID.randomUUID()
        val nativeInfo = androidx.work.WorkInfo(
            id = nativeId,
            state = androidx.work.WorkInfo.State.CANCELLED,
            tags = setOf("legacy-backup"),
        )
        val converted = nativeInfo.toKmp()
        assertEquals(Uuid.parse(nativeId.toString()), converted.id)
        assertEquals(io.github.mobilebytelabs.worker.WorkInfo.State.CANCELLED, converted.state)
        assertEquals(setOf("legacy-backup"), converted.tags)
    }

    @Test
    fun userTags_filtersOutKmpIdTags() {
        val id = Uuid.random()
        val tags = setOf(id.toTag(), "sync", "batch")
        val user = tags.userTags()
        assertEquals(setOf("sync", "batch"), user)
    }

    // ── WorkData ↔ Android Data ───────────────────────────────────────────────

    @Test
    fun workData_roundtripPreservesValues() {
        val data = workDataOf(
            "key1" to "hello",
            "key2" to 42,
            "key3" to true,
        )
        val androidData = data.toAndroid()
        val backAgain = androidData.toKmp()
        assertEquals("hello", backAgain.getString("key1"))
        assertEquals(42, backAgain.getInt("key2"))
        assertEquals(true, backAgain.getBoolean("key3"))
    }

    @Test
    fun toKmpInputData_stripsOnlyInputMetadata() {
        val androidData = androidx.work.workDataOf(
            KEY_KMP_CLASS to "com.example.MyWorker",
            KEY_KMP_ID to "550e8400-e29b-41d4-a716-446655440000",
            KEY_KMP_PROGRESS to 17,
            "kmp_business" to "kept",
            "user_key" to "user_value",
        )
        assertNativeValues(androidData, androidData.toKmp())
        val kmpData = androidData.toKmpInputData()
        assertNull(kmpData.getString(KEY_KMP_CLASS))
        assertNull(kmpData.getString(KEY_KMP_ID))
        assertEquals(17, kmpData.getInt(KEY_KMP_PROGRESS))
        assertEquals("kept", kmpData.getString("kmp_business"))
        assertEquals("user_value", kmpData.getString("user_key"))
    }

    @Test
    fun toAndroid_emptyWorkData_producesEmptyAndroidData() {
        val androidData = WorkData.EMPTY.toAndroid()
        assertTrue(androidData.keyValueMap.isEmpty())
    }

    @Test
    fun dataConversion_preservesAllNativeScalarsAndTopLevelNull() {
        val values = mapOf(
            "boolean" to true,
            "byte" to Byte.MIN_VALUE,
            "int" to Int.MIN_VALUE,
            "longMin" to Long.MIN_VALUE,
            "longMax" to Long.MAX_VALUE,
            "float" to 1.25f,
            "double" to -2.5,
            "string" to "业务数据",
            "null" to null,
            KEY_KMP_CLASS to "business-class",
            KEY_KMP_ID to "business-id",
        )
        assertNativeRoundtrip(values)
    }

    @Test
    fun dataConversion_preservesPrimitiveAndBoxedArraysIncludingEveryEmptyType() {
        val arrays = listOf(
            booleanArrayOf(true, false), byteArrayOf(Byte.MIN_VALUE, Byte.MAX_VALUE),
            intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
            floatArrayOf(-1.25f, 2.5f), doubleArrayOf(-1.25, 2.5),
            arrayOf(true, false), arrayOf(Byte.MIN_VALUE, Byte.MAX_VALUE),
            arrayOf(Int.MIN_VALUE, Int.MAX_VALUE), arrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
            arrayOf(-1.25f, 2.5f), arrayOf(-1.25, 2.5), arrayOf("a", "业务"),
            booleanArrayOf(), byteArrayOf(), intArrayOf(), longArrayOf(), floatArrayOf(), doubleArrayOf(),
            emptyArray<Boolean>(), emptyArray<Byte>(), emptyArray<Int>(), emptyArray<Long>(),
            emptyArray<Float>(), emptyArray<Double>(), emptyArray<String>(),
        )
        assertNativeRoundtrip(arrays.mapIndexed { index, value -> "array$index" to value }.toMap())
    }

    @Test
    fun nullableArrays_followNativeMemoryAndSerializedSemantics() {
        val values = mapOf(
            "boolean" to arrayOf<Boolean?>(true, null),
            "byte" to arrayOf<Byte?>(1, null),
            "int" to arrayOf<Int?>(2, null),
            "long" to arrayOf<Long?>(Long.MAX_VALUE, null),
            "float" to arrayOf<Float?>(1.25f, null),
            "double" to arrayOf<Double?>(2.5, null),
            "string" to arrayOf<String?>("业务", null),
            "null" to null,
        )
        assertNativeRoundtrip(values)
        val native = Data.Builder().putAll(values).build()
        val stored = Data.fromByteArray(Data.toByteArrayInternalV1(native))
        assertArrayValues(arrayOf(true, false), stored.keyValueMap["boolean"])
        assertArrayValues(arrayOf<Byte>(1, 0), stored.keyValueMap["byte"])
        assertArrayValues(arrayOf(2, 0), stored.keyValueMap["int"])
        assertArrayValues(arrayOf(Long.MAX_VALUE, 0L), stored.keyValueMap["long"])
        assertArrayValues(arrayOf(1.25f, 0f), stored.keyValueMap["float"])
        assertArrayValues(arrayOf(2.5, 0.0), stored.keyValueMap["double"])
        assertArrayValues(arrayOf("业务", null), stored.keyValueMap["string"])
        assertTrue(stored.keyValueMap.containsKey("null"))
        assertNull(stored.keyValueMap["null"])
    }

    @Test
    fun toAndroid_rejectsShortCharAndUnknownTypesLikeNativeBuilder() {
        val unsupported = listOf(
            1.toShort(), 'x', Any(), shortArrayOf(1), charArrayOf('x'), shortArrayOf(), charArrayOf(),
            arrayOf<Short>(1), arrayOf('x'), emptyArray<Short>(), emptyArray<Char>(), emptyArray<Any>(),
        )
        unsupported.forEach { value ->
            assertFailsWith<IllegalArgumentException> { Data.Builder().putAll(mapOf("bad" to value)).build() }
            assertFailsWith<IllegalArgumentException> { workDataOf("bad" to value).toAndroid() }
        }
    }

    @Test
    fun workInfoProgress_removesOnlyReservedPercentAndPreservesOutput() {
        val payload = Data.Builder().putAll(
            mapOf(
                "INDEX" to 2, "TOTAL" to 8, "MESSAGE" to "备份中",
                "LAST_RESULT_CODE" to 0, "LAST_RESULT_MESSAGE" to "完成",
                "progress" to 250, KEY_KMP_CLASS to "business-class", KEY_KMP_ID to "business-id",
                "bytes" to byteArrayOf(1, 2),
            ),
        ).build()
        listOf(null, 37).forEach { percent ->
            val progress = if (percent == null) {
                payload
            } else {
                Data.Builder().putAll(payload)
                    .putInt(KEY_KMP_PROGRESS, percent).build()
            }
            val info = androidx.work.WorkInfo(
                UUID.randomUUID(),
                androidx.work.WorkInfo.State.RUNNING,
                emptySet(),
                payload,
                progress,
            ).toKmp()
            assertEquals(percent ?: 0, info.progress.progress)
            assertNativeValues(payload, info.progress.data)
            assertNativeValues(payload, info.outputData)
        }
    }

    @Test
    fun workInfoProgress_doesNotClampInvalidReservedPercent() {
        val native = androidx.work.WorkInfo(
            UUID.randomUUID(),
            androidx.work.WorkInfo.State.RUNNING,
            emptySet(),
            progress = Data.Builder().putInt(KEY_KMP_PROGRESS, 101).build(),
        )
        assertFailsWith<IllegalArgumentException> { native.toKmp() }
    }

    private fun assertNativeRoundtrip(values: Map<String, Any?>) {
        val native = Data.Builder().putAll(values).build()
        val converted = WorkData(*values.map { it.toPair() }.toTypedArray()).toAndroid()
        assertEquals(native, converted)
        assertNativeValues(native, converted.toKmp())
        val storedNative = Data.fromByteArray(Data.toByteArrayInternalV1(native))
        val storedConverted = Data.fromByteArray(Data.toByteArrayInternalV1(converted.toKmp().toAndroid()))
        assertEquals(storedNative, storedConverted)
        assertNativeValues(storedNative, storedConverted.toKmp())
    }

    private fun assertNativeValues(native: Data, converted: WorkData) {
        val values = converted.keyValueMap()
        assertEquals(native.keyValueMap.keys, values.keys)
        native.keyValueMap.forEach { (key, expected) ->
            val actual = values[key]
            if (expected is Array<*>) {
                assertArrayValues(expected, actual, key)
            } else {
                assertEquals(expected, actual, key)
            }
        }
    }

    private fun assertArrayValues(expected: Array<*>, actual: Any?, message: String? = null) {
        val actualArray = assertIs<Array<*>>(actual, message)
        assertEquals<Class<*>>(expected.javaClass, actualArray.javaClass, message)
        assertContentEquals<Any?>(expected.asIterable(), actualArray.asIterable(), message)
    }

    // ── ContentUriTrigger ─────────────────────────────────────────────────────

    @Test
    @org.junit.Ignore(
        "Calls android.net.Uri.parse() which is stubbed on JVM host tests; " +
            "needs Robolectric or instrumented test runner. Pre-existing latent failure " +
            "surfaced when worker-kmp-cmp-launchers-01 enabled withHostTestBuilder for " +
            "this module — the test never ran before that fix.",
    )
    fun contentUriTrigger_appearsInAndroidConstraints() {
        val constraints = Constraints {
            addContentUriTrigger("content://com.example/items", triggerForDescendants = true)
            addContentUriTrigger("content://com.example/settings", triggerForDescendants = false)
        }
        val android = constraints.toAndroid()
        val triggers = android.contentUriTriggers
        assertEquals(2, triggers.size)
        assertTrue(triggers.any { it.uri.toString() == "content://com.example/items" && it.isTriggeredForDescendants })
        assertTrue(
            triggers.any {
                it.uri.toString() == "content://com.example/settings" && !it.isTriggeredForDescendants
            },
        )
    }

    @Test
    fun noContentUriTriggers_producesEmptySet() {
        val android = Constraints.NONE.toAndroid()
        assertTrue(android.contentUriTriggers.isEmpty())
    }
}
