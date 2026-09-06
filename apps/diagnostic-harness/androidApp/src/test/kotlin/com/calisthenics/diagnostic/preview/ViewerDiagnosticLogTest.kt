package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.PullUpExperimentState
import com.calisthenics.diagnostic.viewer.experimental.CycleOutcome
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ViewerDiagnosticLogTest {
    @Test fun jointAndCauseAreRecordedWithoutDependingOnAMessageOrChangingTheStage() {
        val rows = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val latch = CountDownLatch(1)
        val logger = ViewerDiagnosticLog("test",0,exact=true,emit={ rows += JSONObject(it); latch.countDown() })
        val points = listOf(
            com.calisthenics.diagnostic.contract.Landmark(11,null,.5,.5,0.0,.9,.9),
            com.calisthenics.diagnostic.contract.Landmark(13,null,.5,.3,0.0,.9,.9),
            com.calisthenics.diagnostic.contract.Landmark(15,null,.5,-.0035,0.0,.9,.9),
        )
        try {
            logger.record(ViewerDebugEvent.SAMPLE,10,
                com.calisthenics.diagnostic.viewer.measureMovement(points,1080,1440,10),
                PullUpExperimentState(),null,MovementFeedStatus.FRESH,null,1,1,0)
            assertTrue(latch.await(3,TimeUnit.SECONDS))
            assertEquals("WRIST",rows.single().getString("left_unavailable_joint"))
            assertEquals("OUT_OF_FRAME",rows.single().getString("left_unavailable_reason"))
            assertFalse(rows.single().has("message"))
        } finally { logger.close() }
    }
    @Test fun cycleOutcomeIsRecordedEvenWhenOnlyTheOutcomeChanges() {
        val rows = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val latch = CountDownLatch(2)
        val logger = ViewerDiagnosticLog("test",0) { rows += JSONObject(it); latch.countDown() }
        try {
            logger.record(ViewerDebugEvent.SAMPLE,0,null,PullUpExperimentState(),null,
                MovementFeedStatus.WAITING,null,0,0,0)
            logger.record(ViewerDebugEvent.SAMPLE,1,null,
                PullUpExperimentState(lastCycleOutcome=CycleOutcome.TOP_NOT_CONFIRMED),null,
                MovementFeedStatus.WAITING,null,0,0,0)
            assertTrue(latch.await(3,TimeUnit.SECONDS))
            assertEquals("TOP_NOT_CONFIRMED",rows.last().getString("cycle_outcome"))
        } finally { logger.close() }
    }
    @Test fun exactModePreservesTwoFreshInputsInsideRateWindowAndTheirClock() {
        val rows = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val latch = CountDownLatch(2)
        val logger = ViewerDiagnosticLog("test",0,emit = { rows += JSONObject(it); latch.countDown() },
            exact = true, sessionId = "paired-session")
        try {
            for (capture in listOf(10L,20L)) logger.record(ViewerDebugEvent.SAMPLE,capture+1,
                MovementMeasurements(capture,ArmMeasurements(90.234,.00123,.91234),ArmMeasurements(),3),
                PullUpExperimentState(),null,MovementFeedStatus.FRESH,10,0,0,0)
            assertTrue(latch.await(3,TimeUnit.SECONDS))
            assertEquals(2,rows.size)
            assertEquals(90.234,rows[0].getDouble("left_elbow_deg"))
            assertEquals(.00123,rows[0].getDouble("left_wrist_height"))
            assertEquals(10,rows[0].getLong("capture_ns"))
            assertEquals(11,rows[0].getLong("decision_ns"))
            assertEquals("paired-session",rows[0].getString("session"))
        } finally { logger.close() }
    }
    @Test fun enableRequiresDebugBuildAndFreshPrivateMarker() {
        val dir = java.nio.file.Files.createTempDirectory("viewer-debug-test").toFile()
        val marker = File(dir,"enabled")
        try {
            assertFalse(viewerDiagnosticsAuthorized(marker,true,10_000))
            marker.writeText("")
            assertTrue(marker.setLastModified(10_000))
            assertTrue(viewerDiagnosticsAuthorized(marker,true,10_001))
            assertFalse(viewerDiagnosticsAuthorized(marker,false,10_001))
            assertFalse(viewerDiagnosticsAuthorized(marker,true,9_999))
            assertFalse(viewerDiagnosticsAuthorized(marker,true,1_810_000))
        } finally { marker.delete(); dir.delete() }
    }

    @Test fun rowsContainOnlyAllowlistedMeasurementsAndRelativeTime() {
        val latch = CountDownLatch(1)
        var row: JSONObject? = null
        val logger = ViewerDiagnosticLog("test-build",1_000_000_000) {
            row = JSONObject(it); latch.countDown()
        }
        try {
            logger.record(ViewerDebugEvent.SAMPLE,1_100_000_000,
                MovementMeasurements(1_050_000_000,ArmMeasurements(90.23,.126,.912),ArmMeasurements(),3),
                PullUpExperimentState(),null,MovementFeedStatus.FRESH,50_000_000,7,4,2)
            assertTrue(latch.await(3,TimeUnit.SECONDS))
            val value = assertNotNull(row)
            assertEquals(100.0,value.getDouble("elapsed_ms"))
            assertEquals(50.0,value.getDouble("capture_ms"))
            assertEquals(90.0,value.getDouble("left_elbow_deg"))
            assertEquals(.125,value.getDouble("left_wrist_height"))
            assertEquals("FRESH",value.getString("feed"))
            assertEquals(0,value.getInt("candidate_cycles"))
            assertFalse(value.has("landmarks")); assertFalse(value.has("image")); assertFalse(value.has("message"))
            assertTrue(value.isNull("right_elbow_deg"))
            assertEquals("test-build",value.getString("build"))
        } finally { logger.close() }
    }

    @Test fun rateLimitDoesNotDropControlOrStateTransitions() {
        val rows = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val latch = CountDownLatch(3)
        val logger = ViewerDiagnosticLog("test",0) { rows += JSONObject(it); latch.countDown() }
        fun send(event: ViewerDebugEvent, time: Long, state: PullUpExperimentState=PullUpExperimentState()) =
            logger.record(event,time,null,state,null,MovementFeedStatus.WAITING,null,0,0,0)
        try {
            send(ViewerDebugEvent.SAMPLE,0)
            send(ViewerDebugEvent.SAMPLE,1)
            send(ViewerDebugEvent.CALIBRATE,2)
            send(ViewerDebugEvent.SAMPLE,3,PullUpExperimentState(candidateCycles=1))
            assertTrue(latch.await(3,TimeUnit.SECONDS))
            assertEquals(3,rows.size)
            assertEquals(1,rows.last().getLong("rate_suppressed"))
        } finally { logger.close() }
    }
}
