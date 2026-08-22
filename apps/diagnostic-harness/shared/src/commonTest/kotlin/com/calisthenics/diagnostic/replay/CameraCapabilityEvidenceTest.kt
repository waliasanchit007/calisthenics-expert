package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun environment(
    deviceClass: String = "physical_device",
    manufacturer: String = "samsung",
    model: String = "SM-S938B",
    marketingName: String = "",
) = CameraCapabilityEnvironment(
    deviceClass = deviceClass,
    deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
    deviceFingerprint = "samsung/pa3qxins/pa3q:16/BP4A.251205.006/x:user/release-keys",
    apiLevel = 36,
    androidRelease = "16",
    abi = "arm64-v8a",
    manufacturer = manufacturer,
    model = model,
    marketingName = marketingName,
)

private fun provenance() = CameraCapabilityProvenance(
    osBuild = "BP4A.251205.006",
    cameraxVersion = "1.6.1",
    probeGitSha = "0123456789abcdef0123456789abcdef01234567",
)

private fun observation(
    cameraId: String = "0",
    timestampSource: String = "REALTIME",
    sourceDataspaceRaw: Int? = 146931712,
    sourceColorStandard: String? = "BT601",
    sourceColorRange: String? = "LIMITED",
    underlyingImageAvailable: Boolean = true,
) = CameraCapabilityObservation(
    cameraId = cameraId,
    timestampSource = timestampSource,
    sourceDataspaceRaw = sourceDataspaceRaw,
    sourceColorStandard = sourceColorStandard,
    sourceColorRange = sourceColorRange,
    underlyingImageAvailable = underlyingImageAvailable,
    pixelFormat = "YUV_420_888",
)

private fun render(
    env: CameraCapabilityEnvironment = environment(),
    obs: CameraCapabilityObservation = observation(),
) = renderCameraCapabilityEvidence(
    CameraCapabilityEvidence(
        emittedBy = "com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest",
        environment = env,
        provenance = provenance(),
        observation = obs,
    ),
)

private const val ADMITS = """"satisfies_s25_camera_live_admission":true"""
private const val BLOCKS = """"satisfies_s25_camera_live_admission":false"""

class CameraCapabilityEvidenceTest {
    @Test
    fun realtimeKnownColourOnTheAuthorizedS25AdmitsLiveCapture() {
        val rendered = render()

        assertTrue(rendered.contains(ADMITS), rendered)
        assertTrue(rendered.contains(""""live_admission_blocking_reasons":[]"""), rendered)
    }

    @Test
    fun authorizesTheS25UltraByModelWhenMarketingNameIsAbsent() {
        // Retail S25 Ultra firmware need not populate ro.config.marketing_name; the model number
        // is the authoritative identifier, exactly as isAuthorizedS25Target defines it.
        assertTrue(render(env = environment(marketingName = "", model = "SM-S938B")).contains(ADMITS))
    }

    @Test
    fun stillAuthorizesOnMarketingNameWhenTheModelIsUnrecognized() {
        assertTrue(
            render(env = environment(model = "unknown", marketingName = "Galaxy S25 Ultra")).contains(ADMITS),
        )
    }

    // --- one named mutation-guard test per load-bearing gate conjunct (task brief item 9) ---

    @Test
    fun anUnknownTimestampSourceBlocksLiveAdmission() {
        // Design section 8: UNKNOWN is never remapped to another clock, so a capture timestamp in
        // an unknown domain cannot be compared with elapsedRealtimeNanos inference timing at all.
        val rendered = render(obs = observation(timestampSource = "UNKNOWN"))

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("timestamp_source_not_realtime"), rendered)
    }

    @Test
    fun anUnavailableUnderlyingImageBridgeBlocksLiveAdmission() {
        // CameraX's underlying-image bridge is nullable and experimental. When it yields no image
        // there is no DataSpace to read, so the colour provenance the live adapter requires was
        // never observed — a successful probe, and a blocking one.
        val rendered = render(
            obs = observation(
                underlyingImageAvailable = false,
                sourceDataspaceRaw = null,
                sourceColorStandard = null,
                sourceColorRange = null,
            ),
        )

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("underlying_image_bridge_unavailable"), rendered)
    }

    @Test
    fun anUnavailableBridgeBlocksEvenWhenColourMetadataIsSomehowPresent() {
        // Fail closed on a self-contradictory observation rather than believing its colour fields:
        // a record claiming decoded colour with no underlying image cannot have read one.
        assertTrue(render(obs = observation(underlyingImageAvailable = false)).contains(BLOCKS))
    }

    @Test
    fun anUnknownRawDataSpaceBlocksLiveAdmission() {
        // DataSpace.DATASPACE_UNKNOWN is a real value the S25 may report; it is an observation,
        // not an error, and it must never admit live capture.
        val rendered = render(obs = observation(sourceDataspaceRaw = CAMERA_DATASPACE_UNKNOWN))

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("raw_dataspace_unknown"), rendered)
    }

    @Test
    fun anAbsentRawDataSpaceBlocksLiveAdmission() {
        assertTrue(render(obs = observation(sourceDataspaceRaw = null)).contains(BLOCKS))
    }

    @Test
    fun anUndecodedColourStandardBlocksLiveAdmission() {
        val rendered = render(obs = observation(sourceColorStandard = null))

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("colour_standard_undecoded"), rendered)
    }

    @Test
    fun anUndecodedColourRangeBlocksLiveAdmission() {
        val rendered = render(obs = observation(sourceColorRange = null))

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("colour_range_undecoded"), rendered)
    }

    @Test
    fun anEmulatorBlocksLiveAdmission() {
        val rendered = render(env = environment(deviceClass = "emulator"))

        assertTrue(rendered.contains(BLOCKS), rendered)
        assertTrue(rendered.contains("unauthorized_device_target"), rendered)
        assertTrue(rendered.contains(""""authorized_s25_target":false"""), rendered)
    }

    @Test
    fun anotherSamsungPhysicalDeviceBlocksLiveAdmission() {
        assertTrue(render(env = environment(model = "SM-S931B")).contains(BLOCKS))
    }

    @Test
    fun aNonSamsungDeviceWithTheS25ModelPrefixBlocksLiveAdmission() {
        assertTrue(render(env = environment(manufacturer = "notsamsung")).contains(BLOCKS))
    }

    // --- record shape ---

    @Test
    fun recordsEveryObservedFieldTheGateAndTheHumanRecordingStepNeed() {
        val rendered = render()

        listOf(
            """"artifact_schema_version":"1.0.0"""",
            """"record_kind":"$CAMERA_CAPABILITY_RECORD_KIND"""",
            """"camera_id":"0"""",
            """"timestamp_source":"REALTIME"""",
            """"source_dataspace_raw":146931712""",
            """"source_color_standard":"BT601"""",
            """"source_color_range":"LIMITED"""",
            """"underlying_image_available":true""",
            """"pixel_format":"YUV_420_888"""",
            """"model":"SM-S938B"""",
            """"device_identity":"samsung/SM-S938B/pa3q/pa3qxins"""",
            """"device_fingerprint":"samsung/pa3qxins/pa3q:16/BP4A.251205.006/x:user/release-keys"""",
            """"api_level":36""",
            """"android_release":"16"""",
            """"abi":"arm64-v8a"""",
            """"manufacturer":"samsung"""",
            """"marketing_name":""""",
            """"device_class":"physical_device"""",
            """"os_build":"BP4A.251205.006"""",
            """"camerax_version":"1.6.1"""",
            """"probe_git_sha":"0123456789abcdef0123456789abcdef01234567"""",
        ).forEach { fragment -> assertTrue(rendered.contains(fragment), "$fragment missing from $rendered") }
    }

    @Test
    fun theRecordIsSanitizedNonParticipantAndCarriesNoCoordinates() {
        val rendered = render()

        assertTrue(rendered.contains(""""sanitized":true"""), rendered)
        assertTrue(rendered.contains(""""contains_landmark_coordinates":false"""), rendered)
        assertTrue(rendered.contains(""""non_participant_observation":true"""), rendered)
        assertTrue(rendered.contains(""""observation_subject":"device_camera_capability""""), rendered)
        assertFalse(rendered.contains("landmark_x"))
        assertFalse(rendered.contains("normalized_image_landmarks"))
        assertFalse(rendered.contains("world_landmarks"))
    }

    @Test
    fun renderingIsDeterministicForTheSameObservation() {
        assertEquals(render(), render())
    }

    @Test
    fun everyBlockingReasonIsReportedTogetherInADeterministicOrder() {
        val rendered = render(
            env = environment(deviceClass = "emulator"),
            obs = observation(
                timestampSource = "UNKNOWN",
                underlyingImageAvailable = false,
                sourceDataspaceRaw = null,
                sourceColorStandard = null,
                sourceColorRange = null,
            ),
        )

        assertTrue(
            rendered.contains(
                """"live_admission_blocking_reasons":["timestamp_source_not_realtime",""" +
                    """"underlying_image_bridge_unavailable","raw_dataspace_unknown",""" +
                    """"colour_standard_undecoded","colour_range_undecoded","unauthorized_device_target"]""",
            ),
            rendered,
        )
    }
}
