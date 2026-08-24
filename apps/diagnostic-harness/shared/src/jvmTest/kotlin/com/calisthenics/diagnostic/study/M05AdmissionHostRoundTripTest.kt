package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.live.LiveCaptureCadenceDiagnostics
import com.calisthenics.diagnostic.live.LiveCaptureCameraObservation
import com.calisthenics.diagnostic.live.LiveCaptureCounts
import com.calisthenics.diagnostic.live.LiveCaptureEnvironment
import com.calisthenics.diagnostic.live.LiveCaptureEvidence
import com.calisthenics.diagnostic.live.LiveCaptureManifestDevice
import com.calisthenics.diagnostic.live.LiveCaptureThermalObservation
import com.calisthenics.diagnostic.live.LiveCaptureThermalSummary
import com.calisthenics.diagnostic.live.renderLiveCaptureEvidence
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals

class M05AdmissionHostRoundTripTest {
    @Test
    fun wrongSessionAndFalseAttestationRenderAsBlockedOnEveryPythonHostPath() {
        val cases = listOf(
            M05AdmissionEvidence(
                sessionId = SESSION_ID,
                sessionKind = "live_study",
                inputClass = M05InputClass.EMPTY_SCENE,
                operatorAttestedNoPerson = true,
                submittedFrameCount = 10,
                detectionActiveFrameCount = 0,
            ) to "STUDY.ADMISSION_SESSION_KIND",
            M05AdmissionEvidence(
                sessionId = SESSION_ID,
                sessionKind = "live_conformance",
                inputClass = M05InputClass.EMPTY_SCENE,
                operatorAttestedNoPerson = false,
                submittedFrameCount = 10,
                detectionActiveFrameCount = 0,
            ) to "STUDY.ADMISSION_INPUT_CLASS",
        )

        cases.forEach { (source, expectedCode) ->
            val rendered = renderLiveCaptureEvidence(
                admittedEvidence().copy(m05Admission = M05Admission.evaluate(source)),
            )
            assertPythonHostRejectsEveryAdmissionPath(rendered, expectedCode)
        }
    }

    private fun assertPythonHostRejectsEveryAdmissionPath(rendered: String, expectedCode: String) {
        val root = repositoryRoot()
        val directory = Files.createTempDirectory("m05-admission-host-")
        try {
            val evidencePath = directory.resolve("evidence.json")
            Files.writeString(evidencePath, rendered)
            val process = ProcessBuilder(
                root.resolve(".venv/bin/python").toString(),
                "-c",
                PYTHON_HOST_ASSERTION,
                directory.toString(),
                evidencePath.toString(),
                expectedCode,
            )
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor(), output)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }
        .first { Files.isRegularFile(it.resolve("tools/check_live_capture_run.py")) }

    private fun admittedEvidence() = LiveCaptureEvidence(
        emittedBy = "com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest",
        sessionId = SESSION_ID,
        harnessGitSha = "0123456789abcdef0123456789abcdef01234567",
        environment = LiveCaptureEnvironment(
            deviceClass = "physical_device",
            deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
            deviceFingerprint = "samsung/pa3q/pa3qxins:16/test:user/release-keys",
            apiLevel = 36,
            androidRelease = "16",
            abi = "arm64-v8a",
            manufacturer = "samsung",
            model = "SM-S938B",
            marketingName = "",
            chipset = "s5e9955",
        ),
        manifestDevice = LiveCaptureManifestDevice("android", "16", "SM-S938B", "s5e9955"),
        camera = LiveCaptureCameraObservation(
            cameraId = "0",
            timestampSource = "REALTIME",
            sourceDataspaceRaw = 146931712,
            sourceColorStandard = "BT601",
            sourceColorRange = "FULL",
            underlyingImageAvailable = true,
            pixelFormat = "YUV_420_888",
            captureWidth = 1280,
            captureHeight = 720,
            orientationDegrees = 90,
            mirrored = true,
        ),
        thermal = LiveCaptureThermalSummary(
            apiAvailable = true,
            unavailableReason = null,
            observations = listOf(LiveCaptureThermalObservation(0, 0)),
        ),
        counts = LiveCaptureCounts(
            cameraFramesReceived = 10,
            inferenceFramesSubmitted = 2,
            inferenceRecordsEmitted = 2,
            deliveredCallbackClassificationGap = 0,
            cadenceDiagnostics = LiveCaptureCadenceDiagnostics.fromAdmittedTimestamps(
                timestampsNs = (0L..9L).map { it * 33_333_334L },
                requestedCameraFps = 30,
                deliveredCallbackClassificationGap = 0,
            ),
            framesSkippedBeforeInference = 8,
            framesSkippedBeforeInferenceByReason = mapOf("cadence_selection" to 8),
        ),
        referenceValidationSucceeded = true,
        instrumentationSucceeded = true,
    )

    private companion object {
        const val SESSION_ID = "00000000-0000-4000-8000-000000000001"

        val PYTHON_HOST_ASSERTION = """
import hashlib
import json
import sys
from pathlib import Path

from tools.check_live_capture_run import M05_ADMISSION_PATHS, check_transcript, verify_recorded_ledger

directory = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])
expected_code = sys.argv[3]
record = json.loads(evidence_path.read_text(encoding="utf-8"))
assert M05_ADMISSION_PATHS == ("live_transcript", "recorded_live_ledger")

for admission_path in M05_ADMISSION_PATHS:
    if admission_path == "live_transcript":
        transcript = (
            "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest\n"
            + "INSTRUMENTATION_STATUS: LiveCaptureEvidence="
            + json.dumps(record, separators=(",", ":"))
            + "\nOK (1 test)\n"
        )
        checked = check_transcript(transcript, None, True, 0)
        assert expected_code in checked["blocking_reasons"], checked
        assert checked["satisfies_s25_live_capture"] is False
    else:
        ledger_path = directory / "ledger.json"
        ledger_path.write_text(
            json.dumps(
                {
                    "status": "recorded",
                    "evidence_file": evidence_path.name,
                    "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
                    "live_admission": "blocked",
                }
            ),
            encoding="utf-8",
        )
        assert verify_recorded_ledger(directory, ledger_path.name)["status"] == "recorded"
"""
    }
}
