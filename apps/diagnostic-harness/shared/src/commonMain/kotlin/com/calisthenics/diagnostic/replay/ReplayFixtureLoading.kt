package com.calisthenics.diagnostic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Raised when a committed `fixture_metadata.json` cannot be decoded into a [ReplayFixture]. */
class ReplayFixtureLoadingException(message: String) : IllegalArgumentException(message)

/**
 * Decodes one committed `fixture_metadata.json` (the shape both the contract-tier and detection-tier
 * fixtures share, see design section 7) into a [ReplayFixture]. [readPlane] is the only platform seam:
 * it is handed each frame's declared `path` and must return that frame's raw plane bytes, so the same
 * decoder serves an Android asset reader, a staged device-test resource reader, and a JVM host reader
 * without duplicating the JSON shape in each.
 */
fun decodeReplayFixtureMetadata(metadataJson: String, readPlane: (path: String) -> ByteArray): ReplayFixture {
    val root: JsonObject = try {
        Json.parseToJsonElement(metadataJson).jsonObject
    } catch (error: Exception) {
        throw ReplayFixtureLoadingException("fixture metadata is not a JSON object: ${error.message}")
    }

    val framesElement = root["frames"] ?: throw ReplayFixtureLoadingException("fixture metadata is missing field: frames")
    val frameWidthElement = root["frame_width"]
        ?: throw ReplayFixtureLoadingException("fixture metadata is missing field: frame_width")
    val frameHeightElement = root["frame_height"]
        ?: throw ReplayFixtureLoadingException("fixture metadata is missing field: frame_height")

    val frames = framesElement.jsonArray.map { element ->
        val descriptor = element.jsonObject
        val path = descriptor.getValue("path").jsonPrimitive.content
        ReplayFixtureFrame(
            frameIndex = descriptor.getValue("frame_index").jsonPrimitive.long,
            captureTimestampNs = descriptor.getValue("timestamp_ns").jsonPrimitive.long,
            plane = readPlane(path),
            declaredSha256 = descriptor.getValue("sha256").jsonPrimitive.content,
        )
    }

    return ReplayFixture(
        frames = frames,
        frameWidth = frameWidthElement.jsonPrimitive.int,
        frameHeight = frameHeightElement.jsonPrimitive.int,
    )
}
