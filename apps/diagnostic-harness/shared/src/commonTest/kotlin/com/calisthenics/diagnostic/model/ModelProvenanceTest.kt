package com.calisthenics.diagnostic.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val VALID = """
{
  "asset_name": "pose_landmarker_full.task",
  "sha256": "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1",
  "byte_count": 9398198,
  "source_url": "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task",
  "model_family": "pose_landmarker",
  "model_variant": "full",
  "quantization": "float16",
  "upstream_revision": "1",
  "tasks_vision_version": "1.0.0",
  "running_mode": "VIDEO",
  "delegate": "CPU",
  "task_options": {
    "num_poses": 1,
    "min_pose_detection_confidence": 0.5,
    "min_pose_presence_confidence": 0.5,
    "min_tracking_confidence": 0.5,
    "output_segmentation_masks": false
  }
}
"""

class ModelProvenanceTest {
    @Test
    fun parsesEveryPinnedField() {
        val p = parseModelProvenance(VALID)
        assertEquals("pose_landmarker_full.task", p.assetName)
        assertEquals(9398198L, p.byteCount)
        assertEquals("1.0.0", p.tasksVisionVersion)
        assertEquals("VIDEO", p.runningMode)
        assertEquals("CPU", p.delegate)
        assertEquals(1, p.taskOptions["num_poses"])
        assertTrue(p.sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun verificationAcceptsAnExactMatch() {
        val p = parseModelProvenance(VALID)
        verifyModelAsset(p, actualSha256 = p.sha256, actualByteCount = p.byteCount)
    }

    @Test
    fun verificationFailsClosedOnShaMismatch() {
        val p = parseModelProvenance(VALID)
        val e = assertFailsWith<ModelProvenanceException> {
            verifyModelAsset(p, actualSha256 = "0".repeat(64), actualByteCount = p.byteCount)
        }
        assertTrue(e.message!!.contains("sha256"))
    }

    @Test
    fun verificationFailsClosedOnByteCountMismatch() {
        val p = parseModelProvenance(VALID)
        assertFailsWith<ModelProvenanceException> {
            verifyModelAsset(p, actualSha256 = p.sha256, actualByteCount = p.byteCount - 1)
        }
    }

    @Test
    fun uppercaseShaIsRejectedAsNonCanonical() {
        assertFailsWith<ModelProvenanceException> {
            parseModelProvenance(VALID.replace("5134a3aa", "5134A3AA"))
        }
    }

    @Test
    fun nonVideoRunningModeIsRejectedForReplay() {
        val live = parseModelProvenance(VALID.replace("\"VIDEO\"", "\"LIVE_STREAM\""))
        assertEquals("LIVE_STREAM", live.runningMode)
        assertFailsWith<ModelProvenanceException> { requireReplayProvenance(live) }
        requireReplayProvenance(parseModelProvenance(VALID))
    }
}
