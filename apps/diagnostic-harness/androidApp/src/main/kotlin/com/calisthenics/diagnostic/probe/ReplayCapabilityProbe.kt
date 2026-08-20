package com.calisthenics.diagnostic.probe

import android.content.Context
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.model.verifyModelAsset
import com.calisthenics.diagnostic.replay.decodeReplayFixtureMetadata

/** Outcome of [ReplayCapabilityProbe.run]. */
sealed interface ReplayCapabilityOutcome {
    data object Available : ReplayCapabilityOutcome
    data class Unavailable(val reason: String) : ReplayCapabilityOutcome
}

/**
 * Non-participant diagnostic evidence (design section 12: "These values are diagnostics, not
 * quality scores") that the packaged model asset and detection fixture the real replay path needs
 * are present and byte-verified. This performs the same runtime checksum verification design
 * section 13 requires before a session may start, so a stale or corrupted packaged asset is
 * reported to the capability matrix before the user ever presses Start, rather than surfacing only
 * as an in-run failure.
 *
 * Deliberately does not construct a `PoseLandmarker`: that already happens, fail-closed, inside
 * [com.calisthenics.diagnostic.composition.RealReplayComposition.run] itself, and doing it twice
 * would only cost a redundant native construction for a diagnostic check.
 */
class ReplayCapabilityProbe(private val context: Context) {
    fun run(): ReplayCapabilityOutcome = try {
        val provenance = parseModelProvenance(readAsset("replay/model_provenance.json").decodeToString())
        val modelBytes = readAsset("replay/${provenance.assetName}")
        verifyModelAsset(provenance, Sha256Digest.hex(modelBytes), modelBytes.size.toLong())
        decodeReplayFixtureMetadata(readAsset("replay/detection/fixture_metadata.json").decodeToString()) { path ->
            readAsset("replay/detection/$path")
        }
        ReplayCapabilityOutcome.Available
    } catch (error: Exception) {
        ReplayCapabilityOutcome.Unavailable(error.message ?: "replay_capability_check_failed")
    }

    private fun readAsset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}
