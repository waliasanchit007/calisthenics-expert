package com.calisthenics.diagnostic.study

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerTimestampEvidenceTest {
    @Test
    fun constant_offset_is_derived_from_nonzero_submitted_and_demuxed_pts() {
        val evidence = evidence(
            submitted = listOf(1_000_000L, 1_033_333L, 1_066_666L),
            demuxed = listOf(999_000L, 1_032_333L, 1_065_666L),
        )

        assertEquals(1_000L, deriveContainerOffsetUs(evidence.observation))
        assertTrue(containerTimestampBlockingReasons(evidence).isEmpty())
        val rendered = renderContainerTimestampEvidence(evidence)
        assertContains(rendered, "\"constant_offset_us\":1000")
        assertContains(rendered, "\"blocking_result\":false")
        assertContains(rendered, "\"harness_git_sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"")
        assertContains(rendered, "\"api_level\":36")
        assertContains(rendered, "\"non_participant_observation\":true")
    }

    @Test
    fun varying_transform_is_a_successful_but_blocking_typed_observation() {
        val evidence = evidence(
            submitted = listOf(1_000_000L, 1_033_333L, 1_066_666L),
            demuxed = listOf(999_000L, 1_032_332L, 1_065_666L),
        )

        assertNull(deriveContainerOffsetUs(evidence.observation))
        assertEquals(
            listOf(CONTAINER_TIMESTAMP_BLOCK_NON_CONSTANT_TRANSFORM),
            containerTimestampBlockingReasons(evidence),
        )
        val rendered = renderContainerTimestampEvidence(evidence)
        assertContains(rendered, "\"blocking_result\":true")
        assertContains(rendered, CONTAINER_TIMESTAMP_BLOCK_NON_CONSTANT_TRANSFORM)
    }

    @Test
    fun zero_input_count_mismatch_and_unauthorized_device_each_fail_closed() {
        val zeroInput = evidence(listOf(0L, 1L), listOf(0L, 1L))
        assertContains(containerTimestampBlockingReasons(zeroInput), CONTAINER_TIMESTAMP_BLOCK_ZERO_PTS)

        val mismatch = evidence(listOf(1L, 2L), listOf(1L))
        assertContains(containerTimestampBlockingReasons(mismatch), CONTAINER_TIMESTAMP_BLOCK_COUNT_MISMATCH)

        val unauthorized = evidence(listOf(1L), listOf(1L)).copy(
            environment = environment().copy(model = "Pixel 10", manufacturer = "Google"),
        )
        assertContains(
            containerTimestampBlockingReasons(unauthorized),
            CONTAINER_TIMESTAMP_BLOCK_UNAUTHORIZED_TARGET,
        )
        assertFalse(containerTimestampBlockingReasons(unauthorized).isEmpty())
    }

    @Test
    fun submitted_and_demuxed_sequences_must_preserve_strict_order() {
        val submittedOutOfOrder = evidence(listOf(2L, 1L), listOf(2L, 1L))
        assertContains(
            containerTimestampBlockingReasons(submittedOutOfOrder),
            CONTAINER_TIMESTAMP_BLOCK_SUBMITTED_ORDER,
        )

        val demuxedOutOfOrder = evidence(listOf(1L, 2L), listOf(2L, 1L))
        assertContains(
            containerTimestampBlockingReasons(demuxedOutOfOrder),
            CONTAINER_TIMESTAMP_BLOCK_DEMUX_ORDER,
        )
    }

    @Test
    fun controlledEvidenceAdmitsExactOnGridAndNonexactBoundedOffGridSequences() {
        val evidence = controlledEvidence()

        assertEquals(emptyList(), containerTimestampBlockingReasons(evidence))
        val rendered = renderContainerTimestampEvidence(evidence)
        assertContains(rendered, "\"artifact_schema_version\":\"1.1.0\"")
        assertContains(rendered, "\"video_ticks_per_second\":90000")
        assertContains(rendered, "\"metadata_ticks_per_second\":90000")
        assertContains(rendered, "\"constant_offset_us\":1000000")
        assertContains(rendered, "\"minimum\":1000000000")
        assertContains(rendered, "\"maximum\":1000009000")
        assertContains(rendered, "\"quantization_span_bound_ns\":24223")
    }

    @Test
    fun controlledEvidenceRejectsAVacuouslyExactOffGridSequence() {
        val exact = ContainerTimestampObservation(
            submittedPtsUs = listOf(1_000_000L, 1_033_300L, 1_066_600L),
            demuxedPtsUs = listOf(0L, 33_300L, 66_600L),
        )
        val evidence = controlledEvidence().copy(
            controls = controlledEvidence().controls?.copy(offGrid = exact),
        )

        assertContains(
            containerTimestampBlockingReasons(evidence),
            CONTAINER_TIMESTAMP_BLOCK_OFF_GRID_EXACT,
        )
    }

    @Test
    fun controlledEvidenceRejectsAnOverBoundInteriorVariation() {
        val overBound = ContainerTimestampObservation(
            submittedPtsUs = listOf(1_000_000L, 1_033_333L, 1_066_666L),
            demuxedPtsUs = listOf(0L, 33_307L, 66_666L),
        )
        val evidence = controlledEvidence().copy(
            controls = controlledEvidence().controls?.copy(offGrid = overBound),
        )

        assertContains(
            containerTimestampBlockingReasons(evidence),
            CONTAINER_TIMESTAMP_BLOCK_OFF_GRID_SPAN_EXCEEDED,
        )
    }

    private fun evidence(submitted: List<Long>, demuxed: List<Long>) = ContainerTimestampEvidence(
        emittedBy = "ContainerTimestampProbeTest",
        environment = environment(),
        provenance = ContainerTimestampProvenance(
            harnessGitSha = "a".repeat(40),
            container = "mp4",
            codec = "video/avc",
        ),
        observation = ContainerTimestampObservation(submitted, demuxed),
    )

    private fun controlledEvidence() = evidence(
        submitted = listOf(1_000_000L, 1_033_300L, 1_066_600L),
        demuxed = listOf(0L, 33_300L, 66_600L),
    ).copy(
        controls = ContainerTimestampControls(
            timebases = SynchronizationTimebases(90_000L, 90_000L),
            onGrid = ContainerTimestampObservation(
                submittedPtsUs = listOf(1_000_000L, 1_033_300L, 1_066_600L),
                demuxedPtsUs = listOf(0L, 33_300L, 66_600L),
            ),
            offGrid = ContainerTimestampObservation(
                submittedPtsUs = listOf(1_000_000L, 1_033_333L, 1_066_666L),
                demuxedPtsUs = listOf(0L, 33_332L, 66_657L),
            ),
        ),
    )

    private fun environment() = ContainerTimestampEnvironment(
        deviceClass = "physical_device",
        deviceIdentity = "samsung/SM-S938B/pa3q/pa3q",
        deviceFingerprint = "samsung/device/fingerprint",
        apiLevel = 36,
        androidRelease = "16",
        abi = "arm64-v8a",
        manufacturer = "samsung",
        model = "SM-S938B",
        marketingName = "Galaxy S25 Ultra",
    )
}
