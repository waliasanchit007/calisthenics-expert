package com.calisthenics.diagnostic.viewer

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.*

class MovementMeasurementsTest {
    private fun joint(i: Int, x: Double, y: Double) = Landmark(i, null, x, y, 0.0, .9, .8)
    private fun rightAngle() = listOf(joint(11,.25,.25), joint(13,.25,.5), joint(15,.5,.5))

    @Test fun literalAnglesAndWristHeightAreProjectedNotNormalizedAxisAngles() {
        val measured = measureMovement(rightAngle(), 400, 400, 12)
        assertEquals(90.0, measured.left.elbowDegrees!!, 1e-5)
        assertEquals(-.25, measured.left.wristHeightFraction!!, 1e-5)
        assertEquals(.8, measured.left.minimumConfidence)
        assertEquals(3, measured.availableArmJoints)
        assertNull(measured.right.elbowDegrees)
        val straight = listOf(joint(11,.5,.75),joint(13,.5,.5),joint(15,.5,.25))
        assertEquals(180.0, measureMovement(straight,400,400,13).left.elbowDegrees!!,1e-5)
    }

    @Test fun nonsquareSourceRestoresGeometryBeforeMeasuring() {
        // 400x200 source: model y=.25..75. Pixel points (100,0),(100,100),(200,200).
        val raw = listOf(joint(11,.25,.25),joint(13,.25,.5),joint(15,.5,.75))
        val measured = measureMovement(raw,400,200,0)
        assertEquals(135.0, measured.left.elbowDegrees!!,1e-5)
        assertEquals(-1.0, measured.left.wristHeightFraction!!,1e-5)
    }

    @Test fun missingUncertainAndMalformedJointsCannotProduceAnAngle() {
        val original = rightAngle()
        val bad = listOf(
            original.dropLast(1), original + original.last(),
            original.dropLast(1) + original.last().copy(x=Double.NaN),
            original.dropLast(1) + original.last().copy(visibility=null),
            original.dropLast(1) + original.last().copy(presence=.49),
            original.dropLast(1) + original.last().copy(presence=Double.POSITIVE_INFINITY),
        )
        bad.forEach { assertNull(measureMovement(it,400,400,0).left.elbowDegrees) }
    }

    @Test fun paddingAndZeroLengthLimbsAreUnavailableNotZeroDegrees() {
        val padded = listOf(joint(11,.5,.1),joint(13,.5,.4),joint(15,.75,.5))
        assertNull(measureMovement(padded,400,200,0).left.elbowDegrees)
        val zero = listOf(joint(11,.5,.5),joint(13,.5,.5),joint(15,.75,.5))
        assertNull(measureMovement(zero,400,400,0).left.elbowDegrees)
    }
}
