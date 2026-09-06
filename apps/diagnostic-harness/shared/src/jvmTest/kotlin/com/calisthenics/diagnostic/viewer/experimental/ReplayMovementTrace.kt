package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.ArmJoint
import com.calisthenics.diagnostic.viewer.ArmTrackingIssue
import com.calisthenics.diagnostic.viewer.JointIssueReason
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.longOrNull

enum class ReplayTraceEvent {
    START_CAMERA,
    START_SYNTHETIC,
    SAMPLE,
    CALIBRATE,
    CONFIRM,
    RESET,
    STOP,
}

enum class ReplayTraceFeed { FRESH, WAITING, NO_POSE, RESULT_TIMEOUT, DUPLICATE, BACKWARDS }

data class ReplayTraceRow(
    val session: String,
    val sequence: Long,
    val event: ReplayTraceEvent,
    val feed: ReplayTraceFeed,
    val captureNs: Long?,
    val decisionNs: Long,
    val side: ArmSide?,
    val leftElbowDegrees: Double?,
    val rightElbowDegrees: Double?,
    val leftWristHeight: Double?,
    val rightWristHeight: Double?,
    val leftConfidence: Double?,
    val rightConfidence: Double?,
    val availableArmJoints: Int,
    val leftTrackingIssue: ArmTrackingIssue? = null,
    val rightTrackingIssue: ArmTrackingIssue? = null,
)

class ReplayTraceFormatException(message: String) : IllegalArgumentException(message)

object ReplayTraceParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(lines: Sequence<String>): List<ReplayTraceRow> {
        val rows = ArrayList<ReplayTraceRow>()
        var expectedSequence = 0L
        var session: String? = null

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (line.isBlank()) invalid(lineNumber, "blank JSONL row")
            val root = try {
                json.parseToJsonElement(line).let { element ->
                    element as? JsonObject ?: invalid(lineNumber, "row must be a JSON object")
                }
            } catch (error: ReplayTraceFormatException) {
                throw error
            } catch (error: Exception) {
                invalid(lineNumber, "invalid JSON: ${error.message ?: "parse failure"}")
            }

            if (root.requiredInt(lineNumber, "v") != 2) {
                invalid(lineNumber, "only exact v2 traces are supported")
            }
            if (root.requiredDouble(lineNumber, "angle_quantum_deg") != 0.0 ||
                root.requiredDouble(lineNumber, "height_quantum") != 0.0
            ) {
                invalid(lineNumber, "quantized or non-exact v2 trace row")
            }

            val rowSession = root.requiredString(lineNumber, "session")
            if (rowSession.isEmpty()) invalid(lineNumber, "session must not be empty")
            if (session == null) session = rowSession
            if (rowSession != session) invalid(lineNumber, "all rows must belong to one session")

            val sequence = root.requiredLong(lineNumber, "seq")
            if (sequence != expectedSequence) {
                invalid(lineNumber, "seq must be monotonic and contiguous; expected $expectedSequence, got $sequence")
            }
            if (expectedSequence != Long.MAX_VALUE) expectedSequence++

            val event = root.requiredEnum(lineNumber, "event", ReplayTraceEvent.entries)
            val feed = root.requiredEnum(lineNumber, "feed", ReplayTraceFeed.entries)
            val side = root.optionalEnum(lineNumber, "side", ArmSide.entries)
            val captureNs = root.optionalLong(lineNumber, "capture_ns")
            val availableArmJoints = root.requiredInt(lineNumber, "available_arm_joints")
            if (availableArmJoints < 0) invalid(lineNumber, "available_arm_joints must not be negative")

            if (event == ReplayTraceEvent.CALIBRATE && side == null) {
                invalid(lineNumber, "CALIBRATE requires side")
            }
            if (event == ReplayTraceEvent.SAMPLE && feed == ReplayTraceFeed.FRESH && captureNs == null) {
                invalid(lineNumber, "FRESH SAMPLE requires capture_ns")
            }

            rows += ReplayTraceRow(
                session = rowSession,
                sequence = sequence,
                event = event,
                feed = feed,
                captureNs = captureNs,
                decisionNs = root.requiredLong(lineNumber, "decision_ns"),
                side = side,
                leftElbowDegrees = root.optionalDouble(lineNumber, "left_elbow_deg"),
                rightElbowDegrees = root.optionalDouble(lineNumber, "right_elbow_deg"),
                leftWristHeight = root.optionalDouble(lineNumber, "left_wrist_height"),
                rightWristHeight = root.optionalDouble(lineNumber, "right_wrist_height"),
                leftConfidence = root.optionalDouble(lineNumber, "left_confidence"),
                rightConfidence = root.optionalDouble(lineNumber, "right_confidence"),
                availableArmJoints = availableArmJoints,
                leftTrackingIssue = root.trackingIssue(lineNumber,"left"),
                rightTrackingIssue = root.trackingIssue(lineNumber,"right"),
            )
        }

        if (rows.isEmpty()) throw ReplayTraceFormatException("trace must contain at least one row")
        return rows
    }

    fun parse(path: Path): List<ReplayTraceRow> = parse(Files.readAllLines(path).asSequence())

    private fun JsonObject.trackingIssue(line: Int, side: String): ArmTrackingIssue? {
        val joint=optionalEnum(line,"${side}_unavailable_joint",ArmJoint.entries)
        val reason=optionalEnum(line,"${side}_unavailable_reason",JointIssueReason.entries)
        if(joint==null && reason==null) return null // Older exact traces have neither field.
        if(joint==null || reason==null) invalid(line,"$side joint diagnostics require both joint and reason")
        return ArmTrackingIssue(joint,reason)
    }

    private fun JsonObject.requiredString(line: Int, key: String): String {
        val primitive = requiredPrimitive(line, key)
        if (!primitive.isString) invalid(line, "$key must be a JSON string")
        return primitive.content
    }

    private fun JsonObject.requiredLong(line: Int, key: String): Long {
        val primitive = requiredPrimitive(line, key)
        if (primitive.isString) invalid(line, "$key must be an integer JSON number")
        return primitive.longOrNull ?: invalid(line, "$key must be an integer JSON number")
    }

    private fun JsonObject.requiredInt(line: Int, key: String): Int {
        val primitive = requiredPrimitive(line, key)
        if (primitive.isString) invalid(line, "$key must be an integer JSON number")
        return primitive.intOrNull ?: invalid(line, "$key must be an integer JSON number")
    }

    private fun JsonObject.requiredDouble(line: Int, key: String): Double {
        val primitive = requiredPrimitive(line, key)
        if (primitive.isString) invalid(line, "$key must be a finite JSON number")
        return primitive.doubleOrNull?.takeIf { it.isFinite() }
            ?: invalid(line, "$key must be a finite JSON number")
    }

    private fun JsonObject.optionalLong(line: Int, key: String): Long? {
        val primitive = optionalPrimitive(line, key) ?: return null
        if (primitive.isString) invalid(line, "$key must be an integer JSON number or null")
        return primitive.longOrNull ?: invalid(line, "$key must be an integer JSON number or null")
    }

    private fun JsonObject.optionalDouble(line: Int, key: String): Double? {
        val primitive = optionalPrimitive(line, key) ?: return null
        if (primitive.isString) invalid(line, "$key must be a finite JSON number or null")
        return primitive.doubleOrNull?.takeIf { it.isFinite() }
            ?: invalid(line, "$key must be a finite JSON number or null")
    }

    private fun <T : Enum<T>> JsonObject.requiredEnum(line: Int, key: String, values: List<T>): T {
        val name = requiredString(line, key)
        return values.singleOrNull { it.name == name }
            ?: invalid(line, "$key has unsupported value $name")
    }

    private fun <T : Enum<T>> JsonObject.optionalEnum(line: Int, key: String, values: List<T>): T? {
        val primitive = optionalPrimitive(line, key) ?: return null
        if (!primitive.isString) invalid(line, "$key must be a JSON string or null")
        return values.singleOrNull { it.name == primitive.content }
            ?: invalid(line, "$key has unsupported value ${primitive.content}")
    }

    private fun JsonObject.requiredPrimitive(line: Int, key: String): JsonPrimitive =
        get(key)?.let { it as? JsonPrimitive } ?: invalid(line, "$key is required and must be a primitive")

    private fun JsonObject.optionalPrimitive(line: Int, key: String): JsonPrimitive? {
        val element = get(key) ?: return null
        if (element is JsonNull) return null
        return element as? JsonPrimitive ?: invalid(line, "$key must be a primitive or null")
    }

    private fun invalid(line: Int, message: String): Nothing =
        throw ReplayTraceFormatException("line $line: $message")
}

data class ReplayTransition(
    val sequence: Long,
    val captureNs: Long?,
    val event: ReplayTraceEvent,
    val stage: ExperimentStage,
    val phase: CyclePhase,
    val count: Int,
)

data class ReplayResult(
    val transitions: List<ReplayTransition>,
    val finalState: PullUpExperimentState,
    val finalIssue: ExperimentInputIssue?,
)

object ReplayMovementTrace {
    fun replay(rows: List<ReplayTraceRow>): ReplayResult {
        require(rows.isNotEmpty()) { "replay requires at least one trace row" }
        val experiment = PullUpExperiment()
        val transitions = mutableListOf<ReplayTransition>()
        var previousSignature = signature(experiment.state)

        rows.forEach { row ->
            when (row.event) {
                ReplayTraceEvent.START_CAMERA,
                ReplayTraceEvent.START_SYNTHETIC,
                ReplayTraceEvent.RESET,
                ReplayTraceEvent.STOP -> experiment.reset()
                ReplayTraceEvent.CALIBRATE -> experiment.startCalibration(checkNotNull(row.side), row.decisionNs)
                ReplayTraceEvent.CONFIRM -> experiment.confirmCalibration()
                ReplayTraceEvent.SAMPLE -> when (row.feed) {
                    ReplayTraceFeed.FRESH -> experiment.onSample(row.measurements(), row.decisionNs)
                    ReplayTraceFeed.NO_POSE,
                    ReplayTraceFeed.RESULT_TIMEOUT -> experiment.onTrackingGap()
                    ReplayTraceFeed.BACKWARDS -> experiment.onBackwardsTimestamp()
                    ReplayTraceFeed.WAITING,
                    ReplayTraceFeed.DUPLICATE -> Unit
                }
            }

            val state = experiment.state
            val currentSignature = signature(state)
            if (currentSignature != previousSignature) {
                transitions += ReplayTransition(
                    sequence = row.sequence,
                    captureNs = row.captureNs,
                    event = row.event,
                    stage = state.stage,
                    phase = state.phase,
                    count = state.candidateCycles,
                )
                previousSignature = currentSignature
            }
        }

        return ReplayResult(transitions, experiment.state, experiment.inputIssue)
    }

    fun replay(path: Path): ReplayResult = replay(ReplayTraceParser.parse(path))

    fun renderJson(result: ReplayResult): String {
        val state = result.finalState
        val root = buildJsonObject {
            putJsonArray("events") {
                result.transitions.forEach { transition ->
                    add(buildJsonObject {
                        put("seq", transition.sequence)
                        put("capture_ns", transition.captureNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("event", transition.event.name)
                        put("stage", transition.stage.name)
                        put("phase", transition.phase.name)
                        put("count", transition.count)
                    })
                }
            }
            putJsonObject("counts") {
                put("candidate_cycles", state.candidateCycles)
            }
            putJsonObject("calibration") {
                put("stage", state.stage.name)
                put("phase", state.phase.name)
                put("side", state.side.name)
                put("range_low_degrees", state.range?.lowDegrees?.let(::JsonPrimitive) ?: JsonNull)
                put("range_high_degrees", state.range?.highDegrees?.let(::JsonPrimitive) ?: JsonNull)
                put("failure", state.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("input_issue", result.finalIssue?.name?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    private fun ReplayTraceRow.measurements(): MovementMeasurements = MovementMeasurements(
        capturedNs = checkNotNull(captureNs),
        left = ArmMeasurements(leftElbowDegrees, leftWristHeight, leftConfidence, leftTrackingIssue),
        right = ArmMeasurements(rightElbowDegrees, rightWristHeight, rightConfidence, rightTrackingIssue),
        availableArmJoints = availableArmJoints,
    )

    private data class StateSignature(
        val stage: ExperimentStage,
        val side: ArmSide,
        val phase: CyclePhase,
        val count: Int,
        val range: ObservedRange?,
        val failure: CalibrationFailure?,
    )

    private fun signature(state: PullUpExperimentState) = StateSignature(
        stage = state.stage,
        side = state.side,
        phase = state.phase,
        count = state.candidateCycles,
        range = state.range,
        failure = state.failure,
    )
}

fun main(args: Array<String>) {
    require(args.size == 1 && args[0].startsWith("-PtracePath=")) {
        "expected exactly one -PtracePath=/path/to/exact-v2-trace.jsonl argument"
    }
    val path = Paths.get(args[0].substringAfter("=", missingDelimiterValue = ""))
    require(Files.isRegularFile(path)) { "trace path is not a regular file: $path" }
    println(ReplayMovementTrace.renderJson(ReplayMovementTrace.replay(path)))
}
