package com.calisthenics.diagnostic.preview

import android.os.SystemClock
import android.util.Log
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.PullUpExperimentState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertTrue

/** Literal synthetic numbers only: exercises real platform logging without a camera or unlocked UI. */
class ViewerDiagnosticTransportTest {
    @Test fun syntheticFeedbackBundleCanBeRetrievedWithoutCameraCapture() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val now = SystemClock.elapsedRealtimeNanos()
        val bundle = ViewerFeedbackBundle(java.io.File(context.noBackupFilesDir,"movement-feedback"),
            BuildConfig.PROBE_GIT_SHA,now,context.assets.open("replay/live_model_provenance.json").bufferedReader().use { it.readText() })
        val observation = ViewerObservation(ViewerFrame(byteArrayOf(),256,256,ByteArray(256*256*3) { 80 }),
            emptyList(),false,now)
        lateinit var logger: ViewerDiagnosticLog
        logger = ViewerDiagnosticLog(BuildConfig.PROBE_GIT_SHA,now,exact=true,sessionId=bundle.sessionId,
            onClosed={ bundle.close("user_stop",logger.droppedRows) },emit={ bundle.recordTrace(it,now) })
        bundle.recordObservation(observation,now)
        logger.record(ViewerDebugEvent.START_SYNTHETIC,now,null,PullUpExperimentState(),null,
            MovementFeedStatus.NO_POSE,null,1,1,0,observation=observation)
        logger.close()
        bundle.completion.get(10,TimeUnit.SECONDS)
        val frame = org.json.JSONObject(java.io.File(bundle.directory,"frames.jsonl").readLines().single())
        val row = org.json.JSONObject(java.io.File(bundle.directory,"trace.jsonl").readLines().single())
        kotlin.test.assertEquals(frame.getString("input_rgb_sha256"),row.getString("input_rgb_sha256"))
        kotlin.test.assertEquals(now,row.getLong("observation_capture_ns"))
        val jpeg = java.io.File(bundle.directory,frame.getString("image")).readBytes()
        kotlin.test.assertEquals(frame.getString("jpeg_sha256"),feedbackSha(jpeg))
        assertTrue(android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size) != null)
        val end = org.json.JSONObject(java.io.File(bundle.directory,"end.json").readText())
        kotlin.test.assertEquals(0,end.getLong("dropped"))
        instrumentation.sendStatus(0,android.os.Bundle().apply { putString("feedback_session",bundle.sessionId) })
    }
    @Test fun syntheticDiagnosticRecordReachesAndroidLog() {
        val delivered = CountDownLatch(1)
        val now = SystemClock.elapsedRealtimeNanos()
        val logger = ViewerDiagnosticLog(BuildConfig.PROBE_GIT_SHA,now) { line ->
            check(Log.i("CalisthenicsTrace",line) > 0)
            delivered.countDown()
        }
        try {
            logger.record(ViewerDebugEvent.START_SYNTHETIC,now,
                MovementMeasurements(now,ArmMeasurements(90.0,.25,.9),ArmMeasurements(),3),
                PullUpExperimentState(),null,MovementFeedStatus.FRESH,null,1,1,0)
            assertTrue(delivered.await(5,TimeUnit.SECONDS))
        } finally { logger.close() }
    }
}
