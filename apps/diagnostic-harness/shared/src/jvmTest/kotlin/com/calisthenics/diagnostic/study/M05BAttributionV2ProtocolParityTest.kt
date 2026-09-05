package com.calisthenics.diagnostic.study

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class M05BAttributionV2ProtocolParityTest {
    @Test
    fun executableConstantsEqualEveryCanonicalProtocolField() {
        val protocol = Json.parseToJsonElement(
            Files.readString(repositoryRoot().resolve("docs/protocols/m05b-attribution-v2.protocol.json")),
        ).jsonObject

        assertEquals(M05BAttributionV2Protocol.VERSION, protocol.string("protocol_version"))
        assertEquals("m05b_attribution_v2_protocol", protocol.string("record_kind"))
        assertEquals(M05BAttributionV2Protocol.TARGET_CADENCES_FPS, protocol.intList("target_cadences_fps"))
        assertEquals(
            M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS,
            protocol.getValue("source").jsonObject.int("warmup_callbacks"),
        )
        assertEquals(
            M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS,
            protocol.getValue("source").jsonObject.int("measured_callbacks"),
        )
        assertEquals(M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM, protocol.int("raw_callback_maximum"))
        assertEquals(M05BAttributionV2Protocol.SESSION_ARTIFACTS, protocol.stringList("session_artifacts"))
        val environment = protocol.getValue("environment_operands").jsonObject
        assertEquals(
            M05BAttributionV2Protocol.ENVIRONMENT_BOUNDARY_FIELDS,
            environment.stringList("boundary_fields"),
        )
        assertEquals(
            M05BAttributionV2Protocol.ENVIRONMENT_SNAPSHOT_FIELDS,
            environment.stringList("snapshot_fields"),
        )
        assertEquals(
            M05BAttributionV2Protocol.ENVIRONMENT_ENFORCED_PREDICATES,
            environment.stringList("enforced_predicates"),
        )
        val backend = protocol.getValue("backend").jsonObject
        assertEquals(M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS, backend.int("warmup_completed"))
        assertEquals(M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS, backend.long("warmup_elapsed_ns"))
        assertEquals(M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS, backend.int("measured_completions"))
        assertEquals(M05BAttributionV2Protocol.ARM_CAP_NS, protocol.long("arm_cap_ns"))
        val authorizedDevice = protocol.getValue("authorized_device").jsonObject
        assertEquals("physical_device", authorizedDevice.string("device_class"))
        assertEquals("samsung/SM-S938B/pa3q/pa3qxins", authorizedDevice.string("device_identity"))
        assertEquals("samsung", authorizedDevice.string("manufacturer"))
        assertEquals("SM-S938B", authorizedDevice.string("model"))
        assertEquals("36", authorizedDevice.string("api_level"))
        assertEquals("16", authorizedDevice.string("android_release"))
        assertEquals("BP4A.251205.006.S938BXXSBCZG3", authorizedDevice.string("os_build"))
        val cooldown = protocol.getValue("cooldown").jsonObject
        assertEquals(M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS, cooldown.long("sampling_interval_ns"))
        assertEquals(M05BAttributionV2Protocol.COOLDOWN_MAX_NS, cooldown.long("maximum_duration_ns"))
        assertEquals(
            M05BAttributionV2Protocol.REPLICATE_ORDERS,
            protocol.getValue("replicate_orders").jsonArray.map { order ->
                order.jsonArray.map { it.jsonPrimitive.content.toInt() }
            },
        )
        assertEquals(M05BAttributionV2Protocol.TERMINAL_CLASSES, protocol.stringList("terminal_classes"))
        val clock = protocol.getValue("clock").jsonObject
        assertEquals("session_relative_monotonic", clock.string("domain"))
        assertEquals("nanoseconds", clock.string("unit"))
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun Map<String, kotlinx.serialization.json.JsonElement>.int(key: String): Int = string(key).toInt()

    private fun Map<String, kotlinx.serialization.json.JsonElement>.long(key: String): Long = string(key).toLong()

    private fun Map<String, kotlinx.serialization.json.JsonElement>.intList(key: String): List<Int> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content.toInt() }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.stringList(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }.first { Files.isRegularFile(it.resolve("docs/protocols/m05b-attribution-v2.protocol.json")) }
}
