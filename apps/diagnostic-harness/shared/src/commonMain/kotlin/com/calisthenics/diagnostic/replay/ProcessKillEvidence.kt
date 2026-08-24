package com.calisthenics.diagnostic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Discriminator so a reader can never mistake this for a replay or detection observation. */
const val PROCESS_KILL_RECORD_KIND: String = "process_kill_during_export_device_gate_observation"

/** The device the mid-commit kill was observed on. Mirrors [FiveRunReplayEnvironment]. */
data class ProcessKillEnvironment(
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

/**
 * What the verifying invocation actually observed after the killed process died mid-commit.
 *
 * These are observations, never caller-declared verdicts: [renderProcessKillEvidence] computes the
 * gate flag from them rather than trusting a supplied boolean, exactly as the five-run renderer
 * does.
 */
data class ProcessKillObservation(
    /** The storage operation label the kill was injected at, e.g. `destinationExists`. */
    val killedAtOperation: String,
    /** Whether the instrumentation transcript proved the process actually died. */
    val processCrashConfirmed: Boolean,
    /** Corruption entries found under the committed exports root; must be empty. */
    val exportRootCorruptions: List<String>,
    /** Staging attempts left behind for the killed session; exactly one is expected. */
    val stagingResidueCount: Int,
    /** How that residue classified. A killed process leaves crash residue, never an abort marker. */
    val stagingResidueClassification: String,
)

/** One complete sanitized process-kill observation, addressed at the section 14 Android check. */
data class ProcessKillEvidence(
    val emittedBy: String,
    val harnessGitSha: String,
    val environment: ProcessKillEnvironment,
    val sessionId: String,
    val observation: ProcessKillObservation,
) {
    init {
        require(HARNESS_GIT_SHA_PATTERN.matches(harnessGitSha)) {
            "harness_git_sha must be a lowercase 40-hex commit SHA"
        }
    }
}

private val HARNESS_GIT_SHA_PATTERN = Regex("^[0-9a-f]{40}$")

/**
 * Renders [evidence] as one deterministic line of JSON, computing
 * `satisfies_s25_process_kill_gate` from the observed fields.
 *
 * The gate is satisfied only when all of the following hold:
 *
 * - the process demonstrably crashed mid-commit — a run that completed normally proves nothing
 *   about crash safety, and every other condition is trivially satisfied by a session that never
 *   died;
 * - no partial entry appeared under the valid exports root, which is the section 16 fail
 *   condition this check exists to falsify;
 * - exactly one staging attempt was left behind, classified as crash residue rather than an abort
 *   marker, because a killed process cannot have written a deliberate abort;
 * - the observing device is the authorized physical S25 Ultra, never an emulator.
 *
 * The crash conjunct is load-bearing for the same reason the five-run renderer requires detection:
 * without it, a session that simply exported cleanly satisfies every remaining condition and the
 * gate certifies crash safety it never tested.
 */
fun renderProcessKillEvidence(evidence: ProcessKillEvidence): String {
    val observation = evidence.observation
    val environment = evidence.environment

    val isAuthorized = isAuthorizedS25Target(
        deviceClass = environment.deviceClass,
        manufacturer = environment.manufacturer,
        model = environment.model,
        marketingName = environment.marketingName,
    )
    val satisfiesGate = observation.processCrashConfirmed &&
        observation.exportRootCorruptions.isEmpty() &&
        observation.stagingResidueCount == 1 &&
        observation.stagingResidueClassification == "CrashResidue" &&
        isAuthorized

    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", PROCESS_KILL_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("emitted_by", evidence.emittedBy)
        put("harness_git_sha", evidence.harnessGitSha)
        put("session_id", evidence.sessionId)
        put(
            "environment",
            buildJsonObject {
                put("device_class", environment.deviceClass)
                put("device_identity", environment.deviceIdentity)
                put("device_fingerprint", environment.deviceFingerprint)
                put("api_level", environment.apiLevel)
                put("android_release", environment.androidRelease)
                put("abi", environment.abi)
                put("manufacturer", environment.manufacturer)
                put("model", environment.model)
                put("marketing_name", environment.marketingName)
            },
        )
        put(
            "observation",
            buildJsonObject {
                put("killed_at_operation", observation.killedAtOperation)
                put("process_crash_confirmed", observation.processCrashConfirmed)
                put("export_root_corruption_count", observation.exportRootCorruptions.size)
                put("staging_residue_count", observation.stagingResidueCount)
                put("staging_residue_classification", observation.stagingResidueClassification)
            },
        )
        put("satisfies_s25_process_kill_gate", satisfiesGate)
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
