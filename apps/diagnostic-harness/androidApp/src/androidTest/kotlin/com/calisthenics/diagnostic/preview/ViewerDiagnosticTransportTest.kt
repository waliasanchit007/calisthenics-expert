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
