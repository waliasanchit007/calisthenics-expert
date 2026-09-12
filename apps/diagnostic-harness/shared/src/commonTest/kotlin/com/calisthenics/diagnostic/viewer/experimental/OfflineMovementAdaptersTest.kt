package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapter layer is a wiring seam, so these tests pin wiring, not biomechanics: that every
 * movement is constructible, that a frame reaches the underlying engine and comes back as the
 * engine's own vocabulary, that an unusable frame is REFUSED rather than silently counted, and that
 * the withheld movements stay withheld with a reason attached.
 */
class OfflineMovementAdaptersTest {

    private fun landmark(index: Int, x: Double, y: Double, confidence: Double = .9) =
        Landmark(index = index, name = null, x = x, y = y, z = .0, visibility = confidence, presence = confidence)

    /** A full 33-point skeleton, all landmarks present and confident, standing upright. */
    private fun standing(confidence: Double = .9) = (0..32).map { landmark(it, .5, .5, confidence) }

    @Test
    fun everyOfferedMovementIsConstructibleAndDeclaresItsKind() {
        val expected = mapOf(
            OfflineMovement.DIP to OfflineMovementKind.REPETITION,
            OfflineMovement.TOES_TO_BAR to OfflineMovementKind.REPETITION,
            OfflineMovement.HANGING_KNEE_RAISE to OfflineMovementKind.REPETITION,
            OfflineMovement.MUSCLE_UP to OfflineMovementKind.REPETITION,
            OfflineMovement.HANDSTAND_PUSH_UP to OfflineMovementKind.REPETITION,
            OfflineMovement.HANDSTAND_HOLD to OfflineMovementKind.HOLD,
            OfflineMovement.BACK_LEVER to OfflineMovementKind.HOLD,
        )
        assertEquals(OfflineMovement.entries.toSet(), expected.keys)
        for ((movement, kind) in expected) {
            val engine = offlineMovementEngine(movement)
            assertEquals(movement, engine.movement)
            assertEquals(kind, engine.kind, "$movement declared the wrong kind")
        }
    }

    @Test
    fun everyMovementAcceptsAFrameAndReportsTheEnginesOwnVocabulary() {
        // The seam must not swallow, translate or invent. Whatever the engine says about phase is
        // what comes back, and nothing counts on a single upright frame.
        for (movement in OfflineMovement.entries) {
            val engine = offlineMovementEngine(movement)
            val update = engine.onLandmarks(0L, 720, 1280, standing())
            assertEquals(0L, update.timestampNs)
            assertEquals(0, update.completedReps, "$movement counted a repetition from one frame")
            assertTrue(update.phase.isNotEmpty(), "$movement reported no phase")
            assertEquals(false, update.countedThisFrame, "$movement completed something on frame one")
        }
    }

    @Test
    fun anEmptyFrameIsRefusedByEveryMovementRatherThanCounted() {
        // No pose at all. Refuse rather than infer: a rejection reason must be reported and nothing
        // may advance.
        for (movement in OfflineMovement.entries) {
            val engine = offlineMovementEngine(movement)
            val update = engine.onLandmarks(0L, 720, 1280, emptyList())
            assertNotNull(update.rejection, "$movement accepted a frame with no pose")
            assertEquals(0, update.completedReps)
            assertNull(update.activeHoldNs, "$movement opened a hold with no pose")
        }
    }

    @Test
    fun aLowConfidenceFrameIsRefusedRatherThanMeasured() {
        // Every frozen config sets minimumConfidence to 0.5; 0.1 is below all of them.
        for (movement in OfflineMovement.entries) {
            val engine = offlineMovementEngine(movement)
            val update = engine.onLandmarks(0L, 720, 1280, standing(confidence = .1))
            assertNotNull(update.rejection, "$movement measured a low-confidence frame")
        }
    }

    @Test
    fun holdsReportCumulativeDurationAndRepetitionsDoNot() {
        for (movement in OfflineMovement.entries) {
            val engine = offlineMovementEngine(movement)
            val update = engine.onLandmarks(0L, 720, 1280, standing())
            when (engine.kind) {
                OfflineMovementKind.HOLD ->
                    assertNotNull(update.completedHoldNs, "$movement is a hold but reports no duration")
                OfflineMovementKind.REPETITION ->
                    assertNull(update.completedHoldNs, "$movement is a repetition but reports a duration")
            }
        }
    }

    @Test
    fun aTimestampThatGoesBackwardsIsRefusedRatherThanReordered() {
        for (movement in OfflineMovement.entries) {
            val engine = offlineMovementEngine(movement)
            engine.onLandmarks(1_000_000_000L, 720, 1280, standing())
            val backwards = engine.onLandmarks(500_000_000L, 720, 1280, standing())
            assertNotNull(backwards.rejection, "$movement accepted a backwards timestamp")
            assertEquals(0, backwards.completedReps)
        }
    }

    @Test
    fun sideIsAChoiceOfTheRecordingNotOfTheMovement() {
        // The default is whatever side the frozen config was measured on, and it must be
        // overridable, because a live athlete may simply be facing the other way.
        assertEquals(ArmSide.RIGHT, defaultSideFor(OfflineMovement.DIP))
        assertEquals(ArmSide.LEFT, defaultSideFor(OfflineMovement.TOES_TO_BAR))
        for (movement in OfflineMovement.entries) {
            for (side in ArmSide.entries) {
                val engine = offlineMovementEngine(movement, side)
                assertEquals(movement, engine.movement)
            }
        }
    }

    @Test
    fun theWithheldMovementsStayWithheldAndEachCarriesItsMeasuredReason() {
        // Adding one of these to OfflineMovement should require deleting its reason here, which is
        // the point: a movement cannot be quietly promoted past the evidence that withheld it.
        val withheld = setOf(
            "L_SIT", "FRONT_LEVER", "HANGING_STRAIGHT_LEG_RAISE",
            "TUCK_PLANCHE", "PLANCHE", "WALL_SIT",
        )
        assertEquals(withheld, offlineMovementsWithheld.keys)
        val offered = OfflineMovement.entries.map { it.name }.toSet()
        for (name in withheld) {
            assertTrue(name !in offered, "$name is both offered and withheld")
            val reason = assertNotNull(offlineMovementsWithheld[name])
            assertTrue(reason.length > 40, "$name is withheld without a substantive reason")
        }
    }
}
