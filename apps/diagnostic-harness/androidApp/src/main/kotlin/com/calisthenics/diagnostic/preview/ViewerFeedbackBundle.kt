package com.calisthenics.diagnostic.preview

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

internal fun feedbackSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Explicit operator deletion; never invoked by retention or capacity checks. */
internal fun deleteViewerFeedback(root: File): Int {
    check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
    var removed=0
    for (entry in root.listFiles().orEmpty()) {
        if (!entry.name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) continue
        check(entry.walkTopDown().none { java.nio.file.Files.isSymbolicLink(it.toPath()) })
        check(entry.deleteRecursively())
        removed++
    }
    return removed
}

/** Local exploratory evidence, NOT a formal study export or lossless video recording.
 * Files and JPEG work are worker-owned; inference/Compose never waits for disk or compression.
 */
internal class ViewerFeedbackBundle(
    private val root: File,
    private val build: String,
    private val originNs: Long,
    private val modelProvenance: String,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxDurationNs: Long = 180_000_000_000L,
    private val totalBytes: Long = 256L * 1024 * 1024,
    private val modelProvenanceProvider: () -> String = { modelProvenance },
) : AutoCloseable {
    val sessionId: String = UUID.randomUUID().toString()
    val directory = File(root,sessionId)
    val completion = CompletableFuture<Unit>()
    val dropped = AtomicLong()
    @Volatile var status: String = "Starting local recording"
        private set
    @Volatile private var accepting = true
    @Volatile private var reason = "user_stop"
    private var lastCapture: Long? = null
    private var frameSequence = 0L
    private var framesOffered = 0L
    private var tracesOffered = 0L
    private var framesWritten = 0L
    private var tracesWritten = 0L
    private var traceLoggerDropped = 0L
    private var bytesWritten = 0L
    private var firstCapture: Long? = null
    private var finalCapture: Long? = null
    private var diskReady = false
    private val worker = object : ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(8),
        { task -> Thread(task,"viewer-feedback-writer").apply { isDaemon = true } }) {
        override fun terminated() {
            try {
                if (diskReady) {
                    val end = JSONObject().put("v",1).put("session",sessionId).put("reason",reason)
                        .put("frames_offered",framesOffered).put("frames_written",framesWritten)
                        .put("traces_offered",tracesOffered).put("traces_written",tracesWritten)
                        .put("dropped",dropped.get()).put("bytes_written",bytesWritten)
                        .put("trace_logger_dropped",traceLoggerDropped)
                        .put("first_capture_ns",firstCapture ?: JSONObject.NULL)
                        .put("last_capture_ns",finalCapture ?: JSONObject.NULL)
                        .put("durability","best_effort_local_debug")
                    atomicWrite(File(directory,"end.json"),end.toString().toByteArray(),sync=true)
                }
                status = "Recording stopped: $reason · drops ${dropped.get()}"
                completion.complete(Unit)
            } catch (error: Exception) {
                status = "Recording failed: terminal metadata unavailable"
                completion.completeExceptionally(error)
            }
        }
    }

    init {
        require(maxBytes > 0 && totalBytes >= maxBytes && maxDurationNs > 0)
        worker.execute {
            try {
                // Reserve room for this whole session; no automatic deletion of older recordings.
                val existing = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                if (existing > totalBytes - maxBytes) {
                    stopAccepting("total_cap")
                } else {
                    check(File(directory,"frames").mkdirs())
                    diskReady = true
                    val meta = JSONObject().put("v",1).put("session",sessionId).put("build",build)
                        .put("origin_ns",originNs).put("clock","elapsedRealtimeNanos")
                        .put("image_kind","lossy_jpeg_of_model_input").put("audio",false)
                        .put("max_duration_ns",maxDurationNs).put("max_bytes",maxBytes)
                        .put("model_provenance",JSONObject(modelProvenanceProvider()))
                    val metadataBytes=meta.toString().toByteArray()
                    require(metadataBytes.size <= maxBytes / 2)
                    atomicWrite(File(directory,"session.json"),metadataBytes)
                    bytesWritten=metadataBytes.size.toLong()
                    File(directory,"trace.jsonl").createNewFile()
                    File(directory,"frames.jsonl").createNewFile()
                    if (accepting) status = "Recording locally · model-input images + movement logs"
                }
            } catch (_: Exception) { stopAccepting("io_error") }
        }
    }

    @Synchronized fun recordObservation(observation: ViewerObservation, nowNs: Long) {
        if (!admit(nowNs) || observation.capturedNs == lastCapture) return
        if (observation.capturedNs < 0 || lastCapture?.let { observation.capturedNs < it } == true) {
            dropped.incrementAndGet(); return
        }
        lastCapture = observation.capturedNs
        val seq = frameSequence++
        framesOffered++
        // The viewer owns each model buffer; copy before returning so later producer reuse is harmless.
        val rgb = observation.frame.modelRgb.copyOf()
        val landmarks = observation.landmarks.toList()
        enqueue {
            require(rgb.size == 256*256*3)
            val pixels = IntArray(256*256) { index ->
                val i=index*3
                (0xff shl 24) or ((rgb[i].toInt() and 255) shl 16) or
                    ((rgb[i+1].toInt() and 255) shl 8) or (rgb[i+2].toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888)
            val jpeg = try { ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG,80,out)); out.toByteArray()
            } } finally { bitmap.recycle() }
            val path = "frames/${observation.capturedNs}.jpg"
            val points = JSONArray()
            fun finite(value: Double?): Any = value?.takeIf { it.isFinite() } ?: JSONObject.NULL
            for (point in landmarks) points.put(JSONObject().put("index",point.index)
                .put("x",finite(point.x)).put("y",finite(point.y)).put("z",finite(point.z))
                .put("visibility",finite(point.visibility)).put("presence",finite(point.presence)))
            val row = JSONObject().put("v",1).put("session",sessionId).put("seq",seq)
                .put("capture_ns",observation.capturedNs).put("image",path)
                .put("input_rgb_sha256",feedbackSha(rgb)).put("jpeg_sha256",feedbackSha(jpeg))
                .put("width",256).put("height",256)
                .put("source_width",observation.frame.width).put("source_height",observation.frame.height)
                .put("pose_present",observation.posePresent).put("landmarks",points)
            val line = (row.toString()+"\n").toByteArray()
            if (reserve(jpeg.size.toLong()+line.size)) {
                atomicWrite(File(directory,path),jpeg)
                File(directory,"frames.jsonl").appendBytes(line)
                framesWritten++
                firstCapture = firstCapture ?: observation.capturedNs
                finalCapture = observation.capturedNs
            }
        }
    }

    @Synchronized fun recordTrace(row: String, nowNs: Long) {
        if (!admit(nowNs)) return
        tracesOffered++
        enqueue {
            val line=(row+"\n").toByteArray()
            if (reserve(line.size.toLong())) {
                File(directory,"trace.jsonl").appendBytes(line)
                tracesWritten++
            }
        }
    }

    private fun admit(nowNs: Long): Boolean {
        if (!accepting || worker.isShutdown) return false
        if (nowNs < originNs || nowNs-originNs >= maxDurationNs) {
            stopAccepting("duration"); return false
        }
        return true
    }

    private fun enqueue(action: () -> Unit) {
        try { worker.execute {
            if (!diskReady) { dropped.incrementAndGet(); return@execute }
            try { action() } catch (_: Exception) { dropped.incrementAndGet(); stopAccepting("io_error") }
        } } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun reserve(bytes: Long): Boolean {
        // Keep a bounded allowance for the terminal record even when the data budget is exhausted.
        if (bytesWritten + bytes > maxBytes - minOf(4096L,maxBytes/4)) {
            dropped.incrementAndGet(); stopAccepting("session_cap"); return false
        }
        bytesWritten += bytes
        return true
    }

    private fun stopAccepting(cause: String) {
        accepting = false
        reason = cause
        status = "Not recording: $cause · drops ${dropped.get()}"
    }

    @Synchronized fun markStopReason(cause: String) {
        if (accepting) reason=cause
    }

    @Synchronized fun close(cause: String, loggerDropped: Long = 0) {
        if (worker.isShutdown) return
        traceLoggerDropped = loggerDropped
        if (accepting && reason == "user_stop") reason = cause
        accepting = false
        worker.shutdown()
    }
    override fun close() = close("user_stop")

    private fun atomicWrite(target: File, bytes: ByteArray, sync: Boolean = false) {
        val temp = File(target.parentFile,target.name+".partial")
        FileOutputStream(temp).use { it.write(bytes); if (sync) it.fd.sync() }
        check(temp.renameTo(target))
    }
}
