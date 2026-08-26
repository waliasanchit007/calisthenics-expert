package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.android.BuildConfig
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.contract.decodeFrame
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.replay.FiveRunReplayEnvironment
import com.calisthenics.diagnostic.replay.FiveRunReplayEvidence
import com.calisthenics.diagnostic.replay.FiveRunReplayProvenanceBinding
import com.calisthenics.diagnostic.replay.FiveRunReplayRunRecord
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.replay.decodeReplayFixtureMetadata
import com.calisthenics.diagnostic.replay.fiveRunFixtureBindingOf
import com.calisthenics.diagnostic.replay.fiveRunPoseObservationOf
import com.calisthenics.diagnostic.replay.renderFiveRunReplayEvidence
import com.calisthenics.diagnostic.session.FinalizationResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Task 5's five-run S25 device gate (design section 7 / task brief). `make
 * verify-harness-s25-five-run-replay` is the only Makefile target that runs this against a
 * required physical Samsung Galaxy S25 Ultra; it also allows this same test to run on any
 * attached device (including the emulator) as a mechanism proof, exactly like the
 * `verify-harness-detection-replay` / `verify-harness-s25-process-kill` split. Whichever device
 * runs it, `renderFiveRunReplayEvidence` — not this test — decides whether the recorded
 * observation can ever claim `satisfies_s25_device_gate`; a non-S25 run always renders `false`.
 *
 * `validated_by_reference_cli` cannot be set here: the Python reference validator cannot run
 * on-device. Each run's export path is emitted so the host wrapper
 * (`tools/check_five_run_replay.py`) can pull the export via `adb shell run-as` and validate it,
 * then print the final sanitized record — with `validated_by_reference_cli` filled in — for a
 * human to copy into the committed ledger, matching the existing
 * `docs/evidence/device-probes/s25-ultra-process-kill-ledger.json` convention: this test, and the
 * Makefile target that runs it, never write the committed ledger themselves.
 */
class FiveRunReplayDeviceGateTest {

    @Test
    fun theSameFixtureReplaysFiveCompleteTimesWithConsistentExports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provenance = parseModelProvenance(
            context.assets.open("replay/model_provenance.json").use { it.readBytes() }.decodeToString(),
        )
        val fixtureMetadata = context.assets.open("replay/detection/fixture_metadata.json")
            .use { it.readBytes() }.decodeToString()
        val fixture = decodeReplayFixtureMetadata(fixtureMetadata) { path ->
            context.assets.open("replay/detection/$path").use { it.readBytes() }
        }

        val roots = (AndroidStorageRoots.from(context) as StorageRootsResult.Ready).roots

        val runs = (1..RUN_COUNT).map { runIndex ->
            val submitted = mutableListOf<com.calisthenics.diagnostic.replay.SubmittedFrame>()
            val result = RealReplayComposition(context).run(
                fixture = RealReplayFixtureSelection.DETECTION_TIER,
                onSubmittedFrame = { submitted += it },
            )
            val completed = assertIs<RealReplayResult.Completed>(result, "run $runIndex must publish: $result")
            assertIs<FinalizationResult.Published>(completed.finalization, "run $runIndex must publish")

            val exportDir = File("${roots.exportsRoot}/${completed.sessionId.value}")
            val manifestFile = File(exportDir, "manifest.json")
            val framesFile = File(exportDir, "frames.ndjson")
            assertTrue(manifestFile.isFile && framesFile.isFile, "run $runIndex export must exist on disk")
            val persistedFrames = framesFile.readLines()
                .filter(String::isNotEmpty)
                .map(::decodeFrame)

            FiveRunReplayRunRecord(
                runIndex = runIndex,
                sessionId = completed.sessionId.value,
                published = true,
                submittedIndices = submitted.map { it.frameIndex },
                backendTimestampsMs = submitted.map { it.backendTimestampMs },
                canonicalInputSha256 = submitted.map { it.canonicalInputSha256 },
                // Derived from this run's own emitted records, not declared here: a run of an empty
                // pose pipeline reproduces every field above exactly, so without this the gate
                // would certify five identical runs of a pipeline that detected nothing.
                pose = fiveRunPoseObservationOf(
                    persistedFrames,
                    persistedFrames.count { it.posePresent }.toLong(),
                ),
                manifestSha256 = Sha256Digest.hex(manifestFile.readBytes()),
                framesSha256 = Sha256Digest.hex(framesFile.readBytes()),
                // Only the host wrapper, after actually invoking the Python reference CLI against
                // the pulled export, may set this true; see the class doc.
                validatedByReferenceCli = false,
            )
        }

        // The five-run determinism claim itself, independent of the device-gate/CLI-validation
        // question the rendered evidence separately guards.
        runs.drop(1).forEachIndexed { offset, run ->
            assertEquals(runs.first().submittedIndices, run.submittedIndices, "run ${offset + 2} submitted indices diverged")
            assertEquals(runs.first().backendTimestampsMs, run.backendTimestampsMs, "run ${offset + 2} backend timestamps diverged")
            assertEquals(
                runs.first().canonicalInputSha256,
                run.canonicalInputSha256,
                "run ${offset + 2} canonical input digests diverged",
            )
            // Design section 7 puts output topology inside cross-run equality. Agreeing on the
            // inputs while disagreeing on what came back is a material backend-nondeterminism
            // finding under the pinned CPU delegate, not a flake to retry.
            assertEquals(runs.first().pose, run.pose, "run ${offset + 2} pose observation diverged")
        }

        // The pipeline must actually have detected something, in every run. This is the
        // detection-tier admission criterion applied to the device gate: five consistent runs of an
        // empty pose pipeline satisfy every equality above while observing nothing.
        runs.forEach { run ->
            assertTrue(
                run.pose.poseDetectedRecords > 0L,
                "run ${run.runIndex} detected no pose on any frame: ${run.pose}",
            )
            assertEquals(
                run.submittedIndices.size,
                run.pose.posePresentPerFrame.size,
                "run ${run.runIndex} emitted a record count that does not match its submitted frames",
            )
            assertEquals(
                run.pose.posePresentPerFrame.count { it }.toLong(),
                run.pose.poseDetectedRecords,
                "run ${run.runIndex} pose-detected count is not supported by its own records",
            )
        }

        val record = renderFiveRunReplayEvidence(
            FiveRunReplayEvidence(
                emittedBy = "com.calisthenics.diagnostic.composition.FiveRunReplayDeviceGateTest",
                harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                environment = FiveRunReplayEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = marketingName(context),
                ),
                provenance = FiveRunReplayProvenanceBinding(
                    modelAssetName = provenance.assetName,
                    modelSha256 = provenance.sha256,
                    tasksVisionVersion = provenance.tasksVisionVersion,
                    runningMode = provenance.runningMode,
                    delegate = provenance.delegate,
                    modelInputWidth = fixture.frameWidth,
                    modelInputHeight = fixture.frameHeight,
                ),
                // Digests computed from the plane bytes this device actually read, never the
                // declared strings that travelled beside them; see fiveRunFixtureBindingOf.
                fixture = fiveRunFixtureBindingOf(
                    fixtureId = kotlinx.serialization.json.Json.parseToJsonElement(fixtureMetadata)
                        .let { it as kotlinx.serialization.json.JsonObject }
                        .getValue("fixture_id").let { (it as kotlinx.serialization.json.JsonPrimitive).content },
                    fixture = fixture,
                ),
                runs = runs,
            ),
        )

        InstrumentationRegistry.getInstrumentation().sendStatus(
            FIVE_RUN_REPLAY_STATUS_CODE,
            Bundle().apply { putString(FIVE_RUN_REPLAY_TAG, record) },
        )
        Log.i(FIVE_RUN_REPLAY_TAG, record)
    }

    private fun marketingName(context: android.content.Context): String = readMarketingName()

    companion object {
        const val FIVE_RUN_REPLAY_TAG = "FiveRunReplayEvidence"
        const val FIVE_RUN_REPLAY_STATUS_CODE = 4
        private const val RUN_COUNT = 5
    }
}
