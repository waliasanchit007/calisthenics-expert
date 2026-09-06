package com.calisthenics.diagnostic.viewer.experimental

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayMovementTraceMappingTest {
    @Test fun optionalJointDiagnosticsReachReplayWithoutInventingAnElbowFailure() {
        val start=row(event="CALIBRATE",side="LEFT",decisionNs=0)
        val base=Json.parseToJsonElement(row(seq=1,feed="FRESH",captureNs=5_000_000_000,
            decisionNs=5_000_000_000)).jsonObject
        val input=kotlinx.serialization.json.JsonObject(base + mapOf(
            "left_unavailable_joint" to kotlinx.serialization.json.JsonPrimitive("WRIST"),
            "left_unavailable_reason" to kotlinx.serialization.json.JsonPrimitive("OUT_OF_FRAME"),
        )).toString()
        val result=ReplayMovementTrace.replay(ReplayTraceParser.parse(sequenceOf(start,input)))
        assertEquals(ExperimentInputIssue.WRIST_UNAVAILABLE,result.finalIssue)
        kotlin.test.assertTrue(result.finalState.message.contains("outside"),result.finalState.message)
    }
    @Test
    fun controlEventsAreDispatchedBeforeFeedAndClearExperimentState() {
        val rows = listOf(
            row(event = "CALIBRATE", feed = "DUPLICATE", side = "RIGHT", decisionNs = 0),
            row(seq = 1, event = "SAMPLE", feed = "FRESH", captureNs = 5_000_000_000, decisionNs = 5_000_000_000, leftElbow = 160.0),
            row(seq = 2, event = "START_SYNTHETIC", feed = "FRESH"),
            row(seq = 3, event = "CALIBRATE", feed = "WAITING", side = "LEFT", decisionNs = 0),
            row(seq = 4, event = "RESET", feed = "DUPLICATE"),
            row(seq = 5, event = "CALIBRATE", feed = "WAITING", side = "RIGHT", decisionNs = 0),
            row(seq = 6, event = "STOP", feed = "FRESH"),
        )

        val result = ReplayMovementTrace.replay(ReplayTraceParser.parse(rows.asSequence()))

        assertEquals(ExperimentStage.IDLE, result.finalState.stage)
        assertEquals(0, result.finalState.candidateCycles)
        assertNull(result.finalState.range)
        assertEquals(6, result.transitions.size)
        assertEquals(ExperimentStage.COUNTDOWN, result.transitions[0].stage)
        assertEquals(ExperimentStage.IDLE, result.transitions.last().stage)
    }

    @Test
    fun freshSamplesMapToMeasurementsAndTrackingFeedsMapToGaps() {
        val rows = listOf(
            row(event = "CALIBRATE", feed = "WAITING", side = "LEFT", decisionNs = 0),
            row(seq = 1, event = "SAMPLE", feed = "FRESH", captureNs = 5_000_000_000, decisionNs = 5_000_000_000, leftElbow = 160.0, leftWrist = 0.4, leftConfidence = 0.9),
            row(seq = 2, event = "SAMPLE", feed = "WAITING"),
            row(seq = 3, event = "SAMPLE", feed = "DUPLICATE"),
            row(seq = 4, event = "SAMPLE", feed = "NO_POSE"),
        )

        val result = ReplayMovementTrace.replay(ReplayTraceParser.parse(rows.asSequence()))

        assertEquals(ExperimentStage.CALIBRATING, result.finalState.stage)
        assertEquals(CyclePhase.UNCERTAIN, result.finalState.phase)
        assertNull(result.finalState.calibrationEndsNs)
        assertNull(result.finalState.failure)
        assertEquals(ExperimentInputIssue.TRACKING_GAP, result.finalIssue)
        assertEquals(ExperimentStage.COUNTDOWN, result.transitions[0].stage)
        assertEquals(ExperimentStage.CALIBRATING, result.transitions[1].stage)
        assertEquals(CyclePhase.UNCERTAIN, result.transitions.last().phase)
    }

    @Test fun backwardsFeedIsNotReclassifiedAsRecoverableTrackingLoss() {
        val rows = listOf(
            row(event = "CALIBRATE", side = "LEFT", decisionNs = 0),
            row(seq = 1, feed = "FRESH", captureNs = 5_000_000_000, decisionNs = 5_000_000_000,
                leftElbow = 160.0, leftWrist = .4, leftConfidence = .9),
            row(seq = 2, feed = "BACKWARDS"),
        )
        val result = ReplayMovementTrace.replay(ReplayTraceParser.parse(rows.asSequence()))
        assertEquals(ExperimentStage.ERROR, result.finalState.stage)
        assertEquals(CalibrationFailure.TIMESTAMP_REVERSED, result.finalState.failure)
        assertEquals(ExperimentInputIssue.BACKWARDS_TIMESTAMP, result.finalIssue)
    }

    @Test
    fun recordedStateAndCountFieldsNeverDriveReplay() {
        val result = ReplayMovementTrace.replay(
            ReplayTraceParser.parse(
                sequenceOf(
                    row(event = "CALIBRATE", feed = "WAITING", side = "LEFT", decisionNs = 0),
                ),
            ),
        )

        assertEquals(ExperimentStage.COUNTDOWN, result.finalState.stage)
        assertEquals(CyclePhase.WAITING, result.finalState.phase)
        assertEquals(0, result.finalState.candidateCycles)
    }

    @Test
    fun renderJsonContainsOnlyConciseTransitionsCountsAndCalibrationStatus() {
        val result = ReplayMovementTrace.replay(
            ReplayTraceParser.parse(sequenceOf(row(event = "CALIBRATE", feed = "WAITING", side = "RIGHT", decisionNs = 0))),
        )

        val document = Json.parseToJsonElement(ReplayMovementTrace.renderJson(result)).jsonObject
        val transition = document.getValue("events").jsonArray.single().jsonObject
        assertEquals(0, transition.getValue("seq").jsonPrimitive.int)
        assertEquals("CALIBRATE", transition.getValue("event").jsonPrimitive.content)
        assertEquals("COUNTDOWN", transition.getValue("stage").jsonPrimitive.content)
        assertEquals(0, document.getValue("counts").jsonObject.getValue("candidate_cycles").jsonPrimitive.int)
        assertEquals("COUNTDOWN", document.getValue("calibration").jsonObject.getValue("stage").jsonPrimitive.content)
        assertEquals("RIGHT", document.getValue("calibration").jsonObject.getValue("side").jsonPrimitive.content)
    }

    private fun row(
        seq: Long = 0,
        event: String = "SAMPLE",
        feed: String = "WAITING",
        captureNs: Long? = null,
        decisionNs: Long = 100,
        side: String? = "LEFT",
        leftElbow: Double? = null,
        leftWrist: Double? = null,
        leftConfidence: Double? = null,
    ): String = """
        {
          "v":2,"session":"synthetic-session","seq":$seq,"angle_quantum_deg":0.0,"height_quantum":0.0,
          "event":"$event","feed":"$feed","capture_ns":${captureNs ?: "null"},"decision_ns":$decisionNs,
          "side":${side?.let { "\"$it\"" } ?: "null"},
          "left_elbow_deg":${leftElbow ?: "null"},"right_elbow_deg":null,
          "left_wrist_height":${leftWrist ?: "null"},"right_wrist_height":null,
          "left_confidence":${leftConfidence ?: "null"},"right_confidence":null,
          "available_arm_joints":3,"stage":"COUNTING","phase":"FLEXED","candidate_cycles":77
        }
    """.trimIndent()
}
