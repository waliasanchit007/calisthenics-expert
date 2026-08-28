package com.calisthenics.diagnostic.study

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class M05AdmissionTest {
    private val reservedSessionId = "00000000-0000-4000-8000-000000000001"
    @Test
    fun emptySceneDerivesReservedIdentityAndZeroDetectionWithoutAComputedPlaceholder() {
        val result = M05Admission.evaluate(
            M05AdmissionEvidence(
                sessionId = "00000000-0000-4000-8000-000000000001",
                sessionKind = "live_conformance",
                inputClass = M05InputClass.EMPTY_SCENE,
                operatorAttestedNoPerson = true,
                submittedFrameCount = 10,
                detectionActiveFrameCount = 0,
            ),
        )

        assertEquals("synthetic-m05-00000000-0000-4000-8000-000000000001", result.participantPseudonymId)
        assertEquals(0.0, result.detectionActiveFrameFraction)
        assertEquals(M05DetectionEvidenceState.NOT_APPLICABLE, result.detectionEvidenceState)
        assertTrue(result.blockingCodes.isEmpty())
    }

    @Test
    fun policyInadmissibilityReturnsTypedBlockingCodesAndRetainsUnmeasuredEvidence() {
        val mutations = listOf(
            M05AdmissionEvidence(reservedSessionId, "live_study", M05InputClass.EMPTY_SCENE, true, 10, 0) to "STUDY.ADMISSION_SESSION_KIND",
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.EMPTY_SCENE, false, 10, 0) to "STUDY.ADMISSION_INPUT_CLASS",
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.EMPTY_SCENE, true, 10, 11) to "STUDY.ADMISSION_INPUT_CLASS",
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.EMPTY_SCENE, true, 10, 1) to "STUDY.EMPTY_SCENE_DETECTION",
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.DISPLAYED_SYNTHETIC_FIGURE, true, 10, 0) to "STUDY.SYNTHETIC_DETECTION_PENDING",
        )

        mutations.forEach { (evidence, code) ->
            val result = M05Admission.evaluate(evidence)
            assertTrue(code in result.blockingCodes)
            assertEquals(evidence.sessionKind, result.sessionKind)
            assertEquals(evidence.operatorAttestedNoPerson, result.operatorAttestedNoPerson)
        }
        val unmeasured = M05Admission.evaluate(
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.DISPLAYED_SYNTHETIC_FIGURE, true, 0, 0),
        )
        assertEquals(null, unmeasured.detectionActiveFrameFraction)
        assertEquals(M05DetectionEvidenceState.PENDING, unmeasured.detectionEvidenceState)
        assertTrue("STUDY.SYNTHETIC_DETECTION_PENDING" in unmeasured.blockingCodes)

        val emptyUnmeasured = M05Admission.evaluate(
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.EMPTY_SCENE, true, 0, 0),
        )
        assertEquals(null, emptyUnmeasured.detectionActiveFrameFraction)
        assertEquals(M05DetectionEvidenceState.NOT_APPLICABLE, emptyUnmeasured.detectionEvidenceState)
        assertTrue("STUDY.EMPTY_SCENE_DETECTION" in emptyUnmeasured.blockingCodes)
    }

    @Test
    fun displayedSyntheticFigureActivatesOnlyWithObservedDetection() {
        val active = M05Admission.evaluate(
            M05AdmissionEvidence(reservedSessionId, "live_conformance", M05InputClass.DISPLAYED_SYNTHETIC_FIGURE, true, 4, 1),
        )

        assertEquals(0.25, active.detectionActiveFrameFraction)
        assertEquals(M05DetectionEvidenceState.ACTIVE, active.detectionEvidenceState)
        assertTrue(active.blockingCodes.isEmpty())
    }

    @Test
    fun nonReservedSessionIdsAreBlockedBeforeCaptureAdmission() {
        val result = M05Admission.evaluate(
            M05AdmissionEvidence("10000000-0000-4000-8000-000000000001", "live_conformance", M05InputClass.EMPTY_SCENE, true, 1, 0),
        )

        assertTrue("STUDY.ADMISSION_SESSION_ID" in result.blockingCodes)
    }

    @Test
    fun impossibleNegativeSourceCountsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            M05AdmissionEvidence("id", "live_conformance", M05InputClass.EMPTY_SCENE, true, -1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            M05AdmissionEvidence("id", "live_conformance", M05InputClass.EMPTY_SCENE, true, 1, -1)
        }
    }
}
