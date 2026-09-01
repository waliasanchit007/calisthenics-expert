package com.calisthenics.diagnostic.study

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 4 synchronization evidence.
 *
 * The admission authority for synchronization is the released Python validator; these tests prove
 * the producing side populates evidence that satisfies it, and that a run which cannot satisfy it
 * is blocked here rather than shipped as admissible.
 */
class SynchronizationEvidenceTest {

    @Test
    fun aWellFormedRunDerivesOneConstantOffsetAndBlocksNothing() {
        val evidence = evidence()

        assertEquals(emptyList(), synchronizationBlockingReasons(evidence))
        // Probe origin was non-zero; a live run rebased at t0=0 legitimately yields a zero offset.
        assertEquals(0L, deriveSynchronizationOffsetNs(evidence.samples))
    }

    @Test
    fun aConstantNonZeroOffsetIsAdmittedAndReportedInNanoseconds() {
        // The muxer normalises the first sample to zero, so a run whose first frame is not at
        // t0 carries a constant positive offset. Demuxed values stay non-negative, as the
        // released schema requires.
        val samples = listOf(
            sample(0, 1_000_000L, 1_000L, demuxed = 0L),
            sample(1, 34_333_000L, 34_333L, demuxed = 33_333L),
            sample(2, 67_666_000L, 67_666L, demuxed = 66_666L),
        )
        val evidence = evidence(samples = samples, videoPtsUs = samples.map { it.demuxedPtsUs })

        assertEquals(emptyList(), synchronizationBlockingReasons(evidence))
        assertEquals(1_000_000L, deriveSynchronizationOffsetNs(evidence.samples))
    }

    @Test
    fun legacyExactProfileBlocksAnyMidSequenceVariationEvenThoughTheEndpointsAgree() {
        // The drift is strictly interior: the first and last samples both carry offset 0, so an
        // endpoint-only derivation would report a constant transform and admit this run. Only a
        // derivation over every sample can see it. A mutation to endpoint-only comparison must
        // fail here.
        val samples = listOf(
            sample(0, 0L, 0L, demuxed = 0L),
            sample(1, 33_333_000L, 33_333L, demuxed = 33_332L),
            sample(2, 66_666_000L, 66_666L, demuxed = 66_666L),
        )
        val evidence = evidence(samples = samples, videoPtsUs = samples.map { it.demuxedPtsUs })

        assertEquals(
            0L,
            samples.first().submittedPtsUs - samples.first().demuxedPtsUs,
            "test setup: first endpoint must carry offset 0",
        )
        assertEquals(
            0L,
            samples.last().submittedPtsUs - samples.last().demuxedPtsUs,
            "test setup: last endpoint must carry offset 0",
        )
        assertNull(deriveSynchronizationOffsetNs(samples))
        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_NON_CONSTANT_OFFSET)
    }

    @Test
    fun boundedProfileAcceptsASubTickInteriorVariationDerivedFromItsTimebase() {
        val timebase = SynchronizationTimebases(90_000L, 90_000L)
        val subTickUs = (1_000_000L / timebase.videoTicksPerSecond) - 1L
        val samples = listOf(
            sample(0, 0L, 0L, demuxed = 0L),
            sample(1, 33_333_000L, 33_333L, demuxed = 33_333L - subTickUs),
            sample(2, 66_666_000L, 66_666L, demuxed = 66_666L),
        )
        val evidence = evidence(
            samples = samples,
            videoPtsUs = samples.map { it.demuxedPtsUs },
            profile = SynchronizationProfile.BOUNDED_QUANTIZATION,
            timebases = timebase,
        )

        assertEquals(emptyList(), synchronizationBlockingReasons(evidence))
    }

    @Test
    fun boundedProfileBlocksTheFirstIntegralMicrosecondVariationAboveItsDerivedBound() {
        val timebase = SynchronizationTimebases(90_000L, 90_000L)
        val boundNs = assertNotNull(deriveQuantizationSpanBoundNs(timebase))
        val overBoundUs = (boundNs / 1_000L) + 1L
        val samples = listOf(
            sample(0, 0L, 0L, demuxed = 0L),
            sample(1, 33_333_000L, 33_333L, demuxed = 33_333L - overBoundUs),
            sample(2, 66_666_000L, 66_666L, demuxed = 66_666L),
        )
        val evidence = evidence(
            samples = samples,
            videoPtsUs = samples.map { it.demuxedPtsUs },
            profile = SynchronizationProfile.BOUNDED_QUANTIZATION,
            timebases = timebase,
        )

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_NON_CONSTANT_OFFSET)
    }

    @Test
    fun boundedProfileFailsClosedWithoutOnePositiveCommonTimebase() {
        val evidence = evidence(
            profile = SynchronizationProfile.BOUNDED_QUANTIZATION,
            timebases = SynchronizationTimebases(90_000L, 1_000L),
        )

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_TIMEBASE_INVALID)
    }

    @Test
    fun submittedPtsNotDerivedFromRebasedCaptureIsBlocked() {
        val samples = listOf(sample(0, 33_333_999L, submitted = 33_334L, demuxed = 33_334L))
        val evidence = evidence(samples = samples, videoPtsUs = listOf(33_334L))

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_PTS_DERIVATION)
    }

    @Test
    fun videoPtsThatDisagreeWithTheDemuxedSequenceAreBlocked() {
        val samples = defaultSamples()
        val evidence = evidence(samples = samples, videoPtsUs = listOf(0L, 33_333L))

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_VIDEO_PTS_MISMATCH)
    }

    @Test
    fun aSampleCountThatDisagreesWithEncodedFramesIsBlocked() {
        val evidence = evidence(counts = defaultCounts().copy(encodedVideoFrames = 2))

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_COUNT_MISMATCH)
    }

    @Test
    fun deliveredCallbacksMustReconcileWithEveryVideoDisposition() {
        val evidence = evidence(counts = defaultCounts().copy(deliveredCameraCallbacks = 4))

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_CALLBACK_ACCOUNTING)
    }

    @Test
    fun callbacksRejectedBeforeTheWriterAreIncludedInDroppedBeforeEncoder() {
        assertEquals(
            2,
            deriveTotalDroppedBeforeEncoder(
                deliveredCameraCallbacks = 5,
                acceptedByVideoWriter = 4,
                writerDroppedBeforeEncoder = 1,
            ),
        )
        assertNull(
            deriveTotalDroppedBeforeEncoder(
                deliveredCameraCallbacks = 3,
                acceptedByVideoWriter = 4,
                writerDroppedBeforeEncoder = 0,
            ),
        )
    }

    @Test
    fun duplicateSubmittedPtsIsBlocked() {
        val samples = listOf(
            sample(0, 33_333_000L, 33_333L, demuxed = 33_333L),
            sample(1, 33_333_400L, 33_333L, demuxed = 33_333L),
        )
        val evidence = evidence(
            samples = samples,
            videoPtsUs = samples.map { it.demuxedPtsUs },
            counts = defaultCounts().copy(encodedVideoFrames = 2),
        )

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_DUPLICATE_PTS)
    }

    @Test
    fun anEmptySampleSequenceIsBlocked() {
        val evidence = evidence(
            samples = emptyList(),
            videoPtsUs = emptyList(),
            counts = defaultCounts().copy(encodedVideoFrames = 0),
        )

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_NO_SAMPLES)
    }

    @Test
    fun aCodecDifferentFromTheAdmittedProbeIsBlocked() {
        // The probe's constant-offset conclusion was measured on one specific encoder. Inheriting
        // it under a different codec is unsupported; a new probe is required.
        val evidence = evidence(codec = "c2.android.avc.encoder")

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_PROBE_CODEC_MISMATCH)
    }

    @Test
    fun aContainerDifferentFromTheAdmittedProbeIsBlocked() {
        val evidence = evidence(container = "video/webm")

        assertContains(synchronizationBlockingReasons(evidence), SYNC_BLOCK_PROBE_CONTAINER_MISMATCH)
    }

    @Test
    fun renderedEvidencePinsTheAdmittedMappingAndZeroDrift() {
        val json = renderSynchronizationEvidence(evidence())

        assertContains(json, "\"mapping_method\":\"$SYNCHRONIZATION_MAPPING_METHOD\"")
        assertContains(json, "\"container_drift_ns_per_s\":0")
        assertContains(json, "\"container_offset_ns\":0")
        assertContains(json, "\"blocking_result\":false")
    }

    @Test
    fun renderedBoundedEvidenceCarriesTheDerivedProfileTimebasesIntervalAndRepresentative() {
        val samples = listOf(
            sample(0, 1_000_000_000L, 1_000_000L, demuxed = 0L),
            sample(1, 1_033_333_000L, 1_033_333L, demuxed = 33_332L),
            sample(2, 1_066_666_000L, 1_066_666L, demuxed = 66_657L),
        )
        val json = renderSynchronizationEvidence(
            evidence(
                samples = samples,
                videoPtsUs = samples.map { it.demuxedPtsUs },
                profile = SynchronizationProfile.BOUNDED_QUANTIZATION,
                timebases = SynchronizationTimebases(90_000L, 90_000L),
            ),
        )

        assertContains(json, "\"synchronization_evidence_schema_version\":\"1.1.0\"")
        assertContains(json, "\"sync_profile\":\"bounded_quantization\"")
        assertContains(json, "\"mapping_method\":\"camera_capture_to_container_pts_bounded_quantization_v1\"")
        assertContains(json, "\"video_ticks_per_second\":90000")
        assertContains(json, "\"metadata_ticks_per_second\":90000")
        assertContains(json, "\"minimum\":1000000000")
        assertContains(json, "\"maximum\":1000009000")
        assertContains(json, "\"container_offset_ns\":1000001000")
        assertContains(json, "\"container_quantization_span_bound_ns\":24223")
    }

    @Test
    fun renderedEvidenceCarriesEverySampleNotJustTheEndpoints() {
        val json = renderSynchronizationEvidence(evidence())

        assertContains(json, "\"rebased_capture_timestamp_ns\":33333000")
        assertTrue(
            json.split("\"frame_index\"").size - 1 == 3,
            "every sample must be rendered, not only the endpoints",
        )
    }

    @Test
    fun renderedEncodedEndpointsUseTheDemuxedContainerDomain() {
        val samples = listOf(
            sample(0, 1_000_000_000L, 1_000_000L, demuxed = 0L),
            sample(1, 1_033_333_000L, 1_033_333L, demuxed = 33_333L),
        )
        val json = renderSynchronizationEvidence(
            evidence(
                samples = samples,
                videoPtsUs = samples.map { it.demuxedPtsUs },
                counts = defaultCounts().copy(
                    deliveredCameraCallbacks = 2,
                    encodedVideoFrames = 2,
                    submittedInferenceFrames = 2,
                    emittedPoseRecords = 2,
                ),
            ),
        )

        assertContains(json, "\"first_encoded_frame_pts_us\":0")
        assertContains(json, "\"last_encoded_frame_pts_us\":33333")
    }

    @Test
    fun blockedEvidenceStillRendersAndReportsItsReasons() {
        val json = renderSynchronizationEvidence(evidence(codec = "c2.android.avc.encoder"))

        assertContains(json, SYNC_BLOCK_PROBE_CODEC_MISMATCH)
        assertContains(json, "\"blocking_result\":true")
    }

    // --- fixtures ----------------------------------------------------------------------------

    private companion object {
        const val PROBE_CODEC = "c2.qti.avc.encoder"
        const val PROBE_CONTAINER = "video/mp4"

        fun sample(frameIndex: Long, rebasedNs: Long, submitted: Long, demuxed: Long) =
            SynchronizationSample(
                frameIndex = frameIndex,
                rebasedCaptureTimestampNs = rebasedNs,
                submittedPtsUs = submitted,
                demuxedPtsUs = demuxed,
            )

        fun defaultSamples() = listOf(
            sample(0, 0L, 0L, demuxed = 0L),
            sample(1, 33_333_000L, 33_333L, demuxed = 33_333L),
            sample(2, 66_666_000L, 66_666L, demuxed = 66_666L),
        )

        fun defaultCounts() = SynchronizationCounts(
            deliveredCameraCallbacks = 3,
            encodedVideoFrames = 3,
            submittedInferenceFrames = 3,
            emittedPoseRecords = 3,
            droppedBeforeEncoder = 0,
            encoderFailures = 0,
            inferenceSkippedFrames = 0,
            inferenceFailures = 0,
        )

        fun evidence(
            samples: List<SynchronizationSample> = defaultSamples(),
            videoPtsUs: List<Long> = samples.map { it.demuxedPtsUs },
            counts: SynchronizationCounts = defaultCounts(),
            codec: String = PROBE_CODEC,
            container: String = PROBE_CONTAINER,
            profile: SynchronizationProfile = SynchronizationProfile.EXACT,
            timebases: SynchronizationTimebases? = null,
        ) = SynchronizationEvidence(
            sessionId = "00000000-0000-4000-8000-000000000001",
            manifestSessionId = "00000000-0000-4000-8000-000000000001",
            artifacts = SynchronizationArtifacts(
                videoRole = "session_video",
                videoPathRef = "video.mp4",
                videoSha256 = "a".repeat(64),
                videoByteLength = 1024L,
                rawPoseStreamPathRef = "frames.ndjson",
                rawPoseStreamSha256 = "b".repeat(64),
            ),
            container = container,
            codec = codec,
            cameraTimestampDomain = "REALTIME",
            encoderTimestampDomain = "container_pts_us",
            samples = samples,
            videoPtsUs = videoPtsUs,
            counts = counts,
            firstCameraFrameTimestampNs = 1_000_000_000L,
            lastCameraFrameTimestampNs = 1_066_666_000L,
            firstPoseCaptureTimestampNs = 1_000_000_000L,
            lastPoseCaptureTimestampNs = 1_066_666_000L,
            producerGitSha = "c".repeat(40),
            deviceModel = "SM-S938B",
            deviceApiLevel = 36,
            deviceAbi = "arm64-v8a",
            probeContainer = PROBE_CONTAINER,
            probeCodec = PROBE_CODEC,
            profile = profile,
            timebases = timebases,
        )
    }
}
