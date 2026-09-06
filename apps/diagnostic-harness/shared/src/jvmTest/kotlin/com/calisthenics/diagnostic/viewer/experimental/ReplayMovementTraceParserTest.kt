package com.calisthenics.diagnostic.viewer.experimental

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReplayMovementTraceParserTest {
    @Test
    fun parsesExactV2NumericRowsAndPreservesNullableMeasurements() {
        val rows = ReplayTraceParser.parse(
            sequenceOf(
                row(event = "START_CAMERA", feed = "WAITING"),
                row(
                    seq = 1,
                    event = "SAMPLE",
                    feed = "FRESH",
                    captureNs = 10_000_000,
                    leftElbow = 160.25,
                    leftWrist = 0.42,
                    leftConfidence = 0.91,
                    availableJoints = 3,
                ),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(ReplayTraceEvent.START_CAMERA, rows[0].event)
        assertEquals(ReplayTraceFeed.FRESH, rows[1].feed)
        assertEquals(10_000_000, rows[1].captureNs)
        assertEquals(160.25, rows[1].leftElbowDegrees)
        assertEquals(0.42, rows[1].leftWristHeight)
        assertEquals(0.91, rows[1].leftConfidence)
        assertNull(rows[1].rightElbowDegrees)
        assertEquals(3, rows[1].availableArmJoints)
    }

    @Test
    fun rejectsLegacyVersionAndQuantizedV2Rows() {
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(version = 1)))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(angleQuantum = 0.5)))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(heightQuantum = 0.005)))
        }
    }

    @Test
    fun rejectsMixedSessionsAndNonContiguousSequences() {
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(
                sequenceOf(row(), row(seq = 1, session = "other")),
            )
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(), row(seq = 2)))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(), row(seq = 0)))
        }
    }

    @Test
    fun rejectsMalformedEventsFeedsAndMissingFreshCapture() {
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(event = "UNKNOWN")))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(feed = "UNKNOWN")))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(event = "SAMPLE", feed = "FRESH")))
        }
        assertFailsWith<ReplayTraceFormatException> {
            ReplayTraceParser.parse(sequenceOf(row(event = "CALIBRATE", side = null)))
        }
    }

    private fun row(
        seq: Long = 0,
        version: Int = 2,
        session: String = "synthetic-session",
        event: String = "SAMPLE",
        feed: String = "WAITING",
        captureNs: Long? = null,
        decisionNs: Long = 100,
        side: String? = "LEFT",
        angleQuantum: Double = 0.0,
        heightQuantum: Double = 0.0,
        leftElbow: Double? = null,
        rightElbow: Double? = null,
        leftWrist: Double? = null,
        rightWrist: Double? = null,
        leftConfidence: Double? = null,
        rightConfidence: Double? = null,
        availableJoints: Int = 0,
    ): String = """
        {
          "v":$version,"session":"$session","seq":$seq,"angle_quantum_deg":$angleQuantum,
          "height_quantum":$heightQuantum,"event":"$event","feed":"$feed",
          "capture_ns":${captureNs ?: "null"},"decision_ns":$decisionNs,
          "side":${side?.let { "\"$it\"" } ?: "null"},
          "left_elbow_deg":${leftElbow ?: "null"},"right_elbow_deg":${rightElbow ?: "null"},
          "left_wrist_height":${leftWrist ?: "null"},"right_wrist_height":${rightWrist ?: "null"},
          "left_confidence":${leftConfidence ?: "null"},"right_confidence":${rightConfidence ?: "null"},
          "available_arm_joints":$availableJoints,"stage":"RECORDED_ONLY","phase":"WAITING","candidate_cycles":999
        }
    """.trimIndent()
}
