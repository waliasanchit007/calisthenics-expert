package com.calisthenics.diagnostic.composition

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.session.FinalizationResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Task 5: proves the UI's real replay path — real MediaPipe VIDEO replay through the real
 * [RealReplayComposition], real [com.calisthenics.diagnostic.export.SessionExporter], and the real
 * [com.calisthenics.diagnostic.export.AndroidStoragePort] — actually publishes a session, and
 * publishes the on-device export path so `make verify-harness-replay-export` can pull the export
 * off the device and validate it against the Python reference CLI. This is the general,
 * any-authorized-device acceptance check (an emulator is a legitimate target here, exactly as for
 * `verify-harness-detection-replay`); the S25-specific five-run gate is a separate, stricter,
 * device-restricted observation in [FiveRunReplayDeviceGateTest].
 */
class RealReplayCompositionExportTest {

    @Test
    fun realReplayPublishesAndExportsAreReadableForHostValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val result = RealReplayComposition(context).run(RealReplayFixtureSelection.DETECTION_TIER)
        val completed = assertIs<RealReplayResult.Completed>(result, "real replay must publish: $result")
        assertIs<FinalizationResult.Published>(completed.finalization, "real replay must publish: ${completed.finalization}")

        val counts = completed.snapshot.counts
        assertEquals(6, counts.inferenceFramesSubmitted, "the detection fixture has six frames")
        assertEquals(6, counts.inferenceRecordsEmitted)
        assertEquals(0, counts.inferenceFailures)
        assertTrue(counts.poseDetectedRecords > 0, "the detection fixture must actually fire the detector")

        val roots = (AndroidStorageRoots.from(context) as StorageRootsResult.Ready).roots
        val exportDir = File("${roots.exportsRoot}/${completed.sessionId.value}")
        assertTrue(File(exportDir, "manifest.json").isFile)
        assertTrue(File(exportDir, "frames.ndjson").isFile)

        // Emitted so the host wrapper (tools/check_real_replay_export.py, driven by
        // `make verify-harness-replay-export`) can locate this exact run's export for pulling via
        // `adb shell run-as` and validating with the Python reference CLI — the on-device side of
        // "every export validates against the Python reference CLI" cannot itself run Python.
        val record = "session_id=${completed.sessionId.value} export_path=${exportDir.absolutePath}"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            REAL_REPLAY_EXPORT_STATUS_CODE,
            Bundle().apply { putString(REAL_REPLAY_EXPORT_TAG, record) },
        )
        Log.i(REAL_REPLAY_EXPORT_TAG, record)
    }

    companion object {
        const val REAL_REPLAY_EXPORT_TAG = "RealReplayExport"
        const val REAL_REPLAY_EXPORT_STATUS_CODE = 3
    }
}
