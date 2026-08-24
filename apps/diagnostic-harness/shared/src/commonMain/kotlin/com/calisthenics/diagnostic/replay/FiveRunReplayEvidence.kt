package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.contract.RawPoseFrame
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

/**
 * Whether [environment] is the one authorized physical five-run gate target.
 *
 * Delegates to [isAuthorizedS25Target], the single home for this rule.
 */
fun isAuthorizedFiveRunGateTarget(environment: FiveRunReplayEnvironment): Boolean =
    isAuthorizedS25Target(
        deviceClass = environment.deviceClass,
        manufacturer = environment.manufacturer,
        model = environment.model,
        marketingName = environment.marketingName,
    )

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
    val model: String,
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
 * Derives the fixture binding from the loaded fixture itself, hashing the plane bytes the run
 * actually read.
 *
 * `frameSha256` must never be transcribed from the fixture metadata's declared digests. Those are
 * strings in a JSON file that travelled to the device beside the planes, so copying them binds the
 * record to what the fixture *claimed* rather than to what was replayed, and a corrupted plane
 * would be recorded under the digest of the plane it was supposed to be.
 * [ReplayFixtureFrame.actualSha256] is computed from the bytes in hand;
 * [decodeReplayFixtureMetadata] separately refuses a fixture whose declared and actual digests
 * disagree, so a record built here is bound twice over.
 *
 * Derivation lives in `commonMain` rather than in the Android device test so it is covered by host
 * tests, matching [fiveRunPoseObservationOf] and `frameTopologyOf`.
 */
fun fiveRunFixtureBindingOf(fixtureId: String, fixture: ReplayFixture): FiveRunReplayFixtureBinding =
    FiveRunReplayFixtureBinding(
        fixtureId = fixtureId,
        frameWidth = fixture.frameWidth,
        frameHeight = fixture.frameHeight,
        frameIndices = fixture.declaredIndices,
        frameSha256 = fixture.frames.map { it.actualSha256 },
    )

/**
 * What one run's own emitted records say about pose detection.
 *
 * Without this, the five-run gate is satisfiable by an empty pose pipeline: identical frame
 * indices, identical backend timestamps, and identical canonical input digests are all properties
 * of the *input* side, and a run in which MediaPipe returned no pose on any frame reproduces every
 * one of them perfectly. The gate would then certify five runs of a pipeline that observed nothing
 * — exactly the vacuity design section 7 introduced the detection tier to close for check 5.
 *
 * `poseDetectedRecords` is the session's own count, and `posePresentPerFrame` is the per-record
 * pattern behind it; carrying both lets the gate reject a count that no record supports.
 */
data class FiveRunReplayPoseObservation(
    val posePresentPerFrame: List<Boolean>,
    val normalizedLandmarkCountPerFrame: List<Int>,
    val worldLandmarkCountPerFrame: List<Int>,
    val poseDetectedRecords: Long,
)

/**
 * Derives one run's pose observation from the records the session actually emitted.
 *
 * Landmark *values* are deliberately absent, exactly as in [FrameTopology]: what is recorded is
 * whether a pose came back and how many landmarks stood behind it. Turning coordinates into a
 * threshold is what check 6 forbids.
 */
fun fiveRunPoseObservationOf(
    records: List<RawPoseFrame>,
    poseDetectedRecords: Long,
): FiveRunReplayPoseObservation = FiveRunReplayPoseObservation(
    posePresentPerFrame = records.map { it.posePresent },
    normalizedLandmarkCountPerFrame = records.map { it.normalizedImageLandmarks?.size ?: 0 },
    worldLandmarkCountPerFrame = records.map { it.worldLandmarks?.size ?: 0 },
    poseDetectedRecords = poseDetectedRecords,
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
    val pose: FiveRunReplayPoseObservation,
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
    val harnessGitSha: String,
    val environment: FiveRunReplayEnvironment,
    val provenance: FiveRunReplayProvenanceBinding,
    val fixture: FiveRunReplayFixtureBinding,
    val runs: List<FiveRunReplayRunRecord>,
) {
    init {
        require(HARNESS_GIT_SHA_PATTERN.matches(harnessGitSha)) {
            "harness_git_sha must be a lowercase 40-hex commit SHA"
        }
    }
}

private val HARNESS_GIT_SHA_PATTERN = Regex("^[0-9a-f]{40}$")

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
 * - every run actually detecting a pose — a nonzero detected-record count that the run's own
 *   per-frame records support, with landmarks behind every detection, and the identical
 *   detection pattern and cardinality in all five runs;
 * - the observing device is a physical Samsung Galaxy S25 Ultra, never an emulator and never any
 *   other physical device.
 *
 * The detection conjunct is not decoration. Frame indices, backend timestamps, and canonical input
 * digests are all input-side properties that a pipeline detecting nothing reproduces exactly, so
 * without it five identical empty runs satisfy every other condition and the gate certifies an
 * empty pose pipeline (see [FiveRunReplayPoseObservation]).
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
            run.canonicalInputSha256 == firstRun.canonicalInputSha256 &&
            run.pose == firstRun.pose
    }
    val allRunsDetectedPoses = evidence.runs.all(::detectionIsNonVacuous)
    val allRunsPublishedAndValidated = evidence.runs.all { it.published && it.validatedByReferenceCli }
    val isAuthorizedS25 = isAuthorizedFiveRunGateTarget(evidence.environment)
    val satisfiesGate = runsCompleted == 5 && allRunsConsistent && allRunsDetectedPoses &&
        allRunsPublishedAndValidated && isAuthorizedS25

    return Json.encodeToString(JsonObject.serializer(), buildRecord(evidence, runsCompleted, satisfiesGate))
}

/**
 * Whether one run's records show detection actually happened, rather than only claiming it.
 *
 * Every clause closes a way a run with no detection could otherwise read as one:
 * - one record per submitted frame, so a run that submitted six frames and emitted none is not
 *   silently described by an empty pose pattern;
 * - a nonzero detected-record count, which is the finding itself: five identical runs of an empty
 *   pipeline agree on everything else;
 * - the count equal to the number of `pose_present` records, so a declared count no record
 *   supports is rejected rather than believed;
 * - landmarks behind every detection, because `pose_present = true` with an empty landmark list is
 *   the same vacuity wearing a boolean (the rule [DetectionTopologyCheck.requireDetectionFired]
 *   already applies to check 5).
 */
private fun detectionIsNonVacuous(run: FiveRunReplayRunRecord): Boolean {
    val pose = run.pose
    val frameCount = run.submittedIndices.size
    if (pose.posePresentPerFrame.size != frameCount) return false
    if (pose.normalizedLandmarkCountPerFrame.size != frameCount) return false
    if (pose.worldLandmarkCountPerFrame.size != frameCount) return false
    if (pose.poseDetectedRecords <= 0L) return false
    if (pose.posePresentPerFrame.count { it }.toLong() != pose.poseDetectedRecords) return false
    return pose.posePresentPerFrame.indices.all { position ->
        !pose.posePresentPerFrame[position] ||
            (
                pose.normalizedLandmarkCountPerFrame[position] > 0 &&
                    pose.worldLandmarkCountPerFrame[position] > 0
                )
    }
}

private fun buildRecord(evidence: FiveRunReplayEvidence, runsCompleted: Int, satisfiesGate: Boolean): JsonObject =
    buildJsonObject {
        put("artifact_schema_version", FIVE_RUN_REPLAY_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", FIVE_RUN_REPLAY_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("emitted_by", evidence.emittedBy)
        put("harness_git_sha", evidence.harnessGitSha)
        putJsonObject("environment") {
            put("device_class", evidence.environment.deviceClass)
            put("device_identity", evidence.environment.deviceIdentity)
            put("device_fingerprint", evidence.environment.deviceFingerprint)
            put("api_level", evidence.environment.apiLevel)
            put("android_release", evidence.environment.androidRelease)
            put("abi", evidence.environment.abi)
            put("manufacturer", evidence.environment.manufacturer)
            put("model", evidence.environment.model)
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
                        putJsonArray("pose_present_per_frame") { run.pose.posePresentPerFrame.forEach { add(it) } }
                        putJsonArray("normalized_landmark_count_per_frame") {
                            run.pose.normalizedLandmarkCountPerFrame.forEach { add(it) }
                        }
                        putJsonArray("world_landmark_count_per_frame") {
                            run.pose.worldLandmarkCountPerFrame.forEach { add(it) }
                        }
                        put("pose_detected_records", run.pose.poseDetectedRecords)
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
