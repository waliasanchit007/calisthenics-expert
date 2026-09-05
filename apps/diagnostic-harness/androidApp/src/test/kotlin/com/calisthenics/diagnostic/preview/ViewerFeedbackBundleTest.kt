package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerFeedbackBundleTest {
    @Test fun stalledDiskCannotCreateAnUnboundedQueueAndLossIsAccounted() {
        val root = Files.createTempDirectory("feedback-queue-test").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bundle = ViewerFeedbackBundle(root,"test",0,"{}",modelProvenanceProvider={
            entered.countDown(); check(release.await(3,TimeUnit.SECONDS)); "{}"
        })
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            for (i in 1L..20L) bundle.recordObservation(observation(i),i)
            assertTrue(bundle.dropped.get() > 0)
            bundle.close(); release.countDown()
            bundle.completion.get(5,TimeUnit.SECONDS)
            val end=JSONObject(File(bundle.directory,"end.json").readText())
            assertEquals(20,end.getLong("frames_offered"))
            assertEquals(20,end.getLong("frames_written")+end.getLong("dropped"))
        } finally { release.countDown(); bundle.close(); root.deleteRecursively() }
    }

    @Test fun metadataFailureIsObservableAndNeverClaimsSuccessfulRecording() {
        val root = Files.createTempDirectory("feedback-io-test").toFile()
        val bundle = ViewerFeedbackBundle(root,"test",0,"{}",modelProvenanceProvider={ error("unavailable") })
        try {
            bundle.close(); bundle.completion.get(5,TimeUnit.SECONDS)
            assertEquals("io_error",JSONObject(File(bundle.directory,"end.json").readText()).getString("reason"))
            assertFalse(File(bundle.directory,"session.json").exists())
        } finally { bundle.close(); root.deleteRecursively() }
    }
    @Test fun explicitDeletionTouchesOnlyOwnedSessionDirectories() {
        val root = Files.createTempDirectory("feedback-delete-test").toFile()
        try {
            val owned = File(root,"12345678-1234-1234-1234-123456789abc").apply { mkdir() }
            File(owned,"trace.jsonl").writeText("synthetic")
            File(root,"unrelated.txt").writeText("keep")
            assertEquals(1,deleteViewerFeedback(root))
            assertFalse(owned.exists())
            assertEquals("keep",File(root,"unrelated.txt").readText())
        } finally { root.deleteRecursively() }
    }
    private fun observation(capture: Long) = ViewerObservation(
        ViewerFrame(byteArrayOf(),640,480,ByteArray(256*256*3) { 64 }),
        listOf(Landmark(11,null,.25,.5,.1,.9,.8)),true,capture)

    @Test fun pairsActualInputAndLandmarksAndDeduplicatesTheSameCapture() {
        val root = Files.createTempDirectory("feedback-test").toFile()
        val bundle = ViewerFeedbackBundle(root,"test-build",0,"{}")
        try {
            bundle.recordObservation(observation(10),20)
            bundle.recordObservation(observation(10),21)
            bundle.recordTrace("{\"event\":\"STOP\"}",22)
            bundle.close("user_stop")
            bundle.completion.get(5,TimeUnit.SECONDS)
            val rows = File(bundle.directory,"frames.jsonl").readLines()
            assertEquals(1,rows.size)
            val row = JSONObject(rows.single())
            assertEquals(10,row.getLong("capture_ns"))
            assertEquals(.25,row.getJSONArray("landmarks").getJSONObject(0).getDouble("x"))
            val image = File(bundle.directory,row.getString("image"))
            assertTrue(image.length() > 0)
            assertEquals(sha(image.readBytes()),row.getString("jpeg_sha256"))
            assertEquals(sha(ByteArray(256*256*3) { 64 }),row.getString("input_rgb_sha256"))
            val end = JSONObject(File(bundle.directory,"end.json").readText())
            assertEquals(1,end.getLong("frames_written")); assertEquals(0,end.getLong("dropped"))
            assertEquals("user_stop",end.getString("reason"))
            assertTrue(File(bundle.directory,"trace.jsonl").readText().contains("STOP"))
        } finally { bundle.close(); root.deleteRecursively() }
    }

    @Test fun durationLimitStopsCollectionWithoutDeletingEarlierData() {
        val root = Files.createTempDirectory("feedback-limit-test").toFile()
        val bundle = ViewerFeedbackBundle(root,"test",0,"{}",maxDurationNs=100)
        try {
            bundle.recordObservation(observation(5),10)
            bundle.recordObservation(observation(100),100)
            bundle.close()
            bundle.completion.get(5,TimeUnit.SECONDS)
            val end = JSONObject(File(bundle.directory,"end.json").readText())
            assertEquals("duration",end.getString("reason"))
            assertEquals(1,end.getLong("frames_written"))
        } finally { bundle.close(); root.deleteRecursively() }
    }

    @Test fun byteLimitIsAnExplicitPartialRecordingNotSuccessfulCompletion() {
        val root = Files.createTempDirectory("feedback-byte-test").toFile()
        val bundle = ViewerFeedbackBundle(root,"test",0,"{}",maxBytes=1024)
        try {
            bundle.recordObservation(observation(10),20)
            bundle.close()
            bundle.completion.get(5,TimeUnit.SECONDS)
            val end = JSONObject(File(bundle.directory,"end.json").readText())
            assertEquals("session_cap",end.getString("reason"))
            assertEquals(0,end.getLong("frames_written"))
            assertTrue(end.getLong("dropped") > 0)
            assertFalse(File(bundle.directory,"frames/10.jpg").exists())
        } finally { bundle.close(); root.deleteRecursively() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
