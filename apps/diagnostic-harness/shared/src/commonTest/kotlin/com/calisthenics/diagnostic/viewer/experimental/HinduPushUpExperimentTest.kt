package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HinduPushUpExperimentTest {
    private val config = HinduConfig(ArmSide.LEFT, .5, 250_000_000L, 2, .03, 150.0, 150.0)

    @Test fun countsOneFullHighLowHighCycleNotItsInitialPosition() {
        val engine = HinduPushUpExperiment(config)
        val results = listOf(.15, .15, .4, .6, .6, .4, .15, .15).mapIndexed { i, hip ->
            engine.onFrame(frame(i * 100_000_000L, hip))
        }
        assertEquals(listOf(0,0,0,0,0,0,0,1), results.map { it.count })
        assertEquals(listOf(false,false,false,false,false,false,false,true), results.map { it.counted })
    }

    @Test fun pikeHoldAndPikeElbowBendingNeverCountAsDand() {
        val engine = HinduPushUpExperiment(config)
        repeat(12) { i ->
            val f = frame(i * 100_000_000L, .15)
            val points = if (i in 3..7) f.landmarks.map { if(it.index == 13) it.copy(x=.5) else it } else f.landmarks
            assertFalse(engine.onFrame(f.copy(landmarks=points)).counted)
        }
        assertEquals(0, engine.count)
    }

    @Test fun missingJointAfterArmingCannotBridgeAnUnobservedCycle() {
        val engine = HinduPushUpExperiment(config)
        listOf(.15,.15,.4,.6).forEachIndexed { i,h -> engine.onFrame(frame(i*100_000_000L,h)) }
        val missing = engine.onFrame(frame(400_000_000L,.6).copy(landmarks=emptyList()))
        assertEquals("UNAVAILABLE", missing.reason)
        listOf(.6,.4,.15,.15).forEachIndexed { i,h -> engine.onFrame(frame((i+5)*100_000_000L,h)) }
        assertEquals(0, engine.count)
    }

    @Test fun timeGapOrRepeatedTimestampInvalidatesTheArmedSequence() {
        for (badTime in listOf(200_000_000L, 600_000_000L)) {
            val engine = HinduPushUpExperiment(config)
            listOf(.15,.15,.4).forEachIndexed { i,h -> engine.onFrame(frame(i*100_000_000L,h)) }
            assertFalse(engine.onFrame(frame(badTime,.6)).counted)
            listOf(.6,.4,.15,.15).forEachIndexed { i,h -> engine.onFrame(frame((i+7)*100_000_000L,h)) }
            assertEquals(0,engine.count)
        }
    }

    @Test fun untrustedConfidenceAndBentKneeCannotArmTheCounter() {
        for (bad in listOf(Double.NaN, 1.1, .1)) {
            val engine = HinduPushUpExperiment(config)
            assertEquals("UNAVAILABLE",engine.onFrame(frame(0,.15).let { f ->
                f.copy(landmarks=f.landmarks.map { it.copy(visibility=bad) })
            }).reason)
        }
        val engine = HinduPushUpExperiment(config)
        assertEquals("KNEE_FLEXED", engine.onFrame(frame(0,.15).let { f ->
            f.copy(landmarks=f.landmarks.map { if(it.index==25) it.copy(x=.9,y=.15) else it })
        }).reason)
    }

    private fun frame(time:Long, hipY:Double): HinduPoseFrame {
        val points=listOf(11 to (.3 to .4),13 to (.3 to .6),15 to (.3 to .8),
            23 to (.55 to hipY),25 to (.7 to ((hipY+.8)/2)),27 to (.85 to .8))
        return HinduPoseFrame(time,500,500,points.map { (i,p) -> Landmark(i,null,p.first,p.second,0.0,.9,.9) })
    }
}
