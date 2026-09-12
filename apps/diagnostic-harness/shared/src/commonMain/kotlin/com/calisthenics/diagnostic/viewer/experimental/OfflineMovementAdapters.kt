package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark

/**
 * A uniform way to drive the offline movement experiments from a live landmark stream.
 *
 * Every one of these engines was developed and measured OFFLINE, on desktop-decoded Creative
 * Commons footage at whatever camera angle, distance and frame rate the uploader happened to choose.
 * Their configs below are the frozen ones that produced the recorded results; not one value here is
 * invented or re-tuned for live use. Each is cited to the file it came from.
 *
 * THE LIMIT THAT MATTERS MOST, stated here because a caller cannot see it from the types: these are
 * FIXED-THRESHOLD engines. The app's own [CalibratedArmCycleExperiment] instead fits a practice
 * range per person before counting. That difference is not cosmetic — the offline thresholds have
 * never been shown to transfer to a different camera, a different athlete or a different frame rate,
 * and the hold thresholds are known NOT to be frame-rate invariant. Treat live output as an
 * unvalidated port until it is measured against a frozen label on this device.
 *
 * Counts are the metric these engines earned. Their event TIMING is weak: on the offline corpus,
 * muscle-up matched 0/3 labelled events at +/-0.25 s, HSPU back-to-wall 1/5, HSPU wall-facing 0/4.
 * A caller should surface counts and durations, and must not present a repetition instant as
 * accurate to better than roughly half a second.
 */
enum class OfflineMovement {
    /**
     * NOTE ON APPARATUS. The frozen config was measured on a STRAIGHT-BAR dip
     * (`XTDc47D1G_I`, "How To Straight Bar Dip"). Ring dips are a different geometry — the rings
     * rotate and the torso carries more lean — so running this on rings is an untested transfer,
     * not a supported configuration. It may well work; nothing says it does.
     */
    DIP,
    HANGING_KNEE_RAISE,
    TOES_TO_BAR,
    /** Variant matters: see [MuscleUpVariant]. Rings and bar have separate frozen configs. */
    MUSCLE_UP,
    HANDSTAND_PUSH_UP,
    HANDSTAND_HOLD,
    BACK_LEVER,
}

enum class OfflineMovementKind { REPETITION, HOLD }

/**
 * What one frame produced. [completedReps] and [completedHoldNs] are cumulative; [rejection] is the
 * engine's own reason enum name when the frame was not usable, and is null when it was.
 */
data class OfflineMovementUpdate(
    val timestampNs: Long,
    val completedReps: Int,
    val activeHoldNs: Long?,
    val completedHoldNs: Long?,
    val phase: String,
    val rejection: String?,
    val countedThisFrame: Boolean,
)

interface OfflineMovementEngine {
    val movement: OfflineMovement
    val kind: OfflineMovementKind
    fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate
}

private fun reasonOrNull(name: String) = if (name == "NONE") null else name

// ---- repetition adapters -----------------------------------------------------------------------

private class DipAdapter(config: DipConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.DIP
    override val kind = OfflineMovementKind.REPETITION
    private val engine = DipCycleExperiment(config)
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(DipPoseFrame(timestampNs, width, height, landmarks))
        return OfflineMovementUpdate(timestampNs, r.count, null, null, r.phase.name, reasonOrNull(r.reason.name), r.counted)
    }
}

private class ToesToBarAdapter(config: ToesToBarConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.TOES_TO_BAR
    override val kind = OfflineMovementKind.REPETITION
    private val engine = ToesToBarExperiment(config)
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(ToesToBarPoseFrame(timestampNs, width, height, landmarks))
        return OfflineMovementUpdate(timestampNs, r.count, null, null, r.phase.name, reasonOrNull(r.reason.name), r.counted)
    }
}

private class HangingLegRaiseAdapter(config: HangingLegRaiseConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.HANGING_KNEE_RAISE
    override val kind = OfflineMovementKind.REPETITION
    private val engine = HangingLegRaiseExperiment(config)
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(HangingLegRaisePoseFrame(timestampNs, width, height, landmarks))
        return OfflineMovementUpdate(timestampNs, r.count, null, null, r.phase.name, reasonOrNull(r.reason.name), r.counted)
    }
}

private class MuscleUpAdapter(config: MuscleUpConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.MUSCLE_UP
    override val kind = OfflineMovementKind.REPETITION
    private val engine = MuscleUpCycleExperiment(config)
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(MuscleUpPoseFrame(timestampNs, width, height, landmarks))
        return OfflineMovementUpdate(timestampNs, r.count, null, null, r.phase.name, reasonOrNull(r.reason.name), false)
    }
}

private class HandstandPushUpAdapter(config: HspuConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.HANDSTAND_PUSH_UP
    override val kind = OfflineMovementKind.REPETITION
    private val engine = HandstandPushUpExperiment(config)
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(HspuPoseFrame(timestampNs, width, height, landmarks))
        return OfflineMovementUpdate(timestampNs, r.count, null, null, r.phase.name, reasonOrNull(r.reason.name), r.counted)
    }
}

// ---- hold adapters -----------------------------------------------------------------------------

private class HandstandHoldAdapter(config: HandstandHoldConfig) : OfflineMovementEngine {
    override val movement = OfflineMovement.HANDSTAND_HOLD
    override val kind = OfflineMovementKind.HOLD
    private val engine = HandstandHoldExperiment(config)
    private var completedNs = 0L
    private var entryNs: Long? = null
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val r = engine.onFrame(HandstandPoseFrame(timestampNs, width, height, landmarks))
        r.completedInterval?.let { completedNs += it.durationNs }
        entryNs = when {
            r.completedInterval != null -> null
            r.holding && entryNs == null -> timestampNs
            !r.holding -> null
            else -> entryNs
        }
        val active = entryNs?.let { timestampNs - it }
        return OfflineMovementUpdate(
            timestampNs, 0, active, completedNs,
            if (r.holding) "HOLDING" else "NOT_HOLDING",
            reasonOrNull(r.reason.name), r.completedInterval != null,
        )
    }
}

/**
 * Back lever. Shape is decided by [admitPosture] and stillness by [StaticSkillObservation], exactly
 * as the offline replay wires them — the two are separate gates and neither is folded into the other.
 */
private class BackLeverAdapter(
    private val admission: PostureAdmissionConfig,
    stillness: StaticSkillConfig,
) : OfflineMovementEngine {
    override val movement = OfflineMovement.BACK_LEVER
    override val kind = OfflineMovementKind.HOLD
    private val engine = StaticSkillObservation(stillness)
    private var completedNs = 0L
    private var entryNs: Long? = null
    override fun onLandmarks(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): OfflineMovementUpdate {
        val frame = StaticSkillPoseFrame(timestampNs, width, height, landmarks)
        val shape = admitPosture(frame, admission)
        val r = engine.onFrame(frame, shape.admitted)
        r.closedInterval?.let { completedNs += it.durationNs }
        val still = r.state == StaticSkillState.STILL
        entryNs = when {
            r.closedInterval != null -> null
            still && entryNs == null -> timestampNs
            !still -> null
            else -> entryNs
        }
        val active = entryNs?.let { timestampNs - it }
        val rejection = if (!shape.admitted) shape.reason.name else reasonOrNull(r.reason.name)
        return OfflineMovementUpdate(
            timestampNs, 0, active, completedNs, r.state.name, rejection, r.closedInterval != null,
        )
    }
}

// ---- frozen configuration ----------------------------------------------------------------------

/**
 * The configurations that produced the recorded offline results, transcribed verbatim.
 *
 * Provenance, per movement, all under `offline-corpus/`:
 *  DIP                `coverage-first-20260909/dips/config-{left,right}-v1.json`
 *  TOES_TO_BAR        `youtube-cc-20260911-derived/wZ6bjrOjbCI-right-t2b-config-v2observable.json`
 *  HANGING_KNEE_RAISE `youtube-cc-20260911-caches/C0RXOvy609I-hlr-config-v1.json`
 *  MUSCLE_UP          `muscle-up-20260911/config-bar-kipping-right-clip-v3.json`
 *  HANDSTAND_PUSH_UP  `hspu-handstand-20260911/config-hspu-back-to-wall-right-v1.json`
 *  HANDSTAND_HOLD     `hspu-handstand-20260911/config-handstand-hold-btUWnzTI8yk-v2-n3.json`
 *  BACK_LEVER         `youtube-cc-20260911-caches/raAQB7ad24s-hold-config-v3-n3b250.json`
 *
 * `side` and `variant` are properties of the RECORDING each config was frozen against, not of the
 * movement, so a live caller facing the other way will need the mirrored value. They are exposed as
 * parameters rather than buried, and the defaults are whatever the frozen file said.
 */
fun offlineMovementEngine(
    movement: OfflineMovement,
    side: ArmSide = defaultSideFor(movement),
    muscleUpVariant: MuscleUpVariant = MuscleUpVariant.BAR_KIPPING,
): OfflineMovementEngine = when (movement) {
    OfflineMovement.DIP -> DipAdapter(
        DipConfig(
            side = side, extendedElbowDegrees = 155.0, flexedElbowDegrees = 100.0,
            minimumShoulderAboveWristFraction = .05, maximumTorsoTiltDegrees = 45.0,
            minimumConfidence = .5, maximumGapNs = 250_000_000L, minimumEndpointSamples = 2,
        ),
    )
    OfflineMovement.TOES_TO_BAR -> ToesToBarAdapter(
        ToesToBarConfig(
            side = side, minimumHandsAboveShoulders = .35, maximumFeetBelowHandsAtBar = .6,
            minimumFeetBelowHandsDown = 1.5, minimumConfidence = .5, maximumGapNs = 250_000_000L,
            minimumEndpointSamples = 2, requireBothAnkles = false,
        ),
    )
    OfflineMovement.HANGING_KNEE_RAISE -> HangingLegRaiseAdapter(
        HangingLegRaiseConfig(
            mode = HangingLegRaiseMode.SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED, side = side,
            loweredHipMinDegrees = 150.0, raisedHipMaxDegrees = 110.0, minimumHipCloseDegrees = 45.0,
            raisedKneeMinDegrees = .0, hangingTrunkMinDegrees = 55.0, minimumSupportRiseRatio = .35,
            maximumSupportTravelRatio = .3, minimumConfidence = .5, maximumGapNs = 100_000_000L,
            minimumEndpointSamples = 3,
        ),
    )
    // Two frozen configs, differing only in grip drift: rings swing, a bar does not.
    //   BAR_KIPPING  muscle-up-20260911/config-bar-kipping-right-clip-v3.json  (drift 1.0)
    //   RING_STRICT  muscle-up-20260911/config-ring-strict-right-v3.json       (drift 2.0)
    OfflineMovement.MUSCLE_UP -> MuscleUpAdapter(
        MuscleUpConfig(
            variant = muscleUpVariant, side = side,
            minimumHangWristAboveShoulderFraction = .05, minimumSupportShoulderAboveWristFraction = .05,
            maximumGripDriftTorsoLengths = if (muscleUpVariant == MuscleUpVariant.RING_STRICT) 2.0 else 1.0,
            minimumBodyScaleRatio = .6, maximumBodyScaleRatio = 1.6,
            minimumConfidence = .5, maximumGapNs = 250_000_000L, minimumEndpointSamples = 2,
            minimumReferenceSamples = 5, maximumReferenceWristSpreadTorsoLengths = .15,
            hangLockoutElbowDegrees = 150.0,
        ),
    )
    OfflineMovement.HANDSTAND_PUSH_UP -> HandstandPushUpAdapter(
        HspuConfig(
            variant = HspuVariant.WALL_BACK_TO_WALL, side = side,
            minimumHipAboveShoulderFraction = .1, minimumReferenceSamples = 15,
            maximumReferenceSpanSpreadRatio = .04, extendedSpanRatio = .8, loweredSpanRatio = .75,
            minimumConfidence = .5, maximumGapNs = 250_000_000L, minimumEndpointSamples = 2,
        ),
    )
    OfflineMovement.HANDSTAND_HOLD -> HandstandHoldAdapter(
        HandstandHoldConfig(
            support = HandstandSupport.WALL_SUPPORTED, side = side,
            minimumHipAboveShoulderFraction = .1, minimumAnkleAboveHipFraction = .05,
            minimumConfidence = .5, maximumGapNs = 250_000_000L, minimumHoldNs = 1_000_000_000L,
            minimumNonHoldingSamplesToClose = 3,
        ),
    )
    OfflineMovement.BACK_LEVER -> BackLeverAdapter(
        PostureAdmissionConfig(
            posture = HoldPosture.HORIZONTAL_BODY, side = side, minimumConfidence = .5,
            straightMinDegrees = .0, straightMaxDegrees = 180.0,
            maximumOrientationDegreesFromHorizontal = 30.0,
            lSitHipMinDegrees = 60.0, lSitHipMaxDegrees = 125.0, lSitKneeMinDegrees = 145.0,
            lSitMaximumLegDegreesFromHorizontal = 30.0, maximumTrunkDegreesFromVertical = 35.0,
            wallSitKneeMinDegrees = 65.0, wallSitKneeMaxDegrees = 120.0,
            wallSitMaximumThighDegreesFromHorizontal = 25.0,
        ),
        StaticSkillConfig(
            minimumConfidence = .5, maximumTorsoPointSpeedTorsoLengthsPerSecond = .3,
            maximumGapNs = 100_000_000L, minimumStillNs = 300_000_000L,
            mode = StaticSkillMode.TORSO_STILL, minimumMovingSamplesToClose = 3,
            motionBaselineNs = 250_000_000L, side = side,
        ),
    )
}

/** The side the frozen config for this movement was measured on. Not a claim about the movement. */
fun defaultSideFor(movement: OfflineMovement): ArmSide = when (movement) {
    OfflineMovement.DIP, OfflineMovement.HANGING_KNEE_RAISE,
    OfflineMovement.MUSCLE_UP, OfflineMovement.HANDSTAND_PUSH_UP -> ArmSide.RIGHT
    OfflineMovement.TOES_TO_BAR, OfflineMovement.HANDSTAND_HOLD, OfflineMovement.BACK_LEVER -> ArmSide.LEFT
}

/**
 * Movements deliberately NOT offered live, with the measured reason. Kept in code rather than only
 * in a document so that adding one requires deleting its reason.
 */
val offlineMovementsWithheld: Map<String, String> = mapOf(
    "L_SIT" to "the L_SIT admission gate has no hip-clearance test and admits ordinary sitting: " +
        "seated frames read hip 89-95 deg, knee 172-180 deg and were all admitted",
    "FRONT_LEVER" to "camera-angle failure, not a threshold: 178/185 rejections inside the labelled " +
        "holds were UNSUPPORTED_ORIENTATION at a median 53.3 deg against a 30 deg gate",
    "HANGING_STRAIGHT_LEG_RAISE" to "closed as a documented 0/6 negative on a front view; the shin " +
        "is foreshortened along the optical axis so projected knee angle never clears the gate",
    "TUCK_PLANCHE" to "admission measures shoulder-to-ankle, which describes the tuck rather than " +
        "the hold; variant also unresolved",
    "PLANCHE" to "labelled non-blind (disclosed); development material only, no blind evidence",
    "WALL_SIT" to "passed on its original clip but FAILED the held-out handheld recording: 5 " +
        "fragments, 53% coverage, exit missed by 3.6 s",
)
