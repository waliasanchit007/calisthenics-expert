package com.calisthenics.diagnostic.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservationJsonTest {
    @Test
    fun manifestUsesReleasedRequiredKeysAndSnakeCaseNames() {
        val json = Json.parseToJsonElement(encodeManifest(sampleManifest())).jsonObject

        assertEquals(
            setOf(
                "session_manifest_schema_version",
                "session_id",
                "participant_pseudonym_id",
                "capture_protocol_version",
                "harness_git_sha",
                "device",
                "pose_pipeline",
                "camera",
                "model_input",
                "inference_drain_timeout_ms",
                "cadence",
                "clock",
                "available_observations",
                "media_references",
                "counts",
                "machine_inference_failures",
                "capture_quality_observed",
                "study_context",
            ),
            json.keys,
        )
        assertEquals(
            listOf(
                "session_manifest_schema_version",
                "session_id",
                "participant_pseudonym_id",
                "capture_protocol_version",
                "harness_git_sha",
                "device",
                "pose_pipeline",
                "camera",
                "model_input",
                "inference_drain_timeout_ms",
                "cadence",
                "clock",
                "available_observations",
                "media_references",
                "counts",
                "machine_inference_failures",
                "capture_quality_observed",
                "study_context",
            ),
            json.keys.toList(),
        )
        assertContains(json["device"].toString(), "thermal_api_available")
        assertContains(json["pose_pipeline"].toString(), "model_asset_sha256")
        assertContains(json["capture_quality_observed"].toString(), "full_body_visible_at_hang")
    }

    @Test
    fun frameContainsRequiredNullableLandmarkFieldsAndSnakeCaseKeys() {
        val frame = sampleFrame(posePresent = false, backendTimestampMs = null)
        val json = Json.parseToJsonElement(encodeFrameLine(frame)).jsonObject

        assertEquals(
            setOf(
                "raw_pose_frame_schema_version",
                "session_id",
                "frame_index",
                "capture_timestamp_ns",
                "inference_start_ns",
                "inference_end_ns",
                "pose_present",
                "normalized_image_landmarks",
                "world_landmarks",
                "image_width",
                "image_height",
                "frames_skipped_since_last_emitted_record",
            ),
            json.keys,
        )
        assertEquals(JsonNull, json["normalized_image_landmarks"])
        assertEquals(JsonNull, json["world_landmarks"])
        assertFalse(encodeFrameLine(frame).contains("\r"))
    }

    @Test
    fun frameEncoderEmitsOneLfTrailingNewlineAndOptionalBackendTimestamp() {
        val line = encodeFrameLine(sampleFrame(posePresent = true, backendTimestampMs = 134))

        assertTrue(line.endsWith("\n"))
        assertFalse(line.dropLast(1).endsWith("\n"))
        assertFalse(line.endsWith("\n\n"))
        assertContains(line, "\"backend_timestamp_ms\":134")
        assertEquals(sampleFrame(posePresent = true, backendTimestampMs = 134), decodeFrame(line))
    }

    @Test
    fun unknownMajorIsRejected() {
        val json = encodeManifest(sampleManifest()).replace("1.1.0", "2.0.0")

        assertFailsWith<ContractDecodeException> { decodeManifest(json) }
    }

    @Test
    fun compatibleNewerMinorIsAdmitted() {
        val json = encodeManifest(sampleManifest()).replace("1.1.0", "1.9.0")

        assertEquals(sampleManifest().copy(schemaVersion = SemanticVersion("1.9.0")), decodeManifest(json))
    }

    @Test
    fun unknownFieldsAreToleratedWithoutChangingReleasedFields() {
        val manifest = Json.parseToJsonElement(encodeManifest(sampleManifest())).jsonObject
        val extendedManifest = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("future_field", true)
        }.toString()
        val frame = Json.parseToJsonElement(encodeFrameLine(sampleFrame())).jsonObject
        val extendedFrame = buildJsonObject {
            frame.forEach { (key, value) -> put(key, value) }
            put("future_frame_field", "ignored")
        }.toString()

        assertEquals(sampleManifest(), decodeManifest(extendedManifest))
        assertEquals(sampleFrame(), decodeFrame(extendedFrame))
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        val manifest = Json.parseToJsonElement(encodeManifest(sampleManifest())).jsonObject.toMutableMap()
        manifest.remove("counts")
        val missingManifest = buildJsonObject { manifest.forEach { (key, value) -> put(key, value) } }.toString()

        val frame = Json.parseToJsonElement(encodeFrameLine(sampleFrame())).jsonObject.toMutableMap()
        frame.remove("world_landmarks")
        val missingFrame = buildJsonObject { frame.forEach { (key, value) -> put(key, value) } }.toString()

        assertFailsWith<ContractDecodeException> { decodeManifest(missingManifest) }
        assertFailsWith<ContractDecodeException> { decodeFrame(missingFrame) }
    }

    @Test
    fun nonFiniteNumbersAreRejectedOnEncodeAndDecode() {
        assertFailsWith<IllegalArgumentException> {
            encodeFrameLine(sampleFrame(x = Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeFrameLine(sampleFrame(x = Double.POSITIVE_INFINITY))
        }
        assertFailsWith<ContractDecodeException> {
            decodeFrame(encodeFrameLine(sampleFrame()).replace("\"x\":0", "\"x\":NaN"))
        }
    }

    @Test
    fun uuidShaAndSemanticVersionWrappersValidateTheirReleasedShapes() {
        assertEquals("3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27", Uuid("3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27").value)
        assertEquals("0".repeat(64), Sha256("0".repeat(64)).value)
        assertEquals("1.1.0", SemanticVersion("1.1.0").value)
        assertFailsWith<IllegalArgumentException> { Uuid("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { Sha256("not-a-sha") }
        assertFailsWith<IllegalArgumentException> { SemanticVersion("1.1") }
    }

    @Test
    fun sameMillisecondCapturesCarryAStrictlyIncreasingBackendTimestamp() {
        val first = nextBackendTimestampMs(captureTimestampNs = 133_000_000, previousBackendTimestampMs = null)
        val second = nextBackendTimestampMs(captureTimestampNs = 133_500_000, previousBackendTimestampMs = first)
        val later = nextBackendTimestampMs(captureTimestampNs = 900_000_000, previousBackendTimestampMs = second)

        assertEquals(133, first)
        assertEquals(134, second)
        assertEquals(900, later)
        assertTrue(second > first)
        assertTrue(later > second)
    }

    @Test
    fun missingBackendTimestampsAreSkippedWhilePresentValuesRemainStrictlyOrdered() {
        val first = sampleFrame(captureTimestampNs = 133_000_000, backendTimestampMs = 133)
        val missing = sampleFrame(captureTimestampNs = 133_500_000, backendTimestampMs = null)
        val third = sampleFrame(captureTimestampNs = 134_000_000, backendTimestampMs = 134)

        val firstJson = Json.parseToJsonElement(encodeFrameLine(first)).jsonObject
        val missingJson = Json.parseToJsonElement(encodeFrameLine(missing)).jsonObject
        val thirdJson = Json.parseToJsonElement(encodeFrameLine(third)).jsonObject

        assertNotNull(firstJson["backend_timestamp_ms"])
        assertEquals(null, missingJson["backend_timestamp_ms"])
        assertEquals(JsonPrimitive(134), thirdJson["backend_timestamp_ms"])
        assertTrue(third.backendTimestampMs!! > first.backendTimestampMs!!)
    }

    @Test
    fun canonicalNumberFormatterMatchesRfc8785Vectors() {
        val vectors = Json.parseToJsonElement(numberVectors()).jsonArray

        vectors.forEach { vector ->
            val input = vector.jsonObject.getValue("input").jsonPrimitive.content.toDouble()
            val expected = vector.jsonObject.getValue("expected").jsonPrimitive.content
            assertEquals(expected, CanonicalNumber.format(input), vector.jsonObject.getValue("name").jsonPrimitive.content)
        }
        assertFailsWith<IllegalArgumentException> { CanonicalNumber.format(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CanonicalNumber.format(Double.NEGATIVE_INFINITY) }
    }

    private fun numberVectors(): String =
        """[
          {"name":"zero","input":0.0,"expected":"0"},
          {"name":"negative-zero","input":-0.0,"expected":"0"},
          {"name":"one","input":1.0,"expected":"1"},
          {"name":"fraction","input":1.5,"expected":"1.5"},
          {"name":"lower-exponent-bound","input":0.000001,"expected":"0.000001"},
          {"name":"scientific-small","input":1e-7,"expected":"1e-7"},
          {"name":"scientific-large","input":1e21,"expected":"1e+21"},
          {"name":"large-fixed","input":100000000000000000000.0,"expected":"100000000000000000000"},
          {"name":"round-trip","input":1.2345678901234567,"expected":"1.2345678901234567"},
          {"name":"ecmascript-rounding","input":333333333.33333329,"expected":"333333333.3333333"},
          {"name":"shortest-representation","input":1.0000000000000001,"expected":"1"},
          {"name":"very-small","input":1e-27,"expected":"1e-27"}
        ]"""

    private fun sampleManifest(): SessionManifest = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27"),
        sessionKind = null,
        participantPseudonymId = "participant-synthetic-01",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "0".repeat(39) + "5",
        device = Device("android", "14", "synthetic-device", null, true),
        posePipeline = PosePipeline(
            "mediapipe_tasks_vision",
            "0.10.14",
            "pose_landmarker_full.task",
            Sha256("0".repeat(63) + "3"),
            "cpu",
            "live_stream",
            1,
            0.5,
            0.5,
            0.5,
            "mediapipe_pose_33_v1",
        ),
        camera = Camera("back", 1280, 720, 0, false, 30.0, null, null, null, null, null, null),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), null),
        inferenceDrainTimeoutMs = 2000,
        cadence = Cadence(30.0, listOf(CadenceSample(0, 29.5)), null),
        clock = Clock("android_elapsed_realtime_nanos", 100, "2026-08-12T00:00:00Z", "2026-08-12T00:00:10Z"),
        availableObservations = AvailableObservations(true, true),
        mediaReferences = listOf(MediaReference("static_reference_clip", "media/static_reference.rgb24", Sha256("0".repeat(63) + "1"), null, null)),
        counts = Counts(1, 1, 1, 1, 0, mapOf("cadence_selection" to 0, "backpressure" to 0, "session_stopping" to 0), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "indoor_bright", false, ""),
        studyContext = mapOf("environment" to JsonPrimitive("indoor"), "angle_deg_nominal" to JsonPrimitive(45)),
    )

    private fun sampleFrame(
        posePresent: Boolean = true,
        backendTimestampMs: Long? = 133,
        captureTimestampNs: Long = 133_000_000,
        x: Double = 0.0,
    ): RawPoseFrame {
        val landmarks = if (posePresent) {
            listOf(Landmark(0, null, x, 0.0, 0.0, 0.9, 0.8))
        } else {
            null
        }
        return RawPoseFrame(
            schemaVersion = SemanticVersion("1.1.0"),
            sessionId = Uuid("3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27"),
            frameIndex = 0,
            captureTimestampNs = captureTimestampNs,
            backendTimestampMs = backendTimestampMs,
            inferenceStartNs = captureTimestampNs + 1,
            inferenceEndNs = captureTimestampNs + 2,
            posePresent = posePresent,
            normalizedImageLandmarks = landmarks,
            worldLandmarks = landmarks,
            imageWidth = 1280,
            imageHeight = 720,
            framesSkippedSinceLastEmittedRecord = 0,
        )
    }
}
