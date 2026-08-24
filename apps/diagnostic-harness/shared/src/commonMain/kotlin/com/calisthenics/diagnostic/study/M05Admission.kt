package com.calisthenics.diagnostic.study

enum class M05InputClass(val wireValue: String) {
    EMPTY_SCENE("empty_scene"),
    DISPLAYED_SYNTHETIC_FIGURE("displayed_synthetic_figure"),
}

enum class M05DetectionEvidenceState(val wireValue: String) {
    NOT_APPLICABLE("not_applicable"),
    ACTIVE("active"),
    PENDING("pending"),
}

data class M05AdmissionEvidence(
    val sessionId: String,
    val sessionKind: String,
    val inputClass: M05InputClass,
    val operatorAttestedNoPerson: Boolean,
    val submittedFrameCount: Long,
    val detectionActiveFrameCount: Long,
) {
    init {
        require(submittedFrameCount >= 0L) { "submitted frame count must be non-negative" }
        require(detectionActiveFrameCount >= 0L) { "detection-active frame count must be non-negative" }
    }
}

data class M05AdmissionResult(
    val sessionKind: String,
    val participantPseudonymId: String,
    val inputClass: M05InputClass,
    val operatorAttestedNoPerson: Boolean,
    val detectionActiveFrameFraction: Double?,
    val detectionEvidenceState: M05DetectionEvidenceState,
    val blockingCodes: List<String>,
)

object M05Admission {
    private const val LIVE_CONFORMANCE = "live_conformance"
    private const val PSEUDONYM_PREFIX = "synthetic-m05-"

    fun evaluate(evidence: M05AdmissionEvidence): M05AdmissionResult {
        val fraction = if (evidence.submittedFrameCount == 0L) {
            null
        } else {
            evidence.detectionActiveFrameCount.toDouble() / evidence.submittedFrameCount.toDouble()
        }
        val blockingCodes = mutableListOf<String>()
        if (evidence.sessionKind != LIVE_CONFORMANCE) blockingCodes += "STUDY.ADMISSION_SESSION_KIND"
        if (!evidence.operatorAttestedNoPerson || evidence.detectionActiveFrameCount > evidence.submittedFrameCount) {
            blockingCodes += "STUDY.ADMISSION_INPUT_CLASS"
        }
        val evidenceState = when (evidence.inputClass) {
            M05InputClass.EMPTY_SCENE -> {
                if (fraction == null || fraction != 0.0) blockingCodes += "STUDY.EMPTY_SCENE_DETECTION"
                M05DetectionEvidenceState.NOT_APPLICABLE
            }
            M05InputClass.DISPLAYED_SYNTHETIC_FIGURE -> {
                if (fraction == null || fraction == 0.0) {
                    blockingCodes += "STUDY.SYNTHETIC_DETECTION_PENDING"
                    M05DetectionEvidenceState.PENDING
                } else {
                    M05DetectionEvidenceState.ACTIVE
                }
            }
        }
        return M05AdmissionResult(
            sessionKind = LIVE_CONFORMANCE,
            participantPseudonymId = "$PSEUDONYM_PREFIX${evidence.sessionId}",
            inputClass = evidence.inputClass,
            operatorAttestedNoPerson = true,
            detectionActiveFrameFraction = fraction,
            detectionEvidenceState = evidenceState,
            blockingCodes = blockingCodes.distinct(),
        )
    }
}
