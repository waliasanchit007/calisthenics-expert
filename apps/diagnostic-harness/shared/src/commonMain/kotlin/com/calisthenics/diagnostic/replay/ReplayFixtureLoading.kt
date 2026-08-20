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
        ).also(::requireDeclaredDigestMatchesBytes)
    }

    return ReplayFixture(
        frames = frames,
        frameWidth = frameWidthElement.jsonPrimitive.int,
        frameHeight = frameHeightElement.jsonPrimitive.int,
    )
}

/**
 * Fails closed when the bytes [readPlane] returned are not the bytes the fixture declares.
 *
 * The declared digest is a string in a JSON file; on its own it says nothing about the plane behind
 * it. Between the committed fixture and a decoded plane sit a Gradle staging task, an APK asset
 * merge, an `adb install`, and an asset reader, so "the metadata says this digest" and "these bytes
 * hash to this digest" are different claims. Without this check a run — and every determinism
 * comparison, export, and five-run gate record derived from it — could be evidence about silently
 * different pixels while transcribing the digests of the pixels it was supposed to read.
 *
 * The failure is the same typed [ReplayFixtureLoadingException] every other unusable-fixture case
 * raises, so a caller has one thing to catch and one fail-closed path, and the message names the
 * frame plus both digests rather than only reporting that something did not match.
 */
private fun requireDeclaredDigestMatchesBytes(frame: ReplayFixtureFrame) {
    if (frame.actualSha256 != frame.declaredSha256) {
        throw ReplayFixtureLoadingException(
            "fixture frame ${frame.frameIndex} plane does not match its declared digest: " +
                "declared ${frame.declaredSha256}, actual ${frame.actualSha256}",
        )
    }
}
