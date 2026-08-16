package com.calisthenics.diagnostic.capability

enum class CapabilityId { CAMERA_PERMISSION, CAMERA_SOURCE, TIMESTAMP_COMPARABILITY, COLOR_METADATA, MODEL_ASSET, STORAGE_DURABILITY, POSE_BACKEND, THERMAL_API }

sealed interface CapabilityState {
    data object Available : CapabilityState
    data class Unavailable(val reason: String) : CapabilityState
    data class Unknown(val reason: String) : CapabilityState
}

data class Capability(val id: CapabilityId, val state: CapabilityState)
enum class HarnessMode { REPLAY, LIVE }

private val replayRequirements = setOf(CapabilityId.MODEL_ASSET, CapabilityId.STORAGE_DURABILITY, CapabilityId.POSE_BACKEND)
private val liveRequirements = replayRequirements + setOf(CapabilityId.CAMERA_PERMISSION, CapabilityId.CAMERA_SOURCE, CapabilityId.TIMESTAMP_COMPARABILITY, CapabilityId.COLOR_METADATA, CapabilityId.THERMAL_API)

fun requiredCapabilities(mode: HarnessMode): Set<CapabilityId> = when (mode) {
    HarnessMode.REPLAY -> replayRequirements
    HarnessMode.LIVE -> liveRequirements
}

fun canStart(mode: HarnessMode, capabilities: List<Capability>, sessionActive: Boolean): Boolean {
    if (sessionActive) return false
    val byId = capabilities.associateBy(Capability::id)
    return requiredCapabilities(mode).all { byId[it]?.state is CapabilityState.Available }
}

fun unavailableReasons(mode: HarnessMode, capabilities: List<Capability>, sessionActive: Boolean): List<String> {
    val byId = capabilities.associateBy(Capability::id)
    val reasons = requiredCapabilities(mode).mapNotNull { id ->
        when (val state = byId[id]?.state) {
            is CapabilityState.Available -> null
            is CapabilityState.Unavailable -> "${id.name}: ${state.reason}"
            is CapabilityState.Unknown -> "${id.name}: ${state.reason}"
            null -> "${id.name}: capability not reported"
        }
    }
    return if (sessionActive) reasons + "SESSION: a session is already active" else reasons
}

fun iosUnavailableCapabilities(): List<Capability> = CapabilityId.entries.map { id ->
    Capability(id, CapabilityState.Unavailable("iOS adapter is not implemented in Phase 0"))
}
