package com.calisthenics.diagnostic.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCaptureEvidenceTest {
    @Test
    fun rejectsMalformedHarnessGitSha() {
        assertFailsWith<IllegalArgumentException> {
            renderLiveCaptureEvidence(admittedEvidence().copy(harnessGitSha = "not-a-sha"))
        }
    }

    @Test
    fun renderedEvidenceCarriesTheProducerHarnessGitSha() {
        assertTrue(renderLiveCaptureEvidence(admittedEvidence()).contains("\"harness_git_sha\""))
    }

    @Test
    fun exactObservedS25EvidenceRendersOneSanitizedAdmittedRecord() {
        val rendered = renderLiveCaptureEvidence(admittedEvidence())

        val expected = EXACT_ADMITTED_RECORD.replace(
            "\"session_id\":\"00000000-0000-4000-8000-000000000001\",",
            "\"session_id\":\"00000000-0000-4000-8000-000000000001\",\"harness_git_sha\":\"0123456789abcdef0123456789abcdef01234567\",",
        )
        assertEquals(expected, rendered)
        assertTrue(rendered.contains("\"sanitized\":true"))
        assertTrue(rendered.contains("\"delivered_callback_classification_gap\":0"))
        assertTrue(rendered.contains("\"upstream_unclassified_cadence_gap_estimate\":0"))
        assertTrue(rendered.contains("\"capture_width\":1280"))
        assertTrue(rendered.contains("\"capture_height\":720"))
        assertTrue(rendered.contains("\"orientation_degrees\":90"))
        assertTrue(rendered.contains("\"mirrored\":true"))
        assertTrue(rendered.contains("\"thermal\":{\"api_available\":true"))
        assertFalse(rendered.contains("\"unclassified_delivered_callbacks\""))
        assertTrue(rendered.contains("\"satisfies_s25_live_capture\":true"))
        assertTrue(liveCaptureBlockingReasons(admittedEvidence()).isEmpty())
    }

    @Test
    fun everyLoadBearingObservationProducesItsTypedBlockingReason() {
        val mutations = listOf(
            admittedEvidence().copy(environment = admittedEvidence().environment.copy(model = "SM-S918B")) to LIVE_CAPTURE_BLOCK_UNAUTHORIZED_TARGET,
            admittedEvidence().copy(manifestDevice = admittedEvidence().manifestDevice.copy(deviceModel = "unknown")) to LIVE_CAPTURE_BLOCK_MANIFEST_DEVICE_MISMATCH,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(cameraId = "1")) to LIVE_CAPTURE_BLOCK_CAMERA_ID,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(timestampSource = "UNKNOWN")) to LIVE_CAPTURE_BLOCK_TIMESTAMP_SOURCE,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(sourceDataspaceRaw = 0)) to LIVE_CAPTURE_BLOCK_RAW_DATASPACE,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(sourceColorStandard = "BT709")) to LIVE_CAPTURE_BLOCK_COLOUR_STANDARD,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(sourceColorRange = "LIMITED")) to LIVE_CAPTURE_BLOCK_COLOUR_RANGE,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(underlyingImageAvailable = false)) to LIVE_CAPTURE_BLOCK_UNDERLYING_IMAGE,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(pixelFormat = "RGBA_8888")) to LIVE_CAPTURE_BLOCK_PIXEL_FORMAT,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(captureWidth = 0)) to LIVE_CAPTURE_BLOCK_CAMERA_GEOMETRY,
            admittedEvidence().copy(camera = admittedEvidence().camera.copy(orientationDegrees = 45)) to LIVE_CAPTURE_BLOCK_CAMERA_GEOMETRY,
            admittedEvidence().copy(thermal = admittedEvidence().thermal.copy(apiAvailable = false)) to LIVE_CAPTURE_BLOCK_THERMAL,
            admittedEvidence().copy(thermal = admittedEvidence().thermal.copy(unavailableReason = "thermal_api_unavailable")) to LIVE_CAPTURE_BLOCK_THERMAL,
            admittedEvidence().copy(thermal = admittedEvidence().thermal.copy(observations = emptyList())) to LIVE_CAPTURE_BLOCK_THERMAL,
            admittedEvidence().copy(counts = admittedEvidence().counts.copy(cameraFramesReceived = 0)) to LIVE_CAPTURE_BLOCK_RECEIVED,
            admittedEvidence().copy(counts = admittedEvidence().counts.copy(inferenceFramesSubmitted = 0)) to LIVE_CAPTURE_BLOCK_SUBMITTED,
            admittedEvidence().copy(counts = admittedEvidence().counts.copy(inferenceRecordsEmitted = 0)) to LIVE_CAPTURE_BLOCK_EMITTED,
            admittedEvidence().copy(
                counts = admittedEvidence().counts.copy(
                    deliveredCallbackClassificationGap = 1,
                    cadenceDiagnostics = admittedEvidence().counts.cadenceDiagnostics.copy(
                        deliveredCallbackClassificationGap = 1,
                    ),
                ),
            ) to LIVE_CAPTURE_BLOCK_CLASSIFICATION_ACCOUNTING,
            admittedEvidence().copy(referenceValidationSucceeded = false) to LIVE_CAPTURE_BLOCK_VALIDATOR,
            admittedEvidence().copy(runFailureCode = LIVE_CAPTURE_BLOCK_TIMEOUT) to LIVE_CAPTURE_BLOCK_TIMEOUT,
            admittedEvidence().copy(runFailureCode = LIVE_CAPTURE_BLOCK_STALL) to LIVE_CAPTURE_BLOCK_STALL,
            admittedEvidence().copy(runFailureCode = LIVE_CAPTURE_BLOCK_FAILURE) to LIVE_CAPTURE_BLOCK_FAILURE,
            admittedEvidence().copy(instrumentationSucceeded = false) to LIVE_CAPTURE_BLOCK_INSTRUMENTATION,
        )

        mutations.forEach { (evidence, expected) ->
            assertTrue(expected in liveCaptureBlockingReasons(evidence), "missing $expected")
            assertFalse(liveCaptureIsAdmitted(evidence), "mutation $expected must block")
        }
    }

    @Test
    fun deviceAuthorizationIsDelegatedAndManifestMustAgreeWithObservedBuild() {
        val environment = admittedEvidence().environment
        assertTrue(isAuthorizedLiveCaptureTarget(environment))
        assertFalse(isAuthorizedLiveCaptureTarget(environment.copy(manufacturer = "Google")))
        assertTrue(
            LIVE_CAPTURE_BLOCK_MANIFEST_DEVICE_MISMATCH in liveCaptureBlockingReasons(
                admittedEvidence().copy(manifestDevice = admittedEvidence().manifestDevice.copy(chipset = "other")),
            ),
        )
    }

    @Test
    fun cadenceEstimateMustRemainDerivedFromRequestedOpportunitiesAndDeliveredCallbacks() {
        val evidence = admittedEvidence().copy(
            counts = admittedEvidence().counts.copy(
                cadenceDiagnostics = admittedEvidence().counts.cadenceDiagnostics.copy(
                    upstreamUnclassifiedCadenceGapEstimate = 1,
                ),
            ),
        )

        assertTrue(LIVE_CAPTURE_BLOCK_CADENCE_DIAGNOSTICS in liveCaptureBlockingReasons(evidence))
        assertFalse(liveCaptureIsAdmitted(evidence))
    }

    @Test
    fun cadenceDiagnosticsRequireTheRequestedThirtyFpsCameraRate() {
        val evidence = admittedEvidence().copy(
            counts = admittedEvidence().counts.copy(
                cadenceDiagnostics = admittedEvidence().counts.cadenceDiagnostics.copy(
                    requestedCameraFps = 15,
                    requestedCallbackOpportunities = 5,
                ),
            ),
        )

        assertTrue(LIVE_CAPTURE_BLOCK_CADENCE_DIAGNOSTICS in liveCaptureBlockingReasons(evidence))
        assertFalse(liveCaptureIsAdmitted(evidence))
    }

    @Test
    fun typedInitializationFailurePreservesCodeAndSanitizedDetail() {
        val rendered = renderLiveCaptureEvidence(
            admittedEvidence().copy(
                runFailureCode = "live_initialization_failed",
                runFailureDetail = sanitizeLiveCaptureFailureDetail(
                    "live capture requires running_mode LIVE_STREAM, found VIDEO at /Users/private/app\nstack trace",
                ),
            ),
        )

        assertTrue(rendered.contains("\"run_failure_code\":\"live_initialization_failed\""))
        assertTrue(rendered.contains("live capture requires running_mode LIVE_STREAM, found VIDEO"))
        assertTrue(rendered.contains("<redacted-path>"))
        assertFalse(rendered.contains("/Users/private/app"))
        assertFalse(rendered.contains("stack trace"))
        assertTrue(
            LIVE_CAPTURE_BLOCK_INITIALIZATION in liveCaptureBlockingReasons(
                admittedEvidence().copy(runFailureCode = LIVE_CAPTURE_BLOCK_INITIALIZATION),
            ),
        )
    }

    @Test
    fun everyNonNullRunFailureBlocksLiveAdmission() {
        val evidence = admittedEvidence().copy(runFailureCode = "live_source_failed")

        assertTrue("live_source_failed" in liveCaptureBlockingReasons(evidence))
        assertFalse(liveCaptureIsAdmitted(evidence))
    }

    private fun admittedEvidence() = LiveCaptureEvidence(
        emittedBy = "com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest",
        sessionId = "00000000-0000-4000-8000-000000000001",
        harnessGitSha = "0123456789abcdef0123456789abcdef01234567",
        environment = LiveCaptureEnvironment(
            deviceClass = "physical_device",
            deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
            deviceFingerprint = "samsung/pa3q/pa3qxins:16/BP1A.250605.004/S938BXXU1: user/release-keys",
            apiLevel = 36,
            androidRelease = "16",
            abi = "arm64-v8a",
            manufacturer = "samsung",
            model = "SM-S938B",
            marketingName = "",
            chipset = "s5e9955",
        ),
        manifestDevice = LiveCaptureManifestDevice("android", "16", "SM-S938B", "s5e9955"),
        camera = LiveCaptureCameraObservation(
            cameraId = "0",
            timestampSource = "REALTIME",
            sourceDataspaceRaw = 146931712,
            sourceColorStandard = "BT601",
            sourceColorRange = "FULL",
            underlyingImageAvailable = true,
            pixelFormat = "YUV_420_888",
            captureWidth = 1280,
            captureHeight = 720,
            orientationDegrees = 90,
            mirrored = true,
        ),
        thermal = LiveCaptureThermalSummary(
            apiAvailable = true,
            unavailableReason = null,
            observations = listOf(
                LiveCaptureThermalObservation(0L, 0),
                LiveCaptureThermalObservation(3_000_000_000L, 3),
            ),
        ),
        counts = LiveCaptureCounts(
            cameraFramesReceived = 10,
            inferenceFramesSubmitted = 2,
            inferenceRecordsEmitted = 2,
            deliveredCallbackClassificationGap = 0,
            cadenceDiagnostics = LiveCaptureCadenceDiagnostics.fromAdmittedTimestamps(
                timestampsNs = (0L..9L).map { it * 33_333_334L },
                requestedCameraFps = 30,
                deliveredCallbackClassificationGap = 0,
            ),
        ),
        referenceValidationSucceeded = true,
        instrumentationSucceeded = true,
    )

    private companion object {
        const val EXACT_ADMITTED_RECORD = "{\"artifact_schema_version\":\"1.0.0\",\"record_kind\":\"live_capture_device_gate_observation\",\"sanitized\":true,\"contains_landmark_coordinates\":false,\"emitted_by\":\"com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest\",\"session_id\":\"00000000-0000-4000-8000-000000000001\",\"environment\":{\"device_class\":\"physical_device\",\"device_identity\":\"samsung/SM-S938B/pa3q/pa3qxins\",\"device_fingerprint\":\"samsung/pa3q/pa3qxins:16/BP1A.250605.004/S938BXXU1: user/release-keys\",\"api_level\":36,\"android_release\":\"16\",\"abi\":\"arm64-v8a\",\"manufacturer\":\"samsung\",\"model\":\"SM-S938B\",\"marketing_name\":\"\",\"chipset\":\"s5e9955\"},\"manifest_device\":{\"platform\":\"android\",\"os_version\":\"16\",\"device_model\":\"SM-S938B\",\"chipset\":\"s5e9955\"},\"camera\":{\"camera_id\":\"0\",\"timestamp_source\":\"REALTIME\",\"source_dataspace_raw\":146931712,\"source_color_standard\":\"BT601\",\"source_color_range\":\"FULL\",\"underlying_image_available\":true,\"pixel_format\":\"YUV_420_888\",\"capture_width\":1280,\"capture_height\":720,\"orientation_degrees\":90,\"mirrored\":true},\"thermal\":{\"api_available\":true,\"unavailable_reason\":null,\"samples\":[{\"t_monotonic_ns\":0,\"status\":0},{\"t_monotonic_ns\":3000000000,\"status\":3}]},\"counts\":{\"camera_frames_received\":10,\"inference_frames_submitted\":2,\"inference_records_emitted\":2,\"delivered_callback_classification_gap\":0,\"cadence_diagnostics\":{\"delivered_callback_count\":10,\"first_delivered_capture_timestamp_ns\":0,\"last_delivered_capture_timestamp_ns\":300000006,\"observed_capture_span_ns\":300000006,\"requested_camera_fps\":30,\"requested_callback_opportunities\":10,\"upstream_unclassified_cadence_gap_estimate\":0,\"delivered_callback_classification_gap\":0},\"inference_failures\":0,\"frames_skipped_before_inference\":0,\"frames_skipped_before_inference_by_reason\":{}},\"inference_failure_identities\":[],\"reference_validation_succeeded\":true,\"instrumentation_succeeded\":true,\"run_failure_code\":null,\"run_failure_detail\":null,\"blocking_reasons\":[],\"satisfies_s25_live_capture\":true}"
    }
}
