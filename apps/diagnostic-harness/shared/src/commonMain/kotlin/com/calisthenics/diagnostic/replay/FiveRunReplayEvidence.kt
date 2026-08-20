package com.calisthenics.diagnostic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Schema version of the emitted five-run replay device-gate observation record. */
const val FIVE_RUN_REPLAY_EVIDENCE_SCHEMA_VERSION: String = "1.0.0"

/** Discriminator so a reader can never mistake this record for the Task 4 detection observation. */
const val FIVE_RUN_REPLAY_RECORD_KIND: String = "five_run_replay_device_gate_observation"

/** The marketing-name substring that identifies the one authorized five-run gate target. */
private const val S25_ULTRA_MARKER = "S25 Ultra"

/**
 * The device the five complete runs were observed on. Derived from platform build markers by the
 * caller, exactly like [DetectionReplayEnvironment] (Task 4) — an emulator observation and a
 * physical-device observation are never interchangeable, and [renderFiveRunReplayEvidence] never
 * trusts a caller-declared gate flag over what these fields actually say.
 */
data class FiveRunReplayEnvironment(
    val deviceClass: String,
    val deviceIdentity: String,
    val deviceFingerprint: String,
    val apiLevel: Int,
    val androidRelease: String,
    val abi: String,
    val manufacturer: String,
    val marketingName: String,
)

/** The pinned inputs the five runs actually constructed their backend from. */
data class FiveRunReplayProvenanceBinding(
    val modelAssetName: String,
    val modelSha256: String,
    val tasksVisionVersion: String,
    val runningMode: String,
    val delegate: String,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
)

/** The exact fixture every one of the five runs replayed. */
data class FiveRunReplayFixtureBinding(
    val fixtureId: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameIndices: List<Long>,
    val frameSha256: List<String>,
)

/**
 * One complete replay-through-export cycle: the real MediaPipe replay determinism bindings plus
 * what happened to its export. `validatedByReferenceCli` is only ever set true by the host wrapper
 * that actually ran `observation_contracts validate-session` against the pulled export — it is
 * never inferred on-device, because the Python reference validator cannot run there.
 */
data class FiveRunReplayRunRecord(
    val runIndex: Int,
    val sessionId: String,
    val published: Boolean,
    val submittedIndices: List<Long>,
    val backendTimestampsMs: List<Long>,
    val canonicalInputSha256: List<String>,
    val manifestSha256: String,
    val framesSha256: String,
    val validatedByReferenceCli: Boolean,
)

/**
 * One complete sanitized five-run replay observation, addressed at the five-run S25 device gate
 * (design section 7 / Task 5 brief). No landmark coordinate has anywhere to go in this shape, so
 * none can leak, matching [DetectionReplayEvidence]'s sanitization property.
 */
data class FiveRunReplayEvidence(
    val emittedBy: String,
    val environment: FiveRunReplayEnvironment,
    val provenance: FiveRunReplayProvenanceBinding,
    val fixture: FiveRunReplayFixtureBinding,
    val runs: List<FiveRunReplayRunRecord>,
)

/**
 * Renders [evidence] as one deterministic line of JSON, computing `satisfies_s25_device_gate`
 * itself rather than accepting it as an input.
 *
 * This is the single place the gate is decided. A caller can misreport its own environment or
 * runs, but it cannot assert the gate directly: the field is always the conjunction of
 * - exactly five runs;
 * - every run published and validated by the Python reference CLI;
 * - every run submitting the identical frame indices, backend timestamps, and canonical input
 *   digests (the same cross-run determinism equality design section 7 requires elsewhere);
 * - the observing device is a physical Samsung Galaxy S25 Ultra, never an emulator and never any
 *   other physical device.
 *
 * Five runs remain evidence collection, not proof of stability (design section 7): this function
 * only certifies that the five runs *happened* and *agreed*, never that agreement generalizes.
 */
fun renderFiveRunReplayEvidence(evidence: FiveRunReplayEvidence): String {
    require(evidence.runs.isNotEmpty()) { "a five-run replay observation with zero runs is not evidence" }

    val runsCompleted = evidence.runs.size
    val firstRun = evidence.runs.first()
    val allRunsConsistent = evidence.runs.all { run ->
        run.submittedIndices == firstRun.submittedIndices &&
            run.backendTimestampsMs == firstRun.backendTimestampsMs &&
            run.canonicalInputSha256 == firstRun.canonicalInputSha256
    }
    val allRunsPublishedAndValidated = evidence.runs.all { it.published && it.validatedByReferenceCli }
    val isAuthorizedS25 = evidence.environment.deviceClass == "physical_device" &&
        S25_ULTRA_MARKER in evidence.environment.marketingName
    val satisfiesGate = runsCompleted == 5 && allRunsConsistent && allRunsPublishedAndValidated && isAuthorizedS25

    return Json.encodeToString(JsonObject.serializer(), buildRecord(evidence, runsCompleted, satisfiesGate))
}

private fun buildRecord(evidence: FiveRunReplayEvidence, runsCompleted: Int, satisfiesGate: Boolean): JsonObject =
    buildJsonObject {
        put("artifact_schema_version", FIVE_RUN_REPLAY_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", FIVE_RUN_REPLAY_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("emitted_by", evidence.emittedBy)
        putJsonObject("environment") {
            put("device_class", evidence.environment.deviceClass)
            put("device_identity", evidence.environment.deviceIdentity)
            put("device_fingerprint", evidence.environment.deviceFingerprint)
            put("api_level", evidence.environment.apiLevel)
            put("android_release", evidence.environment.androidRelease)
            put("abi", evidence.environment.abi)
            put("manufacturer", evidence.environment.manufacturer)
            put("marketing_name", evidence.environment.marketingName)
        }
        putJsonObject("provenance") {
            put("model_asset_name", evidence.provenance.modelAssetName)
            put("model_sha256", evidence.provenance.modelSha256)
            put("tasks_vision_version", evidence.provenance.tasksVisionVersion)
            put("running_mode", evidence.provenance.runningMode)
            put("delegate", evidence.provenance.delegate)
            put("model_input_width", evidence.provenance.modelInputWidth)
            put("model_input_height", evidence.provenance.modelInputHeight)
        }
        putJsonObject("fixture") {
            put("fixture_id", evidence.fixture.fixtureId)
            put("frame_width", evidence.fixture.frameWidth)
            put("frame_height", evidence.fixture.frameHeight)
            putJsonArray("frame_indices") { evidence.fixture.frameIndices.forEach { add(it) } }
            putJsonArray("frame_sha256") { evidence.fixture.frameSha256.forEach { add(it) } }
        }
        put("runs_completed", runsCompleted)
        putJsonArray("runs") {
            evidence.runs.forEach { run ->
                add(
                    buildJsonObject {
                        put("run_index", run.runIndex)
                        put("session_id", run.sessionId)
                        put("published", run.published)
                        putJsonArray("submitted_indices") { run.submittedIndices.forEach { add(it) } }
                        putJsonArray("backend_timestamps_ms") { run.backendTimestampsMs.forEach { add(it) } }
                        putJsonArray("canonical_input_sha256") { run.canonicalInputSha256.forEach { add(it) } }
                        put("manifest_sha256", run.manifestSha256)
                        put("frames_sha256", run.framesSha256)
                        put("validated_by_reference_cli", run.validatedByReferenceCli)
                    },
                )
            }
        }
        // Five runs are evidence collection, not proof of stability (design section 7): this flag
        // certifies only that five consistent, published, host-validated runs were observed on the
        // one authorized physical device, never that the result generalizes beyond these five.
        put("satisfies_s25_device_gate", satisfiesGate)
    }
