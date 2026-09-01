package com.calisthenics.diagnostic.probe

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.study.ContainerTimestampEnvironment
import com.calisthenics.diagnostic.study.ContainerTimestampEvidence
import com.calisthenics.diagnostic.study.ContainerTimestampControls
import com.calisthenics.diagnostic.study.ContainerTimestampObservation
import com.calisthenics.diagnostic.study.ContainerTimestampProvenance
import com.calisthenics.diagnostic.study.containerTimestampBlockingReasons
import com.calisthenics.diagnostic.study.renderContainerTimestampEvidence
import com.calisthenics.diagnostic.study.SynchronizationTimebases
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ContainerTimestampProbeTest {
    @Test
    fun recordsTheActualMp4ContainerTimestampTransform() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val observation = worker.submit<ContainerTimestampProbeResult> {
                check(Thread.currentThread() !== android.os.Looper.getMainLooper().thread) {
                    "probe must run off the Android main thread"
                }
                ContainerTimestampProbe(instrumentation.targetContext).run()
            }.get(90, TimeUnit.SECONDS)

            val evidence = ContainerTimestampEvidence(
                emittedBy = EMITTED_BY,
                environment = ContainerTimestampEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = readMarketingName(),
                ),
                provenance = ContainerTimestampProvenance(
                    harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                    container = observation.container,
                    codec = observation.codec,
                ),
                observation = ContainerTimestampObservation(
                    submittedPtsUs = observation.onGrid.submittedPtsUs,
                    demuxedPtsUs = observation.onGrid.demuxedPtsUs,
                ),
                controls = ContainerTimestampControls(
                    timebases = SynchronizationTimebases(
                        videoTicksPerSecond = observation.timebases.videoTicksPerSecond,
                        metadataTicksPerSecond = observation.timebases.metadataTicksPerSecond,
                    ),
                    onGrid = ContainerTimestampObservation(
                        submittedPtsUs = observation.onGrid.submittedPtsUs,
                        demuxedPtsUs = observation.onGrid.demuxedPtsUs,
                    ),
                    offGrid = ContainerTimestampObservation(
                        submittedPtsUs = observation.offGrid.submittedPtsUs,
                        demuxedPtsUs = observation.offGrid.demuxedPtsUs,
                    ),
                ),
            )
            val record = renderContainerTimestampEvidence(evidence)

            assertTrue(observation.onGrid.submittedPtsUs.all { it > 0L })
            assertTrue(observation.onGrid.demuxedPtsUs.all { it >= 0L })
            assertTrue(observation.offGrid.submittedPtsUs.all { it > 0L })
            assertTrue(observation.offGrid.demuxedPtsUs.all { it >= 0L })
            assertContains(record, "\"sanitized\":true")
            assertContains(record, "\"non_participant_observation\":true")
            assertFalse(record.contains(instrumentation.targetContext.cacheDir.absolutePath))
            containerTimestampBlockingReasons(evidence).forEach { assertContains(record, it) }

            // A blocking result is a successful probe observation, not a failed test. The host
            // checker returns exit 3 and the milestone stops before contract 1.2 in that case.
            instrumentation.sendStatus(
                STATUS_CODE,
                Bundle().apply { putString(STATUS_TAG, record) },
            )
        } finally {
            worker.shutdownNow()
            check(worker.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val EMITTED_BY = "com.calisthenics.diagnostic.probe.ContainerTimestampProbeTest"
        const val STATUS_TAG = "ContainerTimestampEvidence"
        const val STATUS_CODE = 6
    }
}
